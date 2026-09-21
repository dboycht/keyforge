package com.dboycht.keyforge.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dboycht.keyforge.R
import com.dboycht.keyforge.layout.KeyKind
import com.dboycht.keyforge.layout.Keyboards
import com.dboycht.keyforge.layout.KeyboardLayout
import com.dboycht.keyforge.session.HidSession
import com.dboycht.keyforge.session.HidSessionManager
import com.dboycht.keyforge.session.PairedDevice
import com.dboycht.keyforge.session.SessionPhase
import com.dboycht.keyforge.session.SessionResult
import com.dboycht.keyforge.session.SessionUiState
import com.dboycht.keyforge.settings.KeyboardSettings
import com.dboycht.keyforge.text.TextDiff
import com.dboycht.keyforge.text.TextForwarder
import com.dboycht.keyforge.text.TextSender
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // Text forwarding reuses the same plan→sink machinery as the input method, so both paths share
    // one implementation of "modifiers, pauses, and what counts as a failure".
    val textSender = remember(session) { TextSender(session) }
    val state by session.state.collectAsState()
    val modifierLatch by settings.modifierLatch.collectAsState()
    val fullscreenEnabled by settings.fullscreen.collectAsState()
    // Which layout is on screen. A saved choice always wins; when the user has never chosen,
    // a narrow window gets the 10-unit phone layout instead of the 15-unit PC one.
    //
    // Measured in portrait on a 360dp-wide phone: 15 unit columns give a 24dp key, smaller
    // than a fingertip, while 10 units give 36dp. A keyboard that is complete but unusable is
    // worse than one with fewer keys, and the full layout is still one pick away.
    val widthDp = LocalConfiguration.current.screenWidthDp
    var layout by remember { mutableStateOf(Keyboards.byId(settings.layoutId.value)) }
    LaunchedEffect(widthDp, settings.layoutId.value) {
        // Only when the choice is unset; otherwise the user's pick would be overwritten on
        // every rotation.
        if (settings.layoutId.value == null) {
            layout = Keyboards.defaultFor(wide = widthDp >= NARROW_WIDTH_DP)
        }
    }
    var lastResult by remember { mutableStateOf<String?>(null) }
    // Which overlay is open in full screen. One at a time, and it closes on selection
    // so the user always ends up back at the keyboard.
    var panel by remember { mutableStateOf(FullscreenPanel.NONE) }
    // Set when the user asks for text forwarding: raises the system keyboard, which hides our grid
    // and shows the forwarding box.
    var textInputRequested by remember { mutableStateOf(false) }

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
                textInputRequested = textInputRequested,
                onTextInputRaised = { textInputRequested = false },
                onRequestTextInput = { textInputRequested = true },
                onForwardText = { inserted, done ->
                    scope.launch(keyDispatcher) {
                        done(textSender.send(TextForwarder.plan(inserted)) { delay(it) })
                    }
                },
                onBackspace = { count, done ->
                    scope.launch(keyDispatcher) {
                        repeat(count) { session.tapKey(KeyEvent.KEYCODE_DEL, DELETION_USAGE) }
                        done(null)
                    }
                },
                run = ::run,
            )
            return@Scaffold
        }
        // Normal (non full screen) layout: connection + settings only, NO keyboard.
        //
        // Requested by the user, and it resolves the whole space fight this screen kept
        // losing: squeezed between the diagnostics and a shared window, the keys came out
        // too short to type on (and repeatedly ended up clipped or sitting on a band of
        // empty background). The keyboard now has exactly one home - full screen - where it
        // gets the entire window, and this page is free to be a normal settings page.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DiagnosticsPanel(
                session = session,
                state = state,
                layout = layout,
                lastResult = lastResult,
                modifierLatch = modifierLatch,
                fullscreenEnabled = fullscreenEnabled,
                settings = settings,
                onLayoutPick = { switchLayout(it) },
                onReleaseAll = { run { session.releaseAll() } },
                // Text forwarding: send what was just typed, or backspace what was just deleted.
                // Each call runs on the same single-worker dispatcher as the keyboard, so the
                // characters reach the host in the order they were typed.
                onForwardText = { inserted, done ->
                    scope.launch(keyDispatcher) {
                        val outcome = textSender.send(TextForwarder.plan(inserted)) { delay(it) }
                        done(outcome)
                    }
                },
                onBackspace = { count, done ->
                    scope.launch(keyDispatcher) {
                        repeat(count) { session.tapKey(KeyEvent.KEYCODE_DEL, DELETION_USAGE) }
                        done(null)
                    }
                },
            )
        }
    }
}

