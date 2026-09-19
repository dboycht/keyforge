package com.dboycht.keyforge.session

import android.view.KeyEvent
import com.dboycht.keyforge.hid.HidKeyMap
import com.dboycht.keyforge.hid.HidReport

/**
 * One thing the user can press in the minimal keyboard page.
 *
 * A modifier key appears exactly once (e.g. "Shift") even though Android models a
 * left and a right key code: the minimal page only needs "hold Shift", while the
 * full keyboard will expose both sides.
 */
internal data class KeyboardKey(
    val label: String,
    val keyCode: Int,
) {
    /** HID usage ID to put in the report, or `null` when unmappable. */
    val usage: Int? get() = HidKeyMap.usageFor(keyCode)

    /** True for the eight modifier keys (they live in byte 0, not in a key slot). */
    val isModifier: Boolean get() = HidKeyMap.isModifierKeyCode(keyCode)

    /** Monospace label of the usage ID, for the on-screen diagnostics line. */
    val usageLabel: String
        get() = usage?.let { "0x%02X".format(it) } ?: "n/a"

    /** Modifier bit name when this is a modifier key. */
    val modifierLabel: String?
        get() = usage
            ?.let { HidReport.Modifier.bitForUsage(it) }
            ?.takeIf { it != 0 }
            ?.let { HidReport.Modifier.nameForBit(it) }

    companion object {
        /**
         * The minimal set used to prove the chain end to end, plus the keys a
         * person instinctively reaches for while testing ("can it type a word?").
         */
        fun minimalSet(): List<KeyboardKey> = listOf(
            KeyboardKey("a", KeyEvent.KEYCODE_A),
            KeyboardKey("b", KeyEvent.KEYCODE_B),
            KeyboardKey("Shift", KeyEvent.KEYCODE_SHIFT_LEFT),
            KeyboardKey("Space", KeyEvent.KEYCODE_SPACE),
            KeyboardKey("Enter", KeyEvent.KEYCODE_ENTER),
            KeyboardKey("Backspace", KeyEvent.KEYCODE_DEL),
            KeyboardKey("1", KeyEvent.KEYCODE_1),
            KeyboardKey(".", KeyEvent.KEYCODE_PERIOD),
        )
    }
}
