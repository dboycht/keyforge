package com.dboycht.keyforge.probe

/**
 * Hard-coded identifiers used by the probe.
 *
 * Nothing here is user-visible branding beyond the SDP record fields, which are
 * displayed by the host when it asks the phone to pair as an input device.
 */
internal object ProbeConstants {

    /** SDP service name shown on the host (iPad / PC) during pairing. */
    const val SDP_NAME = "KeyForge"

    /** SDP service description shown on the host. */
    const val SDP_DESCRIPTION = "KeyForge Bluetooth keyboard"

    /** SDP provider name shown on the host. */
    const val SDP_PROVIDER = "KeyForge"

    /**
     * HID subclass 1 = boot interface / keyboard (SDP "HID 1.1.1").
     * Subclass 2 would be a mouse, 0 would be "no subclass / none".
     */
    const val SDP_SUBCLASS_KEYBOARD: Byte = 1

    /** What the Bluetooth stack calls us in its own logs. */
    const val TAG = "KeyForgeProbe"

    /**
     * Minimal boot-protocol keyboard report descriptor: 1 modifier byte,
     * 1 reserved byte, then 6 simultaneous key slots.
     *
     * This is the descriptor the finished app will ship as well; the probe
     * registers it so that `registerApp` exercises the same code path it will
     * later use for real.
     */
    val KEYBOARD_REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(), //   Usage Minimum (Left Control)
        0x29, 0xE7.toByte(), //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95.toByte(), 0x08, //   Report Count (8)
        0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute) -- modifiers
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81.toByte(), 0x01, //   Input (Constant) -- reserved byte
        0x95.toByte(), 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x65, //   Logical Maximum (101)
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0x65, //   Usage Maximum (101)
        0x81.toByte(), 0x00, //   Input (Data, Array) -- 6 key slots
        0x95.toByte(), 0x05, //   Report Count (5)
        0x75, 0x01, //   Report Size (1)
        0x05, 0x08, //   Usage Page (LEDs)
        0x19, 0x01, //   Usage Minimum (Num Lock)
        0x29, 0x05, //   Usage Maximum (Kana)
        0x91.toByte(), 0x02, //   Output (Data, Variable, Absolute) -- LEDs
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75, 0x03, //   Report Size (3)
        0x91.toByte(), 0x01, //   Output (Constant) -- LED padding
        0xC0.toByte(), // End Collection
    )
}
