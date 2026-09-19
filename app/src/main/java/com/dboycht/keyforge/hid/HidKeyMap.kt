package com.dboycht.keyforge.hid

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent] key codes to USB HID keyboard usage IDs.
 *
 * Why a table instead of a formula: `KeyEvent` codes are the Linux input-event
 * codes (KEY_*), while the HID report carries USB HID Usage IDs (Usage Page 0x07).
 * The two sets overlap in places and diverge in others (e.g. Linux KEY_DELETE is
 * 111 while HID Delete/Forward is 0x4C), so the mapping has to be explicit.
 *
 * Only the keys a keyboard layout can actually reach are listed; anything absent
 * maps to `null`, which callers must surface to the user instead of silently
 * dropping (see README: non-ASCII text has no HID representation at all).
 *
 * Sources for the usage IDs: USB HID Usage Tables 1.12, section 10 (Keyboard/
 * Keypad page 0x07) - https://usb.org/sites/default/files/hut1_12.pdf
 */
internal object HidKeyMap {

    /** Usage page 0x07 (Keyboard/Keypad) codes, by conventional name. */
    private const val A = 0x04
    private const val Z = 0x1D
    private const val ONE = 0x1E
    private const val NINE = 0x26
    private const val ZERO = 0x27

    /** `KeyEvent` code -> HID usage ID. Built once; the map is immutable. */
    private val BY_KEY_CODE: Map<Int, Int> = buildMap {
        // Letters: HID 0x04..0x1D follow the alphabet in order.
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, A + i)

        // Digits: HID 0x1E..0x26 are '1'..'9', 0x27 is '0'.
        for (i in 0..8) put(KeyEvent.KEYCODE_1 + i, ONE + i)
        put(KeyEvent.KEYCODE_0, ZERO)

        // Whitespace and editing.
        put(KeyEvent.KEYCODE_ENTER, 0x28)
        put(KeyEvent.KEYCODE_ESCAPE, 0x29)
        put(KeyEvent.KEYCODE_DEL, 0x2A)          // Backspace
        put(KeyEvent.KEYCODE_TAB, 0x2B)
        put(KeyEvent.KEYCODE_SPACE, 0x2C)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        put(KeyEvent.KEYCODE_INSERT, 0x49)

        // Punctuation and symbols (US layout positions; the host's own layout
        // decides the resulting character).
        put(KeyEvent.KEYCODE_MINUS, 0x2D)
        put(KeyEvent.KEYCODE_EQUALS, 0x2E)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31)
        put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34)
        put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36)
        put(KeyEvent.KEYCODE_PERIOD, 0x37)
        put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_NUM_LOCK, 0x53)

        // Function row.
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 0x3A + i)
        put(KeyEvent.KEYCODE_SYSRQ, 0x46)        // Print Screen
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 0x47)
        put(KeyEvent.KEYCODE_BREAK, 0x48)        // Pause

        // Navigation.
        put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A)
        put(KeyEvent.KEYCODE_PAGE_UP, 0x4B)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C)  // Delete (forward)
        put(KeyEvent.KEYCODE_MOVE_END, 0x4D)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x50)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x51)
        put(KeyEvent.KEYCODE_DPAD_UP, 0x52)

        // Keypad.
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x54)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x55)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x56)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 0x57)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58)
        put(KeyEvent.KEYCODE_NUMPAD_1, 0x59)
        put(KeyEvent.KEYCODE_NUMPAD_2, 0x5A)
        put(KeyEvent.KEYCODE_NUMPAD_3, 0x5B)
        put(KeyEvent.KEYCODE_NUMPAD_4, 0x5C)
        put(KeyEvent.KEYCODE_NUMPAD_5, 0x5D)
        put(KeyEvent.KEYCODE_NUMPAD_6, 0x5E)
        put(KeyEvent.KEYCODE_NUMPAD_7, 0x5F)
        put(KeyEvent.KEYCODE_NUMPAD_8, 0x60)
        put(KeyEvent.KEYCODE_NUMPAD_9, 0x61)
        put(KeyEvent.KEYCODE_NUMPAD_0, 0x62)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 0x63)
        put(KeyEvent.KEYCODE_NUMPAD_COMMA, 0x85)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 0x67)

        // Application / GUI.
        put(KeyEvent.KEYCODE_MENU, 0x65)         // Application / context menu
    }

    /**
     * `KeyEvent` code -> HID **modifier usage ID** (0xE0..0xE7) for the eight
     * modifier keys.
     *
     * Kept apart from [BY_KEY_CODE] on purpose: a modifier has two distinct HID
     * numbers - its usage ID (0xE0..) and its bit inside report byte 0
     * ([HidReport.Modifier]). Returning the bit where a usage is expected produces
     * a report that presses 'a' when the user pressed Ctrl, which is exactly the
     * bug the unit tests caught while this table was being written.
     */
    private val MODIFIER_USAGE_BY_KEY_CODE: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_CTRL_LEFT to 0xE0,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xE1,
        KeyEvent.KEYCODE_ALT_LEFT to 0xE2,
        KeyEvent.KEYCODE_META_LEFT to 0xE3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xE4,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xE5,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xE6,
        KeyEvent.KEYCODE_META_RIGHT to 0xE7,
    )

    /** HID usage ID for [keyCode], or `null` when the key has no HID equivalent. */
    fun usageFor(keyCode: Int): Int? = BY_KEY_CODE[keyCode] ?: MODIFIER_USAGE_BY_KEY_CODE[keyCode]

    /** True when [keyCode] is one of the eight modifier keys. */
    fun isModifierKeyCode(keyCode: Int): Boolean = MODIFIER_USAGE_BY_KEY_CODE.containsKey(keyCode)

    /**
     * HID usage ID of a modifier key (0xE0..0xE7), or `null` for non-modifiers.
     * Use with [HidReport.Modifier.bitForUsage] to get the report bit.
     */
    fun modifierUsageFor(keyCode: Int): Int? = MODIFIER_USAGE_BY_KEY_CODE[keyCode]

    /** How many keys this table knows about (used by tests to catch silent shrink). */
    val size: Int get() = BY_KEY_CODE.size + MODIFIER_USAGE_BY_KEY_CODE.size
}
