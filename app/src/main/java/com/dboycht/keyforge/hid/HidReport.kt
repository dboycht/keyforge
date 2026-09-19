package com.dboycht.keyforge.hid

/**
 * A boot-protocol keyboard input report: 1 modifier byte, 1 reserved byte, then
 * 6 key slots.
 *
 * This is a value type, not a state holder: the state machine lives in
 * [KeyboardState], which produces new reports. Keeping the two apart is what
 * makes the whole path unit-testable without a Bluetooth stack.
 *
 * Report layout (matches the descriptor in `ProbeConstants.KEYBOARD_REPORT_DESCRIPTOR`):
 *
 * ```
 * byte 0: modifier bitmap (see [Modifier])
 * byte 1: reserved (always 0)
 * byte 2..7: usage IDs of the keys currently held (0 = empty slot)
 * ```
 */
internal class HidReport private constructor(private val bytes: ByteArray) {

    /** Length of a boot-protocol keyboard report. */
    val size: Int get() = bytes.size

    /** A copy of the raw report, safe to hand to `sendReport`. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    /** Modifier bitmap of this report. */
    val modifiers: Int get() = bytes[0].toInt() and 0xFF

    /** Usage IDs held in this report, in slot order; empty slots are dropped. */
    val keys: List<Int>
        get() = (2 until bytes.size)
            .map { bytes[it].toInt() and 0xFF }
            .filter { it != 0 }

    /** True when nothing is pressed - the "all keys up" report. */
    val isIdle: Boolean get() = modifiers == 0 && keys.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is HidReport && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "HidReport(mod=0x%02X, keys=%s)".format(modifiers, keys.joinToString(",") { "0x%02X".format(it) })

    companion object {

        /** Number of key slots in a boot-protocol report. */
        const val KEY_SLOTS = 6

        /** Total report size in bytes. */
        const val REPORT_SIZE = 2 + KEY_SLOTS

        /** The "nothing pressed" report, sent to release everything. */
        fun idle(): HidReport = HidReport(ByteArray(REPORT_SIZE))

        /**
         * Builds a report. [modifiers] is masked to the 8 defined bits and
         * [keySlots] is truncated to [KEY_SLOTS] entries - an over-long press set
         * is a caller bug, but it must never produce a malformed report.
         */
        fun of(modifiers: Int, keySlots: List<Int>): HidReport {
            require(keySlots.all { it in 0..0xFF }) { "usage IDs must fit in one byte: $keySlots" }
            val bytes = ByteArray(REPORT_SIZE)
            bytes[0] = (modifiers and 0xFF).toByte()
            keySlots.take(KEY_SLOTS).forEachIndexed { index, usage ->
                bytes[2 + index] = (usage and 0xFF).toByte()
            }
            return HidReport(bytes)
        }

        /** Builds a report with a single key held down. */
        fun keyDown(modifiers: Int, usage: Int): HidReport = of(modifiers, listOf(usage))
    }

    /**
     * The eight modifier bits of byte 0 (USB HID Usage Tables, page 0x07,
     * usages 0xE0..0xE7). The values are the bit positions inside the byte.
     */
    object Modifier {
        const val LEFT_CTRL = 0x01
        const val LEFT_SHIFT = 0x02
        const val LEFT_ALT = 0x04
        const val LEFT_GUI = 0x08
        const val RIGHT_CTRL = 0x10
        const val RIGHT_SHIFT = 0x20
        const val RIGHT_ALT = 0x40
        const val RIGHT_GUI = 0x80

        /** True when the HID usage ID is one of the eight modifier usages. */
        fun isModifierUsage(usage: Int): Boolean = usage in 0xE0..0xE7

        /**
         * Modifier bit for a HID modifier usage ID (0xE0..0xE7), or 0 when the
         * usage is not a modifier.
         */
        fun bitForUsage(usage: Int): Int = when (usage) {
            0xE0 -> LEFT_CTRL
            0xE1 -> LEFT_SHIFT
            0xE2 -> LEFT_ALT
            0xE3 -> LEFT_GUI
            0xE4 -> RIGHT_CTRL
            0xE5 -> RIGHT_SHIFT
            0xE6 -> RIGHT_ALT
            0xE7 -> RIGHT_GUI
            else -> 0
        }

        /** HID modifier usage ID for a modifier bit, or `null` when the bit is not one. */
        fun usageForBit(bit: Int): Int? = when (bit) {
            LEFT_CTRL -> 0xE0
            LEFT_SHIFT -> 0xE1
            LEFT_ALT -> 0xE2
            LEFT_GUI -> 0xE3
            RIGHT_CTRL -> 0xE4
            RIGHT_SHIFT -> 0xE5
            RIGHT_ALT -> 0xE6
            RIGHT_GUI -> 0xE7
            else -> null
        }

        /** Human-readable name of a modifier bit, for on-screen diagnostics. */
        fun nameForBit(bit: Int): String = when (bit) {
            LEFT_CTRL -> "Left Ctrl"
            LEFT_SHIFT -> "Left Shift"
            LEFT_ALT -> "Left Alt"
            LEFT_GUI -> "Left Win"
            RIGHT_CTRL -> "Right Ctrl"
            RIGHT_SHIFT -> "Right Shift"
            RIGHT_ALT -> "Right Alt"
            RIGHT_GUI -> "Right Win"
            else -> "Unknown(0x%02X)".format(bit)
        }
    }
}
