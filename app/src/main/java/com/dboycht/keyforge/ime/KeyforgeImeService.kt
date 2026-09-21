package com.dboycht.keyforge.ime

import android.view.View
import android.inputmethodservice.InputMethodService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dboycht.keyforge.keyboard.KeyboardService
import com.dboycht.keyforge.session.HidSessionManager
import com.dboycht.keyforge.session.SessionPhase
import com.dboycht.keyforge.text.TextForwarder
import com.dboycht.keyforge.text.TextSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The input-method flavour of KeyForge: type here, and the keystrokes go to the connected
 * host over Bluetooth HID instead of into this phone.
 *
 * Why this exists next to the on-screen keyboard screen: that screen is a full-screen
 * takeover, which suits "the host is at arm's length and I drive everything from the
 * phone". The input method suits the other case - the phone's own text editing
 * (suggestions, autocorrect, cursor movement, clipboard) stays available while the
 * *output* is the host.
 *
 * The hard honesty requirement from the project rules: **HID keyboards only carry key
 * scancodes**, so Chinese, emoji and other non-ASCII characters have none. They are
 * reported to the user rather than silently dropped or turned into wrong keys.
 */
class KeyforgeImeService : InputMethodService() {

    private val session by lazy { HidSessionManager.get(this) }
    private val sender by lazy { TextSender(session) }

    /**
     * The service is not lifecycle-aware, so it owns its scope. `SupervisorJob` because a
     * failed send must not tear down the scope and leave the IME unable to send again.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Guards the in-flight send so a second one cannot interleave with it. */
    private var sendJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // The IME shares the process-wide session, and that session must outlive both the
        // IME and the keyboard screen: the foreground service owns it.
        KeyboardService.start(this)
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        view.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val state by session.state.collectAsState()
                    ImeScreen(
                        connected = state.connectedDevices,
                        phase = state.phase,
                        send = { text, onResult -> startSend(text, onResult) },
                    )
                }
            }
        }
        return view
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Selecting the IME is exactly when the session should be live.
        session.start()
    }

    override fun onFinishInput() {
        // A pending send must not keep typing after the user left the field.
        sendJob?.cancel()
        sendJob = null
        session.releaseAll()
        super.onFinishInput()
    }

    override fun onDestroy() {
        sendJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSend(text: String, onResult: (TextSender.Outcome) -> Unit) {
        if (sendJob?.isActive == true) return
        sendJob = scope.launch {
            onResult(sender.send(text) { ms -> delay(ms) })
        }
    }
}

/**
 * The IME's own UI: one text field, one send button, and an honest report of what could
 * not be sent.
 *
 * Kept deliberately short in height - an input method shares the screen with the app the
 * user is actually typing into, and the system may give it very little room.
 */
@Composable
private fun ImeScreen(
    connected: List<String>,
    phase: SessionPhase,
    send: (String, (TextSender.Outcome) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<TextSender.Outcome?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    outcome = null
                },
                modifier = Modifier.weight(1f),
                label = { Text("要发送的文本（仅 ASCII 可发送）") },
                singleLine = true,
            )
            Button(
                onClick = {
                    val payload = text
                    if (payload.isNotEmpty()) {
                        send(payload) { result ->
                            outcome = result
                            // Clear only what actually went out, so the user can retry
                            // the rest instead of retyping the whole line.
                            if (result.failure == null && result.skipped.isEmpty()) text = ""
                        }
                    }
                },
            ) {
                Text("发送")
            }
        }

        val statusLine = when {
            connected.isNotEmpty() -> "已连接：${connected.joinToString()}" to ImePass
            phase == SessionPhase.Registered -> "已就绪，等待在键盘页连接主机" to ImeWarn
            else -> "会话未就绪（$phase）" to ImeWarn
        }
        Text(
            text = statusLine.first,
            color = statusLine.second,
            style = MaterialTheme.typography.bodySmall,
        )

        outcome?.let { result ->
            val reported = buildString {
                append("已发送 ${result.sent} 个字符")
                result.failure?.let { append("；失败：$it") }
            }
            Text(
                text = reported,
                color = if (result.failure == null) ImePass else ImeFail,
                style = MaterialTheme.typography.bodySmall,
            )
            if (result.skipped.isNotEmpty()) {
                // This is the honesty requirement made visible.
                Text(
                    text = "以下字符无法通过蓝牙键盘发送（HID 只有按键扫描码）：",
                    color = ImeFail,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = result.skipped.joinToString("、") { "${it.char}：${it.reason}" },
                    color = ImeFail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private val ImePass = Color(0xFF2E7D32)
private val ImeWarn = Color(0xFFB26A00)
private val ImeFail = Color(0xFFB3261E)
