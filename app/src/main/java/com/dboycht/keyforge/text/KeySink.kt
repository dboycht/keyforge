package com.dboycht.keyforge.text

/**
 * The slice of the HID layer that text forwarding needs.
 *
 * Narrow on purpose: [TextSender] only presses and releases key usages, so depending on
 * this instead of the whole session keeps the forwarding rules (ordering, modifiers, what
 * counts as a failure) unit testable on the JVM with a fake sink.
 *
 * Implementations must be safe to call from a single sender coroutine; the real session
 * serialises them internally.
 */
internal interface KeySink {

    /** Presses [usageCode] and keeps it down. */
    fun press(usageCode: Int): Boolean

    /** Releases [usageCode]. */
    fun release(usageCode: Int): Boolean

    /**
     * Releases everything currently down - used when aborting mid-plan.
     *
     * Named `releaseSinkKeys` rather than `releaseAll` so the real session's own
     * `releaseAll()` (which returns a result) does not collide with this one.
     */
    fun releaseSinkKeys()
}
