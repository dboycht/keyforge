package com.dboycht.keyforge.hid

/**
 * Tracked state of a keyboard session: which modifiers are held and which keys
 * occupy the six report slots.
 *
 * The important property is that every mutation returns the report that must be
 * sent to the host - callers never assemble bytes by hand, so the descriptor, the
 * report layout and the state can not drift apart.
 *
 * Rollover: a boot-protocol report only carries six simultaneous keys. When a
 * seventh key is pressed, the press is **dropped and reported** via
 * [KeyPressResult.dropped] instead of silently corrupting the report. Hosts do
 * have this limit; hiding it would look like "the keyboard randomly misses keys".
 */
internal class KeyboardState {

    private val modifiersHeld = LinkedHashSet<Int>()   // HID modifier usages (0xE0..0xE7)
    private val keysHeld = LinkedHashSet<Int>()        // non-modifier usage IDs, press order

    /** Result of a state mutation: the report to send plus what happened to the key. */
    data class KeyPressResult(
        val report: HidReport,
        val accepted: Boolean,
        val dropped: Boolean,
        val reason: String? = null,
    )

    /** Modifiers currently held, as HID modifier usage IDs. */
    val modifiers: List<Int> get() = modifiersHeld.toList()

    /** Non-modifier usage IDs currently held, in press order. */
    val keys: List<Int> get() = keysHeld.toList()

    /** True when nothing at all is held. */
    val isIdle: Boolean get() = modifiersHeld.isEmpty() && keysHeld.isEmpty()

    /** The report representing the current state. */
    fun report(): HidReport = HidReport.of(modifierBits(), keysHeld.toList())

    /**
     * Presses a HID usage ID.
     *
     * - modifier usages (0xE0..0xE7) only touch the modifier byte;
     * - a repeat press of a held key is accepted but leaves the state unchanged
     *   (hosts treat a duplicate slot as a no-op, and we want idempotence);
     * - a press beyond the six slots is rejected with `dropped = true`.
     */
    fun press(usage: Int): KeyPressResult {
        require(usage in 0..0xFF) { "usage ID out of range: $usage" }

        if (HidReport.Modifier.isModifierUsage(usage)) {
            val added = modifiersHeld.add(usage)
            return KeyPressResult(
                report = report(),
                accepted = true,
                dropped = false,
                reason = if (added) null else "modifier already held",
            )
        }

        if (keysHeld.contains(usage)) {
            return KeyPressResult(report(), accepted = true, dropped = false, reason = "key already held")
        }
        if (keysHeld.size >= HidReport.KEY_SLOTS) {
            return KeyPressResult(
                report = report(),
                accepted = false,
                dropped = true,
                reason = "rollover: ${HidReport.KEY_SLOTS} keys already held",
            )
        }
        keysHeld.add(usage)
        return KeyPressResult(report(), accepted = true, dropped = false)
    }

    /** Releases a HID usage ID; releasing something not held is a no-op. */
    fun release(usage: Int): KeyPressResult {
        val removed = if (HidReport.Modifier.isModifierUsage(usage)) {
            modifiersHeld.remove(usage)
        } else {
            keysHeld.remove(usage)
        }
        return KeyPressResult(
            report = report(),
            accepted = true,
            dropped = false,
            reason = if (removed) null else "was not held",
        )
    }

    /** Releases everything; the returned report is the "all keys up" one. */
    fun releaseAll(): HidReport {
        modifiersHeld.clear()
        keysHeld.clear()
        return report()
    }

    /**
     * A press/release pair for one key. Modifier presses are also lifted after the
     * pair, which is what "tap Shift" means when the caller did not hold it.
     *
     * Returned in send order: [0] = down report, [1] = up report.
     */
    fun tap(usage: Int): List<HidReport> {
        val down = press(usage)
        val up = release(usage)
        return listOf(down.report, up.report)
    }

    private fun modifierBits(): Int = modifiersHeld.fold(0) { acc, usage ->
        acc or HidReport.Modifier.bitForUsage(usage)
    }
}
