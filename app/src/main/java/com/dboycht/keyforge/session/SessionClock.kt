package com.dboycht.keyforge.session

/**
 * Injectable time source.
 *
 * The send throttle and the reconnect backoff are the two pieces of session logic
 * that would otherwise need `Thread.sleep` in tests. Taking the clock as a
 * parameter keeps both testable on the JVM, with no device and no waiting.
 */
internal interface SessionClock {
    /** Milliseconds since an arbitrary origin; only differences are meaningful. */
    fun nowMs(): Long

    /** Schedules [action] to run after [delayMs]; returns a handle for cancellation. */
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

/** Handle for a scheduled action. A `fun interface` so tests can pass a lambda. */
internal fun interface Cancellable {
    fun cancel()
}

/** Production clock: system uptime and the main-thread handler. */
internal class AndroidSessionClock(
    private val handler: android.os.Handler = android.os.Handler(android.os.Looper.getMainLooper()),
) : SessionClock {

    override fun nowMs(): Long = android.os.SystemClock.uptimeMillis()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/**
 * Enforces a minimum gap between HID reports.
 *
 * Why it exists: a report describes the *whole* keyboard state ("Shift + A are
 * down"), so two reports sent back to back can be coalesced or reordered by the
 * stack, and the host then sees a different keystroke than intended. A small gap
 * (default 20 ms, comfortably inside the 8-15 ms nag in the field reports) makes
 * the down-then-up sequence an ordered pair.
 *
 * Deliberately time-based rather than sleep-based: [waitMs] tells the caller how
 * long to wait, so tests can assert the schedule without actually waiting.
 */
internal class ReportThrottle(
    private val clock: SessionClock,
    private val minGapMs: Long = DEFAULT_MIN_GAP_MS,
) {
    private var lastSentMs: Long = Long.MIN_VALUE

    /** Milliseconds still to wait before the next send; 0 when it may go now. */
    fun waitMs(): Long {
        if (lastSentMs == Long.MIN_VALUE) return 0
        val elapsed = clock.nowMs() - lastSentMs
        return if (elapsed >= minGapMs) 0 else minGapMs - elapsed
    }

    /** True when a report may be sent right now. */
    fun canSendNow(): Boolean = waitMs() == 0L

    /** Records that a report was sent at the current time. */
    fun markSent(): Long {
        lastSentMs = clock.nowMs()
        return lastSentMs
    }

    /** Forgets history (e.g. after a reconnect). */
    fun reset() {
        lastSentMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_MIN_GAP_MS = 20L
    }
}
