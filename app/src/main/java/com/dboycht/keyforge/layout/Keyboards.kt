package com.dboycht.keyforge.layout

import android.view.KeyEvent

/**
 * The shipped layouts.
 *
 * Three shapes, one renderer:
 * - [PC_60]: a 60% layout expressed the way its spec reads (15 key units wide, Esc/` offset above
 *   the number row, backslash at the end of the bottom row so every row is exactly 15 units). The
 *   "type like a computer" layout, and the default in landscape.
 * - [FULL]: a **12-unit** full screen layout. Narrower than PC_60 on purpose - full screen trades
 *   key count for key size, and 15 units across a phone leaves keys barely wider than a fingertip.
 * - [SPLIT]: the same 12-unit grid split into a left and a right half with a gap down the middle,
 *   for two-handed use.
 *
 * Every row must sum to the same number of units **including its indent** - `validate()` enforces
 * it, so a mistyped width fails the unit tests instead of looking broken on a device.
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

    /**
     * A gap in a split layout: it takes width so the two halves line up, and draws nothing.
     *
     * The key code exists only because [KeySpec] requires one; a spacer is never drawn, never
     * pressed and never sent. `KEYCODE_UNKNOWN` is the honest placeholder for "not a key".
     */
    private fun spacer(widthUnits: Float = 1f) =
        KeySpec("", KeyEvent.KEYCODE_UNKNOWN, widthUnits, kind = KeyKind.SPACER)

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
     * A **split** 12-unit, five-row layout: the left half is typed with the left thumb, the right
     * half with the right one, with a gap down the middle.
     *
     * For a phone held in two hands, where reaching the middle of a full-width keyboard means
     * moving a hand. The gap is a [KeyKind.SPACER]: it takes width so the halves line up but
     * draws nothing.
     *
     * Every row sums to 12 units, and every row is split 5.5 + 1 + 5.5 so the two hands always
     * land on the same column.
     */
    val SPLIT: KeyboardLayout = KeyboardLayout(
        id = "split",
        displayName = "双手分体（横屏）",
        rows = listOf(
            // 5 x 1 + gap(1) + 5 x 1 = 11, and every row below is 12 - so this row carries one
            // extra 1-unit key to match. The gap keeps the two hands on the same columns.
            listOf(
                key("1", KeyEvent.KEYCODE_1, shiftLabel = "!"),
                key("2", KeyEvent.KEYCODE_2, shiftLabel = "@"),
                key("3", KeyEvent.KEYCODE_3, shiftLabel = "#"),
                key("4", KeyEvent.KEYCODE_4, shiftLabel = "$"),
                key("5", KeyEvent.KEYCODE_5, shiftLabel = "%"),
                spacer(),
                key("6", KeyEvent.KEYCODE_6, shiftLabel = "^"),
                key("7", KeyEvent.KEYCODE_7, shiftLabel = "&"),
                key("8", KeyEvent.KEYCODE_8, shiftLabel = "*"),
                key("9", KeyEvent.KEYCODE_9, shiftLabel = "("),
                key("0", KeyEvent.KEYCODE_0, shiftLabel = ")"),
                key("=", KeyEvent.KEYCODE_EQUALS, shiftLabel = "+"),
            ),
            // Tab(1.5) + q..r(4) + gap(1) + t..i(4) + Bksp(1.5) = 12, and because that already
            // fills the row there is no room for a backslash here: `\` stays in the layouts that
            // have a full punctuation row, and this row keeps its two clean halves.
            listOf(
                action("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.5f),
                key("q", KeyEvent.KEYCODE_Q), key("w", KeyEvent.KEYCODE_W),
                key("e", KeyEvent.KEYCODE_E), key("r", KeyEvent.KEYCODE_R),
                spacer(),
                key("t", KeyEvent.KEYCODE_T), key("y", KeyEvent.KEYCODE_Y),
                key("u", KeyEvent.KEYCODE_U), key("i", KeyEvent.KEYCODE_I),
                action("Bksp", KeyEvent.KEYCODE_DEL, widthUnits = 1.5f),
            ),
            // Caps(1) + asdf(4) + gap(1) + ghjk(4) + ;(1) + Enter(1) = 12.
            listOf(
                action("Caps", KeyEvent.KEYCODE_CAPS_LOCK),
                key("a", KeyEvent.KEYCODE_A), key("s", KeyEvent.KEYCODE_S),
                key("d", KeyEvent.KEYCODE_D), key("f", KeyEvent.KEYCODE_F),
                spacer(),
                key("g", KeyEvent.KEYCODE_G), key("h", KeyEvent.KEYCODE_H),
                key("j", KeyEvent.KEYCODE_J), key("k", KeyEvent.KEYCODE_K),
                key(";", KeyEvent.KEYCODE_SEMICOLON, shiftLabel = ":"),
                action("Enter", KeyEvent.KEYCODE_ENTER),
            ),
            // Shift(1.5) + zxcv(4) + gap(1) + bnm(3) + ,(1) + Shift(1.25) + ?(0.25)? No:
            // Shift(1.5) + zxcv(4) + gap(1) + bnm(3) + ,(1) + Shift(1.5) = 12 exactly.
            listOf(
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 1.5f),
                key("z", KeyEvent.KEYCODE_Z), key("x", KeyEvent.KEYCODE_X),
                key("c", KeyEvent.KEYCODE_C), key("v", KeyEvent.KEYCODE_V),
                spacer(),
                key("b", KeyEvent.KEYCODE_B), key("n", KeyEvent.KEYCODE_N),
                key("m", KeyEvent.KEYCODE_M),
                key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"),
                modifier("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, widthUnits = 1.5f),
            ),
            // Ctrl(1.25) + Alt(1) + Space(2.75) + gap(1) + Space(2.75) + /(1) + Alt(1) +
            // Ctrl(1.25) = 12. Two space bars: with the hands apart, either thumb gets its own.
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT, widthUnits = 1f),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 2.75f),
                spacer(),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 2.75f),
                key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT, widthUnits = 1f),
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, widthUnits = 1.25f),
            ),
        ),
    )


    /** Every layout the picker offers, in display order. */
    val all: List<KeyboardLayout> = listOf(PC_60, FULL, SPLIT)

    /** Looks up a layout by id, falling back to the first one. */
    fun byId(id: String?): KeyboardLayout = all.firstOrNull { it.id == id } ?: all.first()

    /**
     * The layout to use when the user has not chosen one.
     *
     * [wide] describes the window, not the device: the same phone is narrow in portrait and wide
     * in landscape. A narrow window gets [FULL] and a wide one gets [PC_60]; the user's own
     * choice, once made, always wins over this.
     */
    fun defaultFor(wide: Boolean): KeyboardLayout = if (wide) PC_60 else FULL
}
