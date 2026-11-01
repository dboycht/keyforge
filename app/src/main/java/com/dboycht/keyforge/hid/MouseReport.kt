package com.dboycht.keyforge.hid

/**
 * A relative mouse input report: button bitmap, X/Y movement and wheel delta.
 *
 * Value type like [HidReport] - the state (which buttons are held) lives in
 * [MouseState], which produces new reports. Keeping the two apart is what makes
 * the whole path unit-testable without a Bluetooth stack.
 *
 * Report layout (Report ID 2 in `ProbeConstants.REPORT_DESCRIPTOR`; the body
 * below is what `sendReport` receives - the platform prepends the ID byte):
 *
 * ```
 * byte 0: button bitmap (see [Button])
 * byte 1: relative X (-127..127)
 * byte 2: relative Y (-127..127)
 * byte 3: relative wheel (-127..127, positive = away from user)
 * ```
 */
internal class MouseReport private constructor(private val bytes: ByteArray) {

    /** Length of a mouse report body. */
    val size: Int get() = bytes.size

    /** A copy of the raw report body, safe to hand to `sendReport`. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    /** Button bitmap of this report (see [Button]). */
    val buttons: Int get() = bytes[0].toInt() and 0xFF

    /** Relative X movement (int8, already sign-extended). */
    val x: Int get() = bytes[1].toInt()

    /** Relative Y movement (int8, already sign-extended). */
    val y: Int get() = bytes[2].toInt()

    /** Relative wheel movement (int8, already sign-extended). */
    val wheel: Int get() = bytes[3].toInt()

    /** True when nothing is pressed and nothing moves - the "idle" report. */
    val isIdle: Boolean get() = buttons == 0 && x == 0 && y == 0 && wheel == 0

    override fun equals(other: Any?): Boolean =
        other is MouseReport && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "MouseReport(buttons=0x%02X, x=%d, y=%d, wheel=%d)".format(buttons, x, y, wheel)

    companion object {

        /** Total report body size in bytes. */
        const val REPORT_SIZE = 4

        /** The "no buttons, no movement" report. */
        fun idle(): MouseReport = MouseReport(ByteArray(REPORT_SIZE))

        /**
         * Builds a mouse report. [buttons] must be a valid bitmap; movement
         * values are clamped into the int8 range the descriptor declares, so an
         * over-range delta (a caller bug, but a plausible one at flick speed)
         * saturates instead of wrapping into a malformed report.
         */
        fun of(buttons: Int, x: Int, y: Int, wheel: Int): MouseReport {
            require(buttons in 0..0xFF) { "button bitmap must fit in one byte: 0x%X".format(buttons) }
            return MouseReport(
                byteArrayOf(
                    buttons.toByte(),
                    int8(x),
                    int8(y),
                    int8(wheel),
                )
            )
        }

        /** Builds a report with only buttons held (no movement). */
        fun ofButtons(buttons: Int): MouseReport = of(buttons, 0, 0, 0)

        private fun int8(value: Int): Byte = value.coerceIn(-127, 127).toByte()
    }

    /**
     * The three mouse buttons of byte 0 (USB HID boot mouse convention:
     * bit 0 left, bit 1 right, bit 2 middle). Values are the bit positions.
     */
    object Button {
        const val LEFT = 0x01
        const val RIGHT = 0x02
        const val MIDDLE = 0x04

        /** True when the value is exactly one known button bit. */
        fun isValid(button: Int): Boolean =
            button == LEFT || button == RIGHT || button == MIDDLE

        /** Human-readable name of a button bit, for on-screen diagnostics. */
        fun nameFor(button: Int): String = when (button) {
            LEFT -> "Left"
            RIGHT -> "Right"
            MIDDLE -> "Middle"
            else -> "Unknown(0x%02X)".format(button)
        }
    }
}
