package com.dboycht.keyforge.layout

import android.view.KeyEvent

/**
 * The shipped layouts.
 *
 * One renderer, several shapes:
 * - [PC_60]: a 60% layout expressed the way its spec reads (15 key units wide,
 *   Esc/` offset above the number row, backslash at the end of the bottom row so every
 *   row is exactly 15 units). The "type like a computer" layout.
 * - [FULL] / [FULL_COMPACT]: **12 units wide** full screen layouts. Narrower than PC_60
 *   on purpose - full screen trades key count for key size, and 15 units across a phone
 *   in landscape leaves keys barely wider than a fingertip. `FULL_COMPACT` drops the
 *   number row so four rows share the height and every key gets taller.
 * - [PHONE_STYLE]: a compact 10-unit layout with big keys for thumb typing.
 *
 * Adding a layout = adding a list here; no renderer or session change. Every row must sum
 * to the same number of units - `KeyboardLayout.validate()` enforces it at construction,
 * so a mistyped width fails the unit tests instead of looking broken on a device.
 */
internal object Keyboards {

    private fun key(
        label: String,
        keyCode: Int,
        widthUnits: Float = 1f,
        shiftLabel: String? = null,
        kind: KeyKind = KeyKind.NORMAL,
    ) = KeySpec(label, keyCode, widthUnits, shiftLabel, kind)

    private fun modifier(label: String, keyCode: Int, widthUnits: Float = 1f) =
        KeySpec(label, keyCode, widthUnits, kind = KeyKind.MODIFIER)

    private fun action(label: String, keyCode: Int, widthUnits: Float = 1f) =
        KeySpec(label, keyCode, widthUnits, kind = KeyKind.ACTION)

