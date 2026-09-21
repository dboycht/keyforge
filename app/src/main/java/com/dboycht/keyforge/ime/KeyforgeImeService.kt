package com.dboycht.keyforge.ime

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dboycht.keyforge.keyboard.KeyboardService
import com.dboycht.keyforge.session.HidSessionManager
import com.dboycht.keyforge.session.SessionPhase
import com.dboycht.keyforge.text.TextSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The input-method flavour of KeyForge: type here, and the keystrokes go to the connected host
 * over Bluetooth HID instead of into this phone.
 *
 * Why an input method exists next to the on-screen keyboard screen: that screen is a
 * full-screen takeover, which suits "the host is at arm's length and I drive everything from
 * the phone". The input method suits the other case - the phone's own text editing
 * (suggestions, autocorrect, cursor movement, clipboard) stays available while the *output* is
 * the host.
 *
 * **Why the input view is built from classic views instead of Compose.** A `ComposeView`
 * inside an `InputMethodService` crashes when the platform attaches it, because Compose
 * resolves its recomposer from the *window's* lifecycle owner and an input method's window is
 * built by the platform (measured: `IllegalStateException: ViewTreeLifecycleOwner not found
 * from ... android:id/parentPanel`, on `ComposeView.onAttachedToWindow`). The crash also takes
 * the app being typed into down with it. One text field and one button do not justify fighting
 * that, so this view is plain Android while the rest of the app stays Compose.
 *
 * The hard honesty requirement from the project rules: **HID keyboards only carry key
 * scancodes**, so Chinese, emoji and other non-ASCII characters have none. They are reported
 * to the user rather than silently dropped or turned into wrong keys.
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

    /** Views, kept so results and status can be updated after the send completes. */
    private var inputField: EditText? = null
    private var statusView: TextView? = null
    private var resultView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        // The IME shares the process-wide session, and that session must outlive both the IME
        // and the keyboard screen: the foreground service owns it.
        KeyboardService.start(this)
    }

    override fun onCreateInputView(): View {
        val field = EditText(this).apply {
            hint = getString(com.dboycht.keyforge.R.string.ime_field_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val send = Button(this).apply {
            text = getString(com.dboycht.keyforge.R.string.ime_action_send)
            setOnClickListener { sendTypedText() }
        }
        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }
        val result = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            setPadding(16, 8, 16, 8)
            addView(row)
            addView(status)
            // The skip report can be several lines; keep it scrollable and bounded so the
            // input method never grows tall enough to bury the app being typed into.
            addView(
                ScrollView(this@KeyforgeImeService).apply {
                    addView(result)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                },
            )
        }

        inputField = field
        statusView = status
        resultView = result
        refreshStatus()
        return column
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Selecting the IME is exactly when the session should be live.
        session.start()
        refreshStatus()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshStatus()
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

    private fun sendTypedText() {
        val payload = inputField?.text?.toString().orEmpty()
        if (payload.isEmpty()) return

        // Disable the button while a send is running: two interleaved sends would produce
        // interleaved keystrokes, and the session serialises reports but not character order.
        val field = inputField
        if (sendJob?.isActive == true) return
        setSending(true)

        sendJob = scope.launch {
            val outcome = sender.send(payload) { ms -> delay(ms) }
            // Clear only what actually went out, so the user can retry the rest instead of
            // retyping the whole line.
            if (outcome.failure == null && outcome.skipped.isEmpty()) field?.setText("")
            showOutcome(outcome)
            setSending(false)
        }
    }

    private fun setSending(sending: Boolean) {
        inputField?.isEnabled = !sending
        refreshStatus(sending = sending)
    }

    private fun showOutcome(outcome: TextSender.Outcome) {
        val reported = buildString {
            append(getString(com.dboycht.keyforge.R.string.ime_sent_count, outcome.sent))
            outcome.failure?.let { append("；").append(it) }
        }
        val skipped = outcome.skipped
        resultView?.text = if (skipped.isEmpty()) {
            reported
        } else {
            // This is the honesty requirement made visible: name every character that cannot
            // travel over a HID keyboard, and say why.
            buildString {
                appendLine(reported)
                appendLine(getString(com.dboycht.keyforge.R.string.ime_skipped_header))
                skipped.forEach { appendLine("${it.char}：${it.reason}") }
            }
        }
        resultView?.setTextColor(if (outcome.failure == null) Color.parseColor("#81C784") else Color.parseColor("#EF9A9A"))
    }

    private fun refreshStatus(sending: Boolean = false) {
        val state = session.state.value
        val text = when {
            sending -> getString(com.dboycht.keyforge.R.string.ime_status_sending)
            state.connectedDevices.isNotEmpty() ->
                getString(com.dboycht.keyforge.R.string.keyboard_connected_to, state.connectedDevices.joinToString())
            state.phase == SessionPhase.Registered ->
                getString(com.dboycht.keyforge.R.string.ime_status_ready)
            else -> getString(com.dboycht.keyforge.R.string.ime_status_not_ready, state.phase.name)
        }
        statusView?.text = text
        statusView?.setTextColor(
            if (state.connectedDevices.isNotEmpty()) Color.parseColor("#81C784") else Color.parseColor("#FFB74D"),
        )
    }
}
