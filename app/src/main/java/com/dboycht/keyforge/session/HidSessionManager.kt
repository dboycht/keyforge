package com.dboycht.keyforge.session

import android.content.Context

/**
 * Process-wide holder for the single [HidSession].
 *
 * Why a holder instead of "each screen builds its own": the platform allows only
 * ONE registered HID app at a time (see `ERROR.md` E3). Two sessions in one process
 * would fight for that slot, so the session must be a singleton, and the
 * foreground service - not an Activity - is what keeps it alive.
 *
 * The clock is injectable so tests can drive time without a device.
 */
internal object HidSessionManager {

    @Volatile
    private var instance: HidSession? = null

    /** The shared session, creating it on first use. */
    fun get(context: Context, onEvent: (String) -> Unit = {}): HidSession {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: HidSession(
                context = context.applicationContext,
                clock = AndroidSessionClock(),
                onEvent = onEvent,
            ).also { instance = it }
        }
    }

    /** The session if it was already created, else `null`. */
    fun peek(): HidSession? = instance

    /**
     * Drops the session (used when the user asks to fully tear down). Safe to call
     * when nothing exists.
     */
    fun release() {
        synchronized(this) {
            instance?.stop()
            instance = null
        }
    }
}
