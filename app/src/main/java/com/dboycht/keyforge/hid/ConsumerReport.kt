package com.dboycht.keyforge.hid

/**
 * A consumer-page input report (media keys): one 16-bit usage code.
 *
 * Value type like [HidReport]. Report ID 3 in `ProbeConstants.REPORT_DESCRIPTOR`;
 * the body is what `sendReport` receives - the platform prepends the ID byte.
 *
 * The descriptor declares a single 16-bit Data/Array field, so the usage code
 * itself is the report payload; "no key held" is the all-zero report. See
 * [ConsumerUsage] for the codes this app sends.
 */
internal class ConsumerReport private constructor(private val bytes: ByteArray) {

    /** The usage code carried by this report (0 = idle). */
    val code: Int
        get() = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)

    /** True when this is the "no key held" report. */
    val isIdle: Boolean get() = code == 0

    /** Length of a consumer report body. */
    val size: Int get() = bytes.size

    /** A copy of the raw report body, safe to hand to `sendReport`. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ConsumerReport && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ConsumerReport(code=0x%03X)".format(code)

    companion object {

        /** Total report body size in bytes. */
        const val REPORT_SIZE = 2

        /** The "no key held" report. */
        fun idle(): ConsumerReport = of(IDLE_CODE)

        /**
         * Builds a report for [code]. The descriptor declares a 10-bit logical
         * range (0..0x3FF), so anything outside is a caller bug and is rejected
         * instead of silently truncated into a different media key.
         */
        fun of(code: Int): ConsumerReport {
            require(code in 0..0x3FF) { "consumer usage must fit in 10 bits: 0x%X".format(code) }
            return ConsumerReport(
                byteArrayOf(
                    (code and 0xFF).toByte(),
                    ((code shr 8) and 0xFF).toByte(),
                )
            )
        }

        private const val IDLE_CODE = 0
    }
}

/**
 * Consumer-page usage codes this app sends (USB HID Usage Tables, page 0x0C).
 * Only codes with a real button in the UI are listed - a code without a button
 * would be dead weight and an untestable promise.
 */
internal object ConsumerUsage {
    const val MUTE = 0xE2
    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val PLAY_PAUSE = 0xCD
    const val SCAN_PREVIOUS_TRACK = 0xB6
    const val SCAN_NEXT_TRACK = 0xB5

    /** Human-readable name of a code, for on-screen diagnostics. */
    fun nameFor(code: Int): String = when (code) {
        MUTE -> "Mute"
        VOLUME_UP -> "Volume+"
        VOLUME_DOWN -> "Volume-"
        PLAY_PAUSE -> "Play/Pause"
        SCAN_PREVIOUS_TRACK -> "Prev"
        SCAN_NEXT_TRACK -> "Next"
        else -> "Unknown(0x%03X)".format(code)
    }
}
