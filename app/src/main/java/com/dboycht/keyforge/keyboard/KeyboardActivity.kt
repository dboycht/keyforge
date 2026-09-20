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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dboycht.keyforge.layout.Keyboards
import com.dboycht.keyforge.layout.KeyboardLayout
import com.dboycht.keyforge.session.HidSession
import com.dboycht.keyforge.session.HidSessionManager
import com.dboycht.keyforge.session.PairedDevice
import com.dboycht.keyforge.session.SessionPhase
import com.dboycht.keyforge.session.SessionResult
import com.dboycht.keyforge.settings.KeyboardSettings
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
        KeyboardService.start(this)
        ensureNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KeyboardScreen(session)
                }
            }
        }
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
private fun KeyboardScreen(session: HidSession) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // ONE worker, not a thread pool: auto-repeat queues a keystroke every ~60 ms, and
    // running those concurrently let several threads into the session's shared state
    // (that crashed the process - see ERROR.md E9). Serialising them also keeps
    // keystroke order, which is what typing means.
    val keyDispatcher = remember { Dispatchers.Default.limitedParallelism(1) }
    val state by session.state.collectAsState()
    val settings = remember { KeyboardSettings.get(context) }
    val modifierLatch by settings.modifierLatch.collectAsState()
    // Which layout is on screen. Switching is a data change: the renderer below is
    // the same for both.
    var layout by remember { mutableStateOf(Keyboards.PC_60) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        session.start()
        onDispose { /* Activity#onDestroy stops the session */ }
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
                    text = "键铸 · 键盘（最小验证）",
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
                    onPick = { picked ->
                        layout = picked
                        // A layout switch while keys are down would leave them stuck:
                        // release everything before the new grid appears.
                        run { session.releaseAll() }
                    },
                )

                Spacer(Modifier.height(4.dp))
                ModifierModeRow(
                    latch = modifierLatch,
                    onToggle = { enabled ->
                        settings.setModifierLatch(enabled)
                        // Switching modes must not leave a modifier stuck down.
                        run { session.releaseAll() }
                    },
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "会话事件（越靠下越新）",
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
            KeyboardView(
                layout = layout,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f),
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
                // the settings switch above turns the phone-style latch back on.
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
    }
}

@Composable
private fun ModifierModeRow(latch: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = "Shift/Ctrl 点一下保持", style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun LayoutPicker(current: KeyboardLayout, onPick: (KeyboardLayout) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "布局",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Keyboards.all.forEach { candidate ->
            FilterChip(
                selected = candidate.id == current.id,
                onClick = { onPick(candidate) },
                label = { Text(candidate.displayName) },
            )
        }
        Text(
            text = "（切换布局会先松开所有按键）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            text = if (connectedNames.isEmpty()) "未连接" else "已连接：${connectedNames.joinToString()}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (connectedNames.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else PassGreen,
        )
        Button(
            onClick = { chooserOpen = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text(if (connectedNames.isEmpty()) "连接设备" else "切换设备", style = MaterialTheme.typography.bodySmall)
        }
        if (connectedNames.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    paired.filter { it.address in connected }.forEach { session.disconnect(it.device) }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("断开", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(
            onClick = { session.refresh() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text("刷新", style = MaterialTheme.typography.bodySmall)
        }
        if (connectedNames.isEmpty() && paired.isEmpty()) {
            Text(
                text = "先在系统蓝牙里配对，再点“刷新”",
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
            )
        }
    }

    if (chooserOpen) {
        AlertDialog(
            onDismissRequest = { chooserOpen = false },
            title = { Text("选择要连接的设备") },
            text = {
                if (paired.isEmpty()) {
                    Text("没有已配对设备。请先在系统蓝牙里配对目标设备。")
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
                                        text = "已连接",
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
                                        Text("连接", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooserOpen = false }) { Text("关闭") }
            },
        )
    }
}

private val PassGreen = Color(0xFF2E7D32)
private val FatalRed = Color(0xFFB3261E)
private val WarnAmber = Color(0xFFB26A00)
