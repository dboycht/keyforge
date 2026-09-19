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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    val state by session.state.collectAsState()
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
            val result = withContext(Dispatchers.Default) { action() }
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                // The keyboard plus the diagnostics do not fit a 1080p landscape
                // screen, so the whole page scrolls instead of clipping the grid.
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

            Spacer(Modifier.height(8.dp))
            HostRow(session, state.pairedDevices) { device -> run { session.connect(device.device) } }

            Spacer(Modifier.height(10.dp))
            LayoutPicker(
                current = layout,
                onPick = { picked ->
                    layout = picked
                    // A layout switch while keys are down would leave them stuck:
                    // release everything before the new grid appears.
                    run { session.releaseAll() }
                },
            )
            KeyboardView(
                layout = layout,
                // Fixed height on purpose: with `weight` the grid collapsed to zero
                // (the rows could not resolve a height inside this column). The
                // value is derived from the key unit x row count so both layouts fit.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KeyboardGridHeight),
                onKeyDown = { key ->
                    key.usage?.let { usage ->
                        scope.launch(Dispatchers.Default) { session.pressKey(key.keyCode, usage) }
                    }
                },
                onKeyUp = { key ->
                    key.usage?.let { usage ->
                        scope.launch(Dispatchers.Default) { session.releaseKey(key.keyCode, usage) }
                    }
                },
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "会话事件（越靠下越新）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(10.dp),
            ) {
                LazyColumn(modifier = Modifier.padding(10.dp)) {
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
    onConnect: (PairedDevice) -> Unit,
) {
    Column {
        Text(
            text = "已配对设备（点“连接”请手机主动建立 HID 通道）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (paired.isEmpty()) {
            Text(
                text = "没有已配对设备：先在系统蓝牙里与电脑配对，再回到本页点“刷新会话”。",
                style = MaterialTheme.typography.bodySmall,
                color = WarnAmber,
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 96.dp)) {
            items(paired) { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(220.dp),
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onConnect(device) }) { Text("连接") }
                    OutlinedButton(onClick = { session.disconnect(device.device) }) { Text("断开") }
                }
            }
        }
        OutlinedButton(onClick = { session.refresh() }) { Text("刷新会话") }
    }
}

private val PassGreen = Color(0xFF2E7D32)
private val FatalRed = Color(0xFFB3261E)
private val WarnAmber = Color(0xFFB26A00)