    /** 60% layout: five rows, every row exactly 15 key units wide. */
    val PC_60: KeyboardLayout = KeyboardLayout(
        id = "pc60",
        displayName = "电脑全键盘",
        rows = listOf(
            // Esc(1) + `(1) + 1..0(10) + -(1) + =(1) + Bksp(1) = 15 exactly.
            listOf(
                action("Esc", KeyEvent.KEYCODE_ESCAPE),
                key("`", KeyEvent.KEYCODE_GRAVE, shiftLabel = "~"),
                key("1", KeyEvent.KEYCODE_1, shiftLabel = "!"),
                key("2", KeyEvent.KEYCODE_2, shiftLabel = "@"),
                key("3", KeyEvent.KEYCODE_3, shiftLabel = "#"),
                key("4", KeyEvent.KEYCODE_4, shiftLabel = "$"),
                key("5", KeyEvent.KEYCODE_5, shiftLabel = "%"),
                key("6", KeyEvent.KEYCODE_6, shiftLabel = "^"),
                key("7", KeyEvent.KEYCODE_7, shiftLabel = "&"),
                key("8", KeyEvent.KEYCODE_8, shiftLabel = "*"),
                key("9", KeyEvent.KEYCODE_9, shiftLabel = "("),
                key("0", KeyEvent.KEYCODE_0, shiftLabel = ")"),
                key("-", KeyEvent.KEYCODE_MINUS, shiftLabel = "_"),
                key("=", KeyEvent.KEYCODE_EQUALS, shiftLabel = "+"),
                action("Bksp", KeyEvent.KEYCODE_DEL),
            ),
            listOf(
                action("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.5f),
                key("Q", KeyEvent.KEYCODE_Q), key("W", KeyEvent.KEYCODE_W),
                key("E", KeyEvent.KEYCODE_E), key("R", KeyEvent.KEYCODE_R),
                key("T", KeyEvent.KEYCODE_T), key("Y", KeyEvent.KEYCODE_Y),
                key("U", KeyEvent.KEYCODE_U), key("I", KeyEvent.KEYCODE_I),
                key("O", KeyEvent.KEYCODE_O), key("P", KeyEvent.KEYCODE_P),
                key("[", KeyEvent.KEYCODE_LEFT_BRACKET, shiftLabel = "{"),
                key("]", KeyEvent.KEYCODE_RIGHT_BRACKET, shiftLabel = "}"),
                key("\\", KeyEvent.KEYCODE_BACKSLASH, widthUnits = 1.5f, shiftLabel = "|"),
            ),
            listOf(
                modifier("Caps", KeyEvent.KEYCODE_CAPS_LOCK, widthUnits = 1.75f),
                key("A", KeyEvent.KEYCODE_A), key("S", KeyEvent.KEYCODE_S),
                key("D", KeyEvent.KEYCODE_D), key("F", KeyEvent.KEYCODE_F),
                key("G", KeyEvent.KEYCODE_G), key("H", KeyEvent.KEYCODE_H),
                key("J", KeyEvent.KEYCODE_J), key("K", KeyEvent.KEYCODE_K),
                key("L", KeyEvent.KEYCODE_L),
                key(";", KeyEvent.KEYCODE_SEMICOLON, shiftLabel = ":"),
                key("'", KeyEvent.KEYCODE_APOSTROPHE, shiftLabel = "\""),
                action("Enter", KeyEvent.KEYCODE_ENTER, widthUnits = 2.25f),
            ),
            listOf(
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 2.25f),
                key("Z", KeyEvent.KEYCODE_Z), key("X", KeyEvent.KEYCODE_X),
                key("C", KeyEvent.KEYCODE_C), key("V", KeyEvent.KEYCODE_V),
                key("B", KeyEvent.KEYCODE_B), key("N", KeyEvent.KEYCODE_N),
                key("M", KeyEvent.KEYCODE_M),
                key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"),
                key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, widthUnits = 2.75f),
            ),
            // 1.25 + 1.25 + 1.25 + 6.25 + 1.25 + 1.25 + 1.25 + 1.25 = 15
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Win", KeyEvent.KEYCODE_META_LEFT, widthUnits = 1.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT, widthUnits = 1.25f),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 6.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT, widthUnits = 1.25f),
                modifier("Win", KeyEvent.KEYCODE_META_RIGHT, widthUnits = 1.25f),
                action("←", KeyEvent.KEYCODE_DPAD_LEFT, widthUnits = 1.25f),
                action("→", KeyEvent.KEYCODE_DPAD_RIGHT, widthUnits = 1.25f),
            ),
        ),
    )

    /** Phone-style layout: 10 units wide, tall keys, thumb friendly. */
    val PHONE_STYLE: KeyboardLayout = KeyboardLayout(
        id = "phone",
        displayName = "手机风格",
        rows = listOf(
            // 1.5 + 8 + 0.5? -> digits row: 10 keys of 1u.
            listOf(
                key("1", KeyEvent.KEYCODE_1, shiftLabel = "!"),
                key("2", KeyEvent.KEYCODE_2, shiftLabel = "@"),
                key("3", KeyEvent.KEYCODE_3, shiftLabel = "#"),
                key("4", KeyEvent.KEYCODE_4, shiftLabel = "$"),
                key("5", KeyEvent.KEYCODE_5, shiftLabel = "%"),
                key("6", KeyEvent.KEYCODE_6, shiftLabel = "^"),
                key("7", KeyEvent.KEYCODE_7, shiftLabel = "&"),
                key("8", KeyEvent.KEYCODE_8, shiftLabel = "*"),
                key("9", KeyEvent.KEYCODE_9, shiftLabel = "("),
                key("0", KeyEvent.KEYCODE_0, shiftLabel = ")"),
            ),
            listOf(
                key("q", KeyEvent.KEYCODE_Q), key("w", KeyEvent.KEYCODE_W),
                key("e", KeyEvent.KEYCODE_E), key("r", KeyEvent.KEYCODE_R),
                key("t", KeyEvent.KEYCODE_T), key("y", KeyEvent.KEYCODE_Y),
                key("u", KeyEvent.KEYCODE_U), key("i", KeyEvent.KEYCODE_I),
                key("o", KeyEvent.KEYCODE_O), key("p", KeyEvent.KEYCODE_P),
            ),
            // Shift(1) + a..l(9) = 10. The ONLY Shift: the thumb reaches it while the
            // other hand keeps typing, and one Shift keeps the layout unambiguous.
            listOf(
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 1f),
                key("a", KeyEvent.KEYCODE_A), key("s", KeyEvent.KEYCODE_S),
                key("d", KeyEvent.KEYCODE_D), key("f", KeyEvent.KEYCODE_F),
                key("g", KeyEvent.KEYCODE_G), key("h", KeyEvent.KEYCODE_H),
                key("j", KeyEvent.KEYCODE_J), key("k", KeyEvent.KEYCODE_K),
                key("l", KeyEvent.KEYCODE_L),
            ),
            // z..m(7) + , . / (3 * 1) = 10 (no Shift on this row)
            listOf(
                key("z", KeyEvent.KEYCODE_Z), key("x", KeyEvent.KEYCODE_X),
                key("c", KeyEvent.KEYCODE_C), key("v", KeyEvent.KEYCODE_V),
                key("b", KeyEvent.KEYCODE_B), key("n", KeyEvent.KEYCODE_N),
                key("m", KeyEvent.KEYCODE_M),
                key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"),
                key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
            ),
            // Ctrl(1.25) + Space(4) + Tab(1.25) + Bksp(1.25) + Enter(1.25) + -(1) = 10
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 4f),
                key("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.25f),
                action("Bksp", KeyEvent.KEYCODE_DEL, widthUnits = 1.25f),
                action("Enter", KeyEvent.KEYCODE_ENTER, widthUnits = 1.25f),
                key("-", KeyEvent.KEYCODE_MINUS, shiftLabel = "_"),
            ),
        ),
    )

    /**
     * Full screen layout with a number row: **12 key units wide**, five rows.
     *
     * Why a separate layout instead of reusing [PC_60]: full screen trades key *count* for
     * key *size*. 15 units of PC_60 across a phone in landscape leaves keys barely wider
     * than a fingertip, so this one is narrower (12 units, fewer/wider keys), which makes
     * every key bigger at the same screen width.
     *
     * Every row is exactly 12 units, and each row is balanced so neither end is much
     * wider than the other - an unbalanced row reads as a rendering mistake.
     */
    val FULL: KeyboardLayout = KeyboardLayout(
        id = "full",
        displayName = "全屏（带数字行）",
        rows = listOf(
            // Esc(1) + 1..0(10) + Bksp(1) = 12.
            listOf(
                action("Esc", KeyEvent.KEYCODE_ESCAPE),
                key("1", KeyEvent.KEYCODE_1, shiftLabel = "!"),
                key("2", KeyEvent.KEYCODE_2, shiftLabel = "@"),
                key("3", KeyEvent.KEYCODE_3, shiftLabel = "#"),
                key("4", KeyEvent.KEYCODE_4, shiftLabel = "$"),
                key("5", KeyEvent.KEYCODE_5, shiftLabel = "%"),
                key("6", KeyEvent.KEYCODE_6, shiftLabel = "^"),
                key("7", KeyEvent.KEYCODE_7, shiftLabel = "&"),
                key("8", KeyEvent.KEYCODE_8, shiftLabel = "*"),
                key("9", KeyEvent.KEYCODE_9, shiftLabel = "("),
                key("0", KeyEvent.KEYCODE_0, shiftLabel = ")"),
                action("Bksp", KeyEvent.KEYCODE_DEL),
            ),
            // Tab(1.5) + q..p(10) + \(0.5) = 12.
            listOf(
                action("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.5f),
                key("q", KeyEvent.KEYCODE_Q), key("w", KeyEvent.KEYCODE_W),
                key("e", KeyEvent.KEYCODE_E), key("r", KeyEvent.KEYCODE_R),
                key("t", KeyEvent.KEYCODE_T), key("y", KeyEvent.KEYCODE_Y),
                key("u", KeyEvent.KEYCODE_U), key("i", KeyEvent.KEYCODE_I),
                key("o", KeyEvent.KEYCODE_O), key("p", KeyEvent.KEYCODE_P),
                key("\\", KeyEvent.KEYCODE_BACKSLASH, widthUnits = 0.5f, shiftLabel = "|"),
            ),
            // Caps(1) + a..l(9) + Enter(2) = 12.
            listOf(
                action("Caps", KeyEvent.KEYCODE_CAPS_LOCK),
                key("a", KeyEvent.KEYCODE_A), key("s", KeyEvent.KEYCODE_S),
                key("d", KeyEvent.KEYCODE_D), key("f", KeyEvent.KEYCODE_F),
                key("g", KeyEvent.KEYCODE_G), key("h", KeyEvent.KEYCODE_H),
                key("j", KeyEvent.KEYCODE_J), key("k", KeyEvent.KEYCODE_K),
                key("l", KeyEvent.KEYCODE_L),
                action("Enter", KeyEvent.KEYCODE_ENTER, widthUnits = 2f),
            ),
            // Shift(1.5) + z..m(7) + ,(0.5) + .(1) + /(1) + Shift(1) = 12.
            listOf(
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 1.5f),
                key("z", KeyEvent.KEYCODE_Z), key("x", KeyEvent.KEYCODE_X),
                key("c", KeyEvent.KEYCODE_C), key("v", KeyEvent.KEYCODE_V),
                key("b", KeyEvent.KEYCODE_B), key("n", KeyEvent.KEYCODE_N),
                key("m", KeyEvent.KEYCODE_M),
                key(",", KeyEvent.KEYCODE_COMMA, widthUnits = 0.5f, shiftLabel = "<"),
                key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT),
            ),
            // Ctrl(1.25) + Alt(1) + Space(4.5) + Alt(1) + Ctrl(1.25) + arrows(3) = 12.
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT, widthUnits = 1f),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 4.5f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT, widthUnits = 1f),
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, widthUnits = 1.25f),
                action("←", KeyEvent.KEYCODE_DPAD_LEFT),
                action("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                action("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        ),
    )

    /**
     * Full screen layout **without** the number row: four rows, so each key is taller.
     *
     * Same 12-unit grid as [FULL] minus the top row; for people who mostly type letters
     * and want the biggest possible targets.
     */
    val FULL_COMPACT: KeyboardLayout = KeyboardLayout(
        id = "fullCompact",
        displayName = "全屏（无数字行·键更大）",
        rows = listOf(
            // Tab(1.5) + q..p(10) + \(0.5) = 12.
            listOf(
                action("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.5f),
                key("q", KeyEvent.KEYCODE_Q), key("w", KeyEvent.KEYCODE_W),
                key("e", KeyEvent.KEYCODE_E), key("r", KeyEvent.KEYCODE_R),
                key("t", KeyEvent.KEYCODE_T), key("y", KeyEvent.KEYCODE_Y),
                key("u", KeyEvent.KEYCODE_U), key("i", KeyEvent.KEYCODE_I),
                key("o", KeyEvent.KEYCODE_O), key("p", KeyEvent.KEYCODE_P),
                key("\\", KeyEvent.KEYCODE_BACKSLASH, widthUnits = 0.5f, shiftLabel = "|"),
            ),
            // Caps(1) + a..l(9) + Bksp(1) + Enter(1) = 12.
            // Bksp lives here (not only on the number row): the compact layout has no
            // number row, and a full screen keyboard without a reachable Backspace is
            // useless for editing - a contract test caught exactly that.
            listOf(
                action("Caps", KeyEvent.KEYCODE_CAPS_LOCK),
                key("a", KeyEvent.KEYCODE_A), key("s", KeyEvent.KEYCODE_S),
                key("d", KeyEvent.KEYCODE_D), key("f", KeyEvent.KEYCODE_F),
                key("g", KeyEvent.KEYCODE_G), key("h", KeyEvent.KEYCODE_H),
                key("j", KeyEvent.KEYCODE_J), key("k", KeyEvent.KEYCODE_K),
                key("l", KeyEvent.KEYCODE_L),
                action("Bksp", KeyEvent.KEYCODE_DEL),
                action("Enter", KeyEvent.KEYCODE_ENTER),
            ),
            // Shift(1.5) + z..m(7) + ,(0.5) + .(1) + /(1) + Shift(1) = 12.
            listOf(
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 1.5f),
                key("z", KeyEvent.KEYCODE_Z), key("x", KeyEvent.KEYCODE_X),
                key("c", KeyEvent.KEYCODE_C), key("v", KeyEvent.KEYCODE_V),
                key("b", KeyEvent.KEYCODE_B), key("n", KeyEvent.KEYCODE_N),
                key("m", KeyEvent.KEYCODE_M),
                key(",", KeyEvent.KEYCODE_COMMA, widthUnits = 0.5f, shiftLabel = "<"),
                key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT),
            ),
            // Same bottom row as [FULL]: 1.25 + 1 + 4.5 + 1 + 1.25 + 3 = 12.
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT, widthUnits = 1f),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 4.5f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT, widthUnits = 1f),
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, widthUnits = 1.25f),
                action("←", KeyEvent.KEYCODE_DPAD_LEFT),
                action("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                action("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        ),
    )

    /** Every layout the picker offers, in display order. */
    val all: List<KeyboardLayout> = listOf(PC_60, FULL, FULL_COMPACT, PHONE_STYLE)

    /** Looks up a layout by id, falling back to the first one. */
    fun byId(id: String?): KeyboardLayout = all.firstOrNull { it.id == id } ?: all.first()
}
