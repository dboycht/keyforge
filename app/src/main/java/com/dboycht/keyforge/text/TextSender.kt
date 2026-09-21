package com.dboycht.keyforge.text

/**
 * Sends a [TextForwarder.ForwardPlan] through a [KeySink], one step at a time.
 *
 * Why this is separate from the input method service:
 * - the interesting rules (ordering, waiting between steps, deciding what counts as a
 *   failure) are plain logic, testable on the JVM with a fake [KeySink];
 * - the service only calls [send] and renders what comes back.
 *
 * The plan's steps already carry the ordering: a modifier is pressed before the key it
 * modifies and released after it, and [TextForwarder.Step.Pause] stops the host from
 * coalescing two events into one.
 */
internal class TextSender(private val sink: KeySink) {

    /** What happened while sending, for the UI to show. */
    internal data class Outcome(
        /** How many characters actually produced keystrokes. */
        val sent: Int,
        /** Characters a HID keyboard cannot represent, with the reason. */
        val skipped: List<TextForwarder.Skipped>,
        /** Non-null when the sink refused a step (wrong state, no host, ...). */
        val failure: String?,
        /** True when the plan was aborted part-way and everything was released. */
        val aborted: Boolean = false,
    ) {
        val allSent: Boolean get() = failure == null && skipped.isEmpty()
    }

    /**
     * Sends [text], character by character.
     *
     * Stops at the first refusal instead of pushing the rest blindly: if the host is gone,
     * continuing only spams the log, and if the state is wrong the user needs to hear it
     * rather than watch a half-typed sentence.
     *
     * @param sleep suspends for [TextForwarder.Step.Pause] durations.
     */
    suspend fun send(
        text: String,
        sleep: suspend (Long) -> Unit,
    ): Outcome = send(TextForwarder.plan(text), sleep)

    /**
     * Sends an already-built plan.
     *
     * Exists so the live-forwarding box can hand in a whole line's plan while a stream of single
     * characters keeps arriving, without rebuilding the plan or duplicating the abort handling.
     */
    suspend fun send(
        plan: TextForwarder.ForwardPlan,
        sleep: suspend (Long) -> Unit,
    ): Outcome {
        if (plan.steps.isEmpty()) {
            return Outcome(sent = 0, skipped = plan.skipped, failure = null)
        }

        for (step in plan.steps) {
            when (step) {
                is TextForwarder.Step.Pause -> sleep(step.ms)
                is TextForwarder.Step.Down -> if (!sink.press(step.usage)) return abort(plan, step.label)
                is TextForwarder.Step.Up -> if (!sink.release(step.usage)) return abort(plan, step.label)
            }
        }

        return Outcome(sent = plan.forwardedCount, skipped = plan.skipped, failure = null)
    }

    /**
     * Abandons the plan with **no key left down**: an aborted send that leaves Shift held
     * would silently turn the user's next typing into capitals, which is worse than the
     * original failure.
     */
    private fun abort(plan: TextForwarder.ForwardPlan, label: String): Outcome {
        sink.releaseSinkKeys()
        return Outcome(
            sent = 0,
            skipped = plan.skipped,
            failure = "发送中断（$label）：没有已连接的主机或会话未就绪",
            aborted = true,
        )
    }
}