/**
 * Everything above the keyboard in the normal layout: the connection bar, the layout
 * picker, the settings and the event log.
 *
 * Extracted because this band is the part that keeps going wrong on device (it has been
 * clipped by the screen edge, had its device list squeezed to nothing, and been covered by
 * the keys), and because it keeps the screen function from growing another level of
 * nesting.
 */
@Composable
private fun DiagnosticsPanel(
    session: HidSession,
    state: SessionUiState,
    layout: KeyboardLayout,
    lastResult: String?,
    modifierLatch: Boolean,
    fullscreenEnabled: Boolean,
    settings: KeyboardSettings,
    onLayoutPick: (KeyboardLayout) -> Unit,
    onReleaseAll: () -> Unit,
    onForwardText: (String, (TextSender.Outcome) -> Unit) -> Unit,
    onBackspace: (Int, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
            session = session,            paired = state.pairedDevices,
            connected = state.connectedDevices,
        ) { device -> session.connect(device.device) }

        Spacer(Modifier.height(6.dp))
        LayoutPicker(current = layout, onPick = onLayoutPick)

        Spacer(Modifier.height(4.dp))
        ModifierModeRow(
            latch = modifierLatch,
            onToggle = {
                settings.setModifierLatch(it)
                // Switching modes must not leave a modifier stuck down.
                onReleaseAll()
            },
            fullscreen = fullscreenEnabled,
            onFullscreenToggle = { settings.setFullscreen(it) },
        )

        Spacer(Modifier.height(6.dp))
        // Text forwarding sits above the log: it is the feature you interact with, the log is only
        // there when something goes wrong.
        Text(
            text = stringResource(R.string.forward_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        TextForwardingBox(
            state = state,
            onForward = onForwardText,
            onBackspace = onBackspace,
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
}

/** Which overlay is open in full screen mode. */
private enum class FullscreenPanel { NONE, DEVICES, SETTINGS }

/**
 * Below this width (dp) the app prefers a layout with fewer, wider keys.
 *
 * A phone in portrait is about 360dp wide: split into the 15 columns of the PC layout that is
 * a 24dp key, which is smaller than a fingertip.
 */
private const val NARROW_WIDTH_DP = 600

/** HID usage for Backspace (the key slot that deletes one character to the left). */
private const val DELETION_USAGE = 0x2A

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
        Text(
            text = stringResource(R.string.keyboard_section_settings),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Switch on one line, explanation on the next.
        //
        // Measured in portrait: label + switch + hint on a single line forced the hint to wrap
        // into three short lines ("（打开后隐藏系统栏、键盘 / 占满屏幕；键盘只在全屏下 / 显示）"),
        // which reads badly and made the rows tall. Splitting them keeps each row one line of
        // label plus one line of explanation in both orientations.
        SettingRow(
            label = stringResource(R.string.keyboard_fullscreen),
            hint = stringResource(R.string.keyboard_fullscreen_hint),
            checked = fullscreen,
            onCheckedChange = onFullscreenToggle,
        )
        SettingRow(
            label = stringResource(R.string.keyboard_modifier_latch),
            hint = if (latch) {
                "粘滞：点一下亮、再点一下灭"
            } else {
                "默认同电脑键盘：按住生效、松手释放"
            },
            checked = latch,
            onCheckedChange = onToggle,
        )
    }
}

/** A labelled switch with its explanation on the line below. */
@Composable
private fun SettingRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    LayoutChoiceEntry(
                        layout = candidate,
                        selected = candidate.id == current.id,
                        onPick = { onPick(candidate) },
                    )
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
 * One layout in the chooser: a picture of it, its name, and how to pick it.
 *
 * The picture matters. Reported by the user: choosing from names alone gave no idea what the
 * layout would look like, so picking one was a guess. The thumbnail is rendered from the very
 * same [KeyboardLayout] data the keyboard itself uses, so it cannot drift from reality - it is
 * not a screenshot that has to be kept up to date.
 *
 * The proportions are real (key widths and row count come from the data); only the size is
 * scaled down, and the labels are omitted because they would be unreadable at this size.
 */
@Composable
private fun LayoutChoiceEntry(
    layout: KeyboardLayout,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayoutThumbnail(layout = layout, modifier = Modifier.width(118.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = layout.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) PassGreen else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.keyboard_layout_size, layout.widthUnits, layout.rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (selected) {
            Text(
                text = stringResource(R.string.keyboard_chooser_connected),
                style = MaterialTheme.typography.bodySmall,
                color = PassGreen,
            )
        } else {
            Button(
                onClick = onPick,
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

/**
 * A miniature of a layout: one thin bar per key, with the widths taken from the layout data.
 *
 * Colours only distinguish the three key kinds, so the shape of the layout - how many columns,
 * where the wide keys are - is what the eye reads.
 */
@Composable
private fun LayoutThumbnail(layout: KeyboardLayout, modifier: Modifier = Modifier) {
    val unit = 8.dp
    val gap = 1.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        layout.rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(unit),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                // The thumbnail must stagger exactly like the keyboard does, or the preview of the
                // staggered layout would show the aligned grid the user complained about - and a
                // preview that lies is worse than no preview.
                val leader = layout.offsetFor(rowIndex)
                if (leader > 0f) {
                    Spacer(modifier = Modifier.weight(leader))
                }
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(key.widthUnits.coerceAtLeast(0.1f))
                            .fillMaxHeight()
                            .background(
                                color = when (key.kind) {
                                    KeyKind.MODIFIER -> ThumbModifier
                                    KeyKind.ACTION -> ThumbAction
                                    // A gap in a split layout: show it as empty space.
                                    KeyKind.SPACER -> Color.Transparent
                                    KeyKind.NORMAL -> ThumbNormal
                                },
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
    }
}

/** Thumbnail colours: readable against the dark dialog, and distinct per key kind. */
private val ThumbNormal = Color(0xFFBBBCC4)
private val ThumbModifier = Color(0xFF7E808C)
private val ThumbAction = Color(0xFF9A9CAA)

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
    textInputRequested: Boolean,
    onTextInputRaised: () -> Unit,
    onRequestTextInput: () -> Unit,
    onForwardText: (String, (TextSender.Outcome) -> Unit) -> Unit,
    onBackspace: (Int, (String?) -> Unit) -> Unit,
    run: (suspend () -> SessionResult) -> Unit,
) {
    // Hide our own keyboard while the system input method is up.
    //
    // This is the conflict the forwarding box creates: to type with the user's own IME, the system
    // keyboard must appear - and our full screen keyboard would sit underneath it, hiding the very
    // text field being typed into. Hiding ours leaves exactly one keyboard on screen at a time.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
            onRequestTextInput = onRequestTextInput,
        )

        // Invisible: it exists only to raise the system keyboard when asked.
        SystemKeyboardOpener(requested = textInputRequested, onRaised = onTextInputRaised)

        // Typing with your own keyboard, forwarded to the host as you type. Only shown when the
        // system keyboard is up: this panel appears above our grid, so it belongs to that mode.
        if (imeVisible) {
            TextForwardingBox(
                state = state,
                onForward = onForwardText,
                onBackspace = onBackspace,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

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

        // Our grid hides itself while the system keyboard is up: at that moment the user is typing
        // into the forwarding box with their own IME, and two keyboards on screen at once would
        // bury the text field this mode exists to show.
        if (!imeVisible) {
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
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
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
    onRequestTextInput: () -> Unit,
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
        // The way into text forwarding: raising the system keyboard hides our grid and shows the
        // forwarding box, so the user can type with their own IME.
        OutlinedButton(
            onClick = onRequestTextInput,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(
                text = stringResource(R.string.forward_section_short),
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

/**
 * The text-forwarding box: type here with **your own keyboard**, and the characters go to the host
 * as you type.
 *
 * This is the "forward text" mode the user asked for ("上面一个文本框，我进行输入，那边自动打出文字").
 * It deliberately does **not** use the app's own input method: the user wants their familiar IME
 * (pinyin candidates, autocorrect), and this box just relays what that IME commits.
 *
 * What it can and cannot do is a hardware fact, not a limitation of this code: a HID keyboard sends
 * key **scancodes**, so only characters that exist on a keyboard can travel. Chinese characters have
 * no scancode - when a pinyin IME commits 你好, the host can only receive the letters the user typed.
 * Refused characters are therefore listed with the reason, never silently dropped.
 */
@Composable
private fun TextForwardingBox(
    state: SessionUiState,
    onForward: (String, (TextSender.Outcome) -> Unit) -> Unit,
    onBackspace: (Int, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Messages are resolved here, in composable scope, and handed to the plain functions below:
    // stringResource cannot be called from a non-composable helper.
    val sentTemplate = stringResource(R.string.forward_sent)
    val failedTemplate = stringResource(R.string.forward_failed)
    val skippedHeader = stringResource(R.string.forward_skipped_header)
    val resyncMessage = stringResource(R.string.forward_resync)

    var text by remember { mutableStateOf("") }
    var autoSend by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<String?>(null) }
    // The last text we know the host has seen. Updated only after a send returns, so a refused
    // character is reported rather than being silently skipped.
    var syncedText by remember { mutableStateOf("") }

    fun onTextChanged(next: String) {
        if (!autoSend) {
            text = next
            syncedText = next
            return
        }
        val change = TextDiff.between(syncedText, next)
        text = next

        when {
            change.isNoop -> Unit
            change.needsResync -> {
                // The caret jumped or the field was replaced: we cannot reproduce that as typing,
                // so re-anchor instead of sending keystrokes that do not match what the user sees.
                syncedText = next
                report = resyncMessage
            }
            change.isBackspace -> onBackspace(change.deletedFromEnd) { failure ->
                syncedText = next
                report = failure
            }
            else -> onForward(change.inserted) { outcome ->
                syncedText = next
                report = describeOutcome(outcome, sentTemplate, failedTemplate, skippedHeader)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { onTextChanged(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.forward_field_label)) },
            minLines = 2,
            maxLines = 4,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.forward_auto_send),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = autoSend, onCheckedChange = { autoSend = it })
            Text(
                text = stringResource(
                    if (state.connectedDevices.isEmpty()) R.string.forward_no_host
                    else R.string.forward_connected,
                    state.connectedDevices.joinToString().ifEmpty { "-" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.connectedDevices.isEmpty()) WarnAmber else PassGreen,
                modifier = Modifier.weight(1f),
            )
            // A compact button, not a TextButton: the label was wrapping onto two lines in a
            // narrow portrait window, which made the row taller for no reason.
            OutlinedButton(
                onClick = {
                    text = ""
                    syncedText = ""
                    report = null
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(
                    text = stringResource(R.string.forward_clear),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        report?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (line.startsWith("已发送")) PassGreen else FatalRed,
            )
        }
        Text(
            text = stringResource(R.string.forward_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Turns a send result into the one line the user reads.
 *
 * The honesty rule from the project: a character a HID keyboard cannot carry is **named**, with its
 * reason, rather than dropped or turned into a wrong key.
 */
private fun describeOutcome(
    outcome: TextSender.Outcome,
    sentTemplate: String,
    failedTemplate: String,
    skippedHeader: String,
): String = buildString {
    append(sentTemplate.format(outcome.sent))
    outcome.failure?.let { append("；").append(failedTemplate.format(it)) }
    if (outcome.skipped.isNotEmpty()) {
        append("；").append(skippedHeader).append(' ')
        append(outcome.skipped.joinToString("、") { "${it.char}：${it.reason}" })
    }
}

/**
 * An invisible focusable field whose only job is to **summon the system keyboard**.
 *
 * Why it is needed: in full screen mode our own keyboard is on screen, so nothing would ever ask
 * the system for its input method - and the forwarding box needs the user's own IME (pinyin,
 * autocorrect) to type with. Focusing this field raises that IME, and once it is up our grid hides
 * itself (see the `imeVisible` handling in [FullscreenLayout]).
 *
 * The field itself holds no text: the visible text lives in the forwarding box, which is what
 * actually forwards characters. One field owning the text keeps the two in step.
 */
@Composable
private fun SystemKeyboardOpener(requested: Boolean, onRaised: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .focusable(),
    )

    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboard?.show()
        onRaised()
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

    // One wrapping row, not a fixed-height Row.
    //
    // Vertical space is the scarce resource in landscape (360dp), so this started as a single
    // 40dp line. Measured in portrait: the status text plus three controls do not fit the
    // narrow width, and a fixed-height Row either clips them or squashes the labels (the
    // probe screen's buttons rendered as a vertical column of characters the same way).
    // FlowRow wraps, and the height follows the content.
    val connectedNames = paired.filter { it.address in connected }.map { it.name }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
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
