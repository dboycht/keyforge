package com.dboycht.keyforge.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dboycht.keyforge.R
import com.dboycht.keyforge.layout.Keyboards
import com.dboycht.keyforge.layout.KeyboardLayout
import com.dboycht.keyforge.session.HidSession
import com.dboycht.keyforge.session.HidSessionManager
import com.dboycht.keyforge.session.PairedDevice
import com.dboycht.keyforge.session.SessionPhase
import com.dboycht.keyforge.session.SessionResult
import com.dboycht.keyforge.session.SessionUiState
import com.dboycht.keyforge.settings.KeyboardSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The minimal keyboard: enough to prove a keystroke really reaches a host, with
 * the session diagnostics visible on the same screen.
 *
 * The layout deliberately keeps logic out of the composable - anything that can
 * be computed (which key, which usage ID, what to send) lives in
 * `com.dboycht.keyforge.session` / `hid` and is unit tested. This file only wires
 * state to widgets.
 */
class KeyboardActivity : ComponentActivity() {

    private lateinit var session: HidSession
    private lateinit var fullscreen: FullscreenController
    private lateinit var settings: KeyboardSettings

    /** Android 13+ needs POST_NOTIFICATIONS for the ongoing session notification. */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denied is acceptable: the session still runs, only the notice is hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The service owns the session and the single HID registration; this screen
        // only observes it and sends keys.
        session = HidSessionManager.get(this)
        settings = KeyboardSettings.get(this)
        fullscreen = FullscreenController(this)
        // Restore the remembered mode: the user asked for a full screen keyboard, so it
        // must still be one next time. A mode that forgets itself reads as a bug.
        fullscreen.set(settings.fullscreen.value)
        KeyboardService.start(this)
        ensureNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KeyboardScreen(
                        session = session,
                        fullscreen = fullscreen,
                        settings = settings,
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system can restore the bars on transitions (dialogs, returning from the
        // launcher); re-assert our state whenever we regain focus.
        if (hasFocus) fullscreen.reapply()
    }

    override fun onDestroy() {
        // Deliberately NOT stopping the session here: it lives in KeyboardService so
        // the keyboard survives leaving this screen. Use the notification's "stop"
        // action (or the in-app button) to end a session.
        super.onDestroy()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun KeyboardScreen(
    session: HidSession,
    fullscreen: FullscreenController,
    settings: KeyboardSettings,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // ONE worker, not a thread pool: auto-repeat queues a keystroke every ~60 ms, and
    // running those concurrently let several threads into the session's shared state
    // (that crashed the process - see ERROR.md E9). Serialising them also keeps
    // keystroke order, which is what typing means.
    val keyDispatcher = remember { Dispatchers.Default.limitedParallelism(1) }
    val state by session.state.collectAsState()
    val modifierLatch by settings.modifierLatch.collectAsState()
    val fullscreenEnabled by settings.fullscreen.collectAsState()
    // Which layout is on screen. The choice is remembered, and switching is a data
    // change: the renderer below is the same for every layout.
    var layout by remember { mutableStateOf(Keyboards.byId(settings.layoutId.value)) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    // Which overlay is open in full screen. One at a time, and it closes on selection
    // so the user always ends up back at the keyboard.
    var panel by remember { mutableStateOf(FullscreenPanel.NONE) }

    DisposableEffect(lifecycleOwner) {
        session.start()
        onDispose { /* Activity#onDestroy stops the session */ }
    }

    // Keep the window in step with the setting, including when it is toggled from the
    // settings row (not only from onCreate).
    LaunchedEffect(fullscreenEnabled) { fullscreen.set(fullscreenEnabled) }

    fun switchLayout(picked: KeyboardLayout) {
        if (picked.id == layout.id) return
        layout = picked
        settings.setLayoutId(picked.id)
        // A layout switch while keys are down would leave them stuck: release
        // everything before the new grid appears.
        run { session.releaseAll() }
    }

    fun run(action: suspend () -> SessionResult) {
        scope.launch {
            val result = withContext(keyDispatcher) { action() }
            lastResult = when (result) {
                is SessionResult.Ok -> "OK: ${result.detail}"
                is SessionResult.Rejected -> "REJECTED: ${result.reason}"
            }
        }
    }

    Scaffold { innerPadding ->
        if (fullscreenEnabled) {
            // Full screen: the system bars are hidden, so the window insets are
            // deliberately NOT applied - the keyboard is meant to own the whole screen.
            FullscreenLayout(
                session = session,
                state = state,
                layout = layout,
                scope = scope,
                keyDispatcher = keyDispatcher,
                modifierLatch = modifierLatch,
                settings = settings,
                panel = panel,
                onPanelChange = { panel = it },
                onLayoutPick = { switchLayout(it) },
                onExitFullscreen = { settings.setFullscreen(false) },
                lastResult = lastResult,
                run = ::run,
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // The diagnostics scroll; the keyboard below is pinned so it is always fully
            // visible. Before this the page scrolled as a whole and the bottom key row
            // was clipped by the screen edge (measured: Shift had an 11 px tall hit
            // area), which made the bottom row nearly untappable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.keyboard_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                StatusLine(session)

                lastResult?.let { line ->
                    Text(
                        text = line,
                        color = if (line.startsWith("OK")) PassGreen else FatalRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.height(6.dp))
                HostRow(
                    session = session,
                    paired = state.pairedDevices,
                    connected = state.connectedDevices,
                ) { device -> run { session.connect(device.device) } }

                Spacer(Modifier.height(6.dp))
                LayoutPicker(
                    current = layout,
                    onPick = { switchLayout(it) },
                )

                Spacer(Modifier.height(4.dp))
                ModifierModeRow(
                    latch = modifierLatch,
                    onToggle = { enabled ->
                        settings.setModifierLatch(enabled)
                        // Switching modes must not leave a modifier stuck down.
                        run { session.releaseAll() }
                    },
                    fullscreen = fullscreenEnabled,
                    onFullscreenToggle = { settings.setFullscreen(it) },
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.keyboard_section_log),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(state.log) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            // Pinned keyboard. `weight` works now that KeyboardView measures its own
            // height (BoxWithConstraints) instead of demanding a fixed size - the fixed
            // size was the earlier "grid collapses to zero" trap (ERROR.md E5).
            KeyboardGrid(
                layout = layout,
                session = session,
                scope = scope,
                keyDispatcher = keyDispatcher,
                modifierLatch = modifierLatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f),
            )
        }
    }
}

/** Which overlay is open in full screen mode. */
private enum class FullscreenPanel { NONE, DEVICES, SETTINGS }

/**
 * The key grid, wired to the session.
 *
 * Extracted so the full screen layout and the normal layout render the *same* keyboard:
 * two copies would drift, and a keyboard that behaves differently depending on a display
 * mode is a bug factory.
 */
@Composable
private fun KeyboardGrid(
    layout: KeyboardLayout,
    session: HidSession,
    scope: CoroutineScope,
    keyDispatcher: CoroutineDispatcher,
    modifierLatch: Boolean,
    modifier: Modifier = Modifier,
) {
    KeyboardView(
        layout = layout,
        modifier = modifier,
        // Ordinary key: one tap = one keystroke, nothing stays held.
        onKeyTap = { key ->
            key.usage?.let { usage ->
                scope.launch(keyDispatcher) { session.tapKey(key.keyCode, usage) }
            }
        },
        // Holding a key keeps sending (pulse output), like a real keyboard.
        onKeyRepeat = { key ->
            key.usage?.let { usage ->
                scope.launch(keyDispatcher) { session.tapKey(key.keyCode, usage) }
            }
        },
        // Physical-keyboard style by default (hold = active, lift = release);
        // the settings switch turns the phone-style latch back on.
        modifierLatch = modifierLatch,
        onModifierChanged = { key, on ->
            key.usage?.let { usage ->
                scope.launch(keyDispatcher) {
                    if (on) session.pressKey(key.keyCode, usage)
                    else session.releaseKey(key.keyCode, usage)
                }
            }
        },
    )
}

@Composable
private fun ModifierModeRow(
    latch: Boolean,
    onToggle: (Boolean) -> Unit,
    fullscreen: Boolean,
    onFullscreenToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.keyboard_section_settings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.keyboard_fullscreen),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = fullscreen, onCheckedChange = onFullscreenToggle)
            Text(
                text = stringResource(R.string.keyboard_fullscreen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.keyboard_modifier_latch),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 40.dp),
            )
            Switch(checked = latch, onCheckedChange = onToggle)
            Text(
                text = if (latch) {
                    "粘滞：点一下亮、再点一下灭"
                } else {
                    "默认同电脑键盘：按住生效、松手释放"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LayoutPicker(current: KeyboardLayout, onPick: (KeyboardLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.keyboard_section_layout),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A dialog instead of a row of chips: the chips stop fitting once there are more
        // than a couple of layouts, and they cost a permanent row of vertical space.
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text(current.displayName, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(R.string.keyboard_layout_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) {
        LayoutChoiceDialog(
            current = current,
            onPick = {
                open = false
                onPick(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** Layout chooser dialog, shared by full screen mode and the normal layout. */
@Composable
private fun LayoutChoiceDialog(
    current: KeyboardLayout,
    onPick: (KeyboardLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keyboard_action_choose_layout)) },
        text = {
            Column {
                Keyboards.all.forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = candidate.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (candidate.id == current.id) PassGreen
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (candidate.id == current.id) {
                            Text(
                                text = stringResource(R.string.keyboard_chooser_connected),
                                style = MaterialTheme.typography.bodySmall,
                                color = PassGreen,
                            )
                        } else {
                            Button(
                                onClick = { onPick(candidate) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.keyboard_chooser_connect),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.keyboard_chooser_close))
            }
        },
    )
}

/**
 * Full screen keyboard: the key grid owns the window and the controls collapse into a
 * thin bar plus on-demand panels.
 *
 * Design constraints that shaped this:
 * - **The keyboard gets everything left over** (`weight(1f)`), never a fixed size.
 * - **There is always a visible way out**: the bar carries an explicit exit control, and
 *   the system bars can also be swiped back (see [FullscreenController]).
 * - The panels live *inside* the window rather than as dialogs, so opening them does not
 *   resize or re-layout the key grid.
 */
@Composable
private fun FullscreenLayout(
    session: HidSession,
    state: SessionUiState,
    layout: KeyboardLayout,
    scope: CoroutineScope,
    keyDispatcher: CoroutineDispatcher,
    modifierLatch: Boolean,
    settings: KeyboardSettings,
    panel: FullscreenPanel,
    onPanelChange: (FullscreenPanel) -> Unit,
    onLayoutPick: (KeyboardLayout) -> Unit,
    onExitFullscreen: () -> Unit,
    lastResult: String?,
    run: (suspend () -> SessionResult) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        FullscreenBar(
            state = state,
            layout = layout,
            onTogglePanel = { wanted ->
                onPanelChange(if (panel == wanted) FullscreenPanel.NONE else wanted)
            },
            onLayoutPick = onLayoutPick,
            onExitFullscreen = onExitFullscreen,
        )

        when (panel) {
            FullscreenPanel.DEVICES -> FullscreenPanelCard(
                title = stringResource(R.string.keyboard_panel_devices),
                onClose = { onPanelChange(FullscreenPanel.NONE) },
            ) {
                HostRow(
                    session = session,
                    paired = state.pairedDevices,
                    connected = state.connectedDevices,
                ) { device -> run { session.connect(device.device) } }
            }

            FullscreenPanel.SETTINGS -> FullscreenPanelCard(
                title = stringResource(R.string.keyboard_panel_settings),
                onClose = { onPanelChange(FullscreenPanel.NONE) },
            ) {
                ModifierModeRow(
                    latch = modifierLatch,
                    onToggle = {
                        settings.setModifierLatch(it)
                        run { session.releaseAll() }
                    },
                    fullscreen = true,
                    onFullscreenToggle = { onExitFullscreen() },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = lastResult ?: stringResource(R.string.keyboard_section_log),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        lastResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        lastResult.startsWith("OK") -> PassGreen
                        else -> FatalRed
                    },
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    LazyColumn(modifier = Modifier.padding(6.dp)) {
                        items(state.log) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            FullscreenPanel.NONE -> Unit
        }

        KeyboardGrid(
            layout = layout,
            session = session,
            scope = scope,
            keyDispatcher = keyDispatcher,
            modifierLatch = modifierLatch,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/** The one-line control strip shown in full screen mode. */
@Composable
private fun FullscreenBar(
    state: SessionUiState,
    layout: KeyboardLayout,
    onTogglePanel: (FullscreenPanel) -> Unit,
    onLayoutPick: (KeyboardLayout) -> Unit,
    onExitFullscreen: () -> Unit,
) {
    var choosingLayout by remember { mutableStateOf(false) }
    val connected = state.connectedDevices

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (connected.isEmpty()) stringResource(R.string.keyboard_not_connected)
            else stringResource(R.string.keyboard_connected_to, connected.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = if (connected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else PassGreen,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onTogglePanel(FullscreenPanel.DEVICES) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(
                text = stringResource(R.string.keyboard_panel_devices),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            onClick = { choosingLayout = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(layout.displayName, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { onTogglePanel(FullscreenPanel.SETTINGS) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(
                text = stringResource(R.string.keyboard_panel_settings),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // The escape hatch stays visible rather than living in a menu: a full screen
        // mode the user cannot leave is a trap.
        Button(
            onClick = onExitFullscreen,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(
                text = stringResource(R.string.keyboard_exit_fullscreen),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (choosingLayout) {
        LayoutChoiceDialog(
            current = layout,
            onPick = {
                choosingLayout = false
                onLayoutPick(it)
            },
            onDismiss = { choosingLayout = false },
        )
    }
}

/** A compact panel holding one group of controls, shown above the keyboard. */
@Composable
private fun FullscreenPanelCard(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.keyboard_panel_close))
                }
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(session: HidSession) {
    val state by session.state.collectAsState()
    val (label, color) = when (state.phase) {
        SessionPhase.Idle -> "空闲（未注册）" to WarnAmber
        SessionPhase.PermissionMissing -> "缺少蓝牙权限" to FatalRed
        SessionPhase.BluetoothOff -> "蓝牙未开启" to FatalRed
        SessionPhase.ProfileConnecting -> "正在获取 HID 代理…" to WarnAmber
        SessionPhase.ProfileUnavailable -> "本机未开放 HID Device profile" to FatalRed
        SessionPhase.Registering -> "正在注册 HID 应用…" to WarnAmber
        SessionPhase.Registered -> "已注册为键盘" to PassGreen
        SessionPhase.Failed -> "注册失败（见日志）" to FatalRed
    }
    val connected = state.connectedDevices
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = color, fontWeight = FontWeight.SemiBold)
        Text(
            text = if (connected.isEmpty()) "未连接主机" else "已连接：${connected.joinToString()}",
            style = MaterialTheme.typography.bodySmall,
            color = if (connected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else PassGreen,
        )
    }
}

@Composable
private fun HostRow(
    session: HidSession,
    paired: List<PairedDevice>,
    connected: List<String>,
    onConnect: (PairedDevice) -> Unit,
) {
    var chooserOpen by remember { mutableStateOf(false) }

    // ONE line, not a list. Vertical space is the scarce resource on a landscape phone
    // (360dp total) and a permanent list of paired devices pushed the keyboard's bottom
    // row off screen. The chooser is a dialog, which costs no layout space until asked.
    val connectedNames = paired.filter { it.address in connected }.map { it.name }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (connectedNames.isEmpty()) {
                stringResource(R.string.keyboard_not_connected)
            } else {
                stringResource(R.string.keyboard_connected_to, connectedNames.joinToString())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (connectedNames.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else PassGreen,
        )
        Button(
            onClick = { chooserOpen = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text(
                text = if (connectedNames.isEmpty()) {
                    stringResource(R.string.keyboard_action_connect_device)
                } else {
                    stringResource(R.string.keyboard_action_switch_device)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (connectedNames.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    paired.filter { it.address in connected }.forEach { session.disconnect(it.device) }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(stringResource(R.string.keyboard_action_disconnect), style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(
            onClick = { session.refresh() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text(stringResource(R.string.keyboard_action_refresh), style = MaterialTheme.typography.bodySmall)
        }
        if (connectedNames.isEmpty() && paired.isEmpty()) {
            Text(
                text = stringResource(R.string.keyboard_pair_first),
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
            )
        }
    }

    if (chooserOpen) {
        AlertDialog(
            onDismissRequest = { chooserOpen = false },
            title = { Text(stringResource(R.string.keyboard_chooser_title)) },
            text = {
                if (paired.isEmpty()) {
                    Text(stringResource(R.string.keyboard_chooser_empty))
                } else {
                    Column {
                        paired.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (device.address in connected) {
                                    Text(
                                        text = stringResource(R.string.keyboard_chooser_connected),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PassGreen,
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            chooserOpen = false
                                            onConnect(device)
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Text(stringResource(R.string.keyboard_chooser_connect), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooserOpen = false }) { Text(stringResource(R.string.keyboard_chooser_close)) }
            },
        )
    }
}

private val PassGreen = Color(0xFF2E7D32)
private val FatalRed = Color(0xFFB3261E)
private val WarnAmber = Color(0xFFB26A00)
