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
     * HID subclass for a combo keyboard + pointing device: AOSP's
     * `BluetoothHidDevice.SUBCLASS1_COMBO` (0xC0). Used together with the
     * composite descriptor below, which declares keyboard, mouse and consumer
     * (media key) reports.
     *
     * Historical note: before T7 the app shipped `SDP_SUBCLASS_KEYBOARD = 1`,
     * which is neither AOSP's `SUBCLASS1_KEYBOARD` (0x40) nor any documented
     * COD value - yet hosts accepted it and parsed the descriptor. If a host
     * refuses to pair with the combo subclass, falling back to `1` is a known
     * workable configuration for keyboard-only descriptors.
     */
    const val SDP_SUBCLASS_COMBO: Byte = 0xC0.toByte()

    /**
     * HID subclass historically shipped by this app ("boot interface /
     * keyboard", SDP "HID 1.1.1"). Kept as a pairing fallback for hosts that
     * reject the combo subclass; NOT used while the composite descriptor ships.
     */
    const val SDP_SUBCLASS_KEYBOARD: Byte = 1

    /** What the Bluetooth stack calls us in its own logs. */
    const val TAG = "KeyForgeProbe"

    /**
     * Composite report descriptor: keyboard + mouse + consumer (media keys) in
     * one device, addressed by Report ID (HID requires every report to carry an
     * ID as soon as one report does; ID 0 is reserved and never used).
     *
     * Report bodies (what `sendReport(device, id, data)` sends in `data` - the
     * platform prepends the Report ID byte on the wire, per AOSP docs):
     *
     * - Report ID 1, keyboard (8 bytes): modifier bitmap, reserved, 6 key slots,
     *   plus a LED output report (Num Lock..Kana) for host-driven LEDs.
     * - Report ID 2, mouse (4 bytes): button bitmap (bit0 left, bit1 right,
     *   bit2 middle), relative X, relative Y, relative wheel - each movement a
     *   relative int8 (-127..127).
     * - Report ID 3, consumer (2 bytes): one 16-bit usage code from the
     *   Consumer page, little endian (0 = "no key held"). See `ConsumerUsage`.
     *
     * This is the descriptor the finished app ships as well; the probe
     * registers it so that `registerApp` exercises the same code path it will
     * later use for real. `CompositeDescriptorTest` pins these bytes and
     * cross-checks the Report IDs against `HidSession`'s constants.
     */
    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        // ---- Report ID 1: keyboard ----
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), 0x01, //   Report ID (1) - keyboard
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
        0xC0.toByte(), // End Collection (Application, keyboard)
        // ---- Report ID 2: mouse ----
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x02, // Usage (Mouse)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), 0x02, //   Report ID (2) - mouse
        0x09, 0x01, //   Usage (Pointer)
        0xA1.toByte(), 0x00, //   Collection (Physical)
        0x05, 0x09, //     Usage Page (Buttons)
        0x19, 0x01, //     Usage Minimum (Button 1)
        0x29, 0x03, //     Usage Maximum (Button 3)
        0x15, 0x00, //     Logical Minimum (0)
        0x25, 0x01, //     Logical Maximum (1)
        0x75, 0x01, //     Report Size (1)
        0x95.toByte(), 0x03, //     Report Count (3)
        0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute) -- buttons
        0x95.toByte(), 0x01, //     Report Count (1)
        0x75, 0x05, //     Report Size (5)
        0x81.toByte(), 0x01, //     Input (Constant) -- button padding
        0x05, 0x01, //     Usage Page (Generic Desktop)
        0x09, 0x30, //     Usage (X)
        0x09, 0x31, //     Usage (Y)
        0x09, 0x38, //     Usage (Wheel)
        0x15, 0x81.toByte(), //     Logical Minimum (-127)
        0x25, 0x7F, //     Logical Maximum (127)
        0x75, 0x08, //     Report Size (8)
        0x95.toByte(), 0x03, //     Report Count (3)
        0x81.toByte(), 0x06, //     Input (Data, Variable, Relative) -- X, Y, wheel
        0xC0.toByte(), //   End Collection (Physical)
        0xC0.toByte(), // End Collection (Application, mouse)
        // ---- Report ID 3: consumer (media keys) ----
        0x05, 0x0C, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), 0x03, //   Report ID (3) - consumer
        0x19, 0x00, //   Usage Minimum (0)
        0x2A.toByte(), 0xFF.toByte(), 0x03, //   Usage Maximum (0x3FF)
        0x15, 0x00, //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x03, //   Logical Maximum (0x3FF)
        0x75, 0x10, //   Report Size (16)
        0x95.toByte(), 0x01, //   Report Count (1)
        0x81.toByte(), 0x00, //   Input (Data, Array) -- one 16-bit usage
        0xC0.toByte(), // End Collection (Application, consumer)
    )
}
