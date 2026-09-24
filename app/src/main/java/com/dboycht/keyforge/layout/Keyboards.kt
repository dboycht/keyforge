package com.dboycht.keyforge.layout

import android.view.KeyEvent

/**
 * The shipped layouts.
 *
 * Four shapes, one renderer:
 * - [PC_60] - the "type like a computer" layout: a 60% expressed the way its spec reads
 *   (15 key units wide, Esc/` above the number row). Landscape; the default in a wide window.
 * - [PHONE_12] - the general-purpose phone keyboard, **12 units** wide, five rows. Fewer keys than
 *   [PC_60] and therefore bigger ones; the default in a narrow window.
 * - [SPLIT_13] - the same idea split down the middle (6 units + a gap + 6 units) for two-handed
 *   landscape use, with a space bar under each thumb.
 * - [GAME] - a pad for streaming and games: WASD-ish movement under the left thumb, an inverted-T
 *   arrow cluster under the right. **Not a typing layout** ([KeyboardLayout.textCapable] is false).
 *
 * ## What was deleted, and why it is written down
 *
 * The 2026-09-24 layouts round removed two of the three previous layouts. Both had defects that a
 * hand-written table hides well and a phone reveals immediately:
 * - the old `full` used **0.5-unit keys** for `\` and `,`. Half a unit is ~15dp on a 360dp phone -
 *   narrower than a fingertip - and it shipped neither `-` nor `'`, so "e-mail" or "don't" could not
 *   be typed at all.
 * - the old `split` was **missing l, o and p**. "hello", "you" and "people" were literally
 *   untypable. The unit test of the day only asked whether the *essential* editing keys were
 *   present, so it passed.
 *
 * The guards that would have caught both now exist in `KeyboardsTest`: every text-capable layout
 * must contain all 26 letters and all 10 digits, and no key may be narrower than one unit.
 *
 * ## The two rules a layout table must satisfy
 *
 * 1. every row sums to the same number of units **including its indent** - `validate()` enforces it,
 *    otherwise the renderer squeezes the wider rows and keys change size from row to row;
 * 2. no key shorter than one unit and no row of more than 13-15 keys - a phone key that narrow is
 *    not tappable.
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

    /** Letters of [letters] (lower case), in order, each one key unit wide. */
    private fun letterKeys(letters: String, upperCase: Boolean = false): List<KeySpec> =
        letters.map { ch ->
            key(
                label = if (upperCase) ch.uppercaseChar().toString() else ch.toString(),
                keyCode = KeyEvent.KEYCODE_A + (ch - 'a'),
            )
        }

    /**
     * `1`..`0` with their shifted symbols, shared by every layout that has a number row.
     *
     * Shared on purpose: three layouts each spelling out ten digits is three chances to typo one.
     */
    private val DIGITS: List<KeySpec> = listOf(
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
    )

    private val ESC = action("Esc", KeyEvent.KEYCODE_ESCAPE)
    private val BACKSPACE = action("Bksp", KeyEvent.KEYCODE_DEL)

    /** 60% layout: five rows, every row exactly 15 key units wide. */
    val PC_60: KeyboardLayout = KeyboardLayout(
        id = "pc60",
        displayName = "电脑全键盘",
        rows = listOf(
            // Esc(1) + `(1) + 1..0(10) + -(1) + =(1) + \(1) = 15 exactly.
            //
            // The backslash sits here and Backspace sits at the end of the letter row below: the two
            // were swapped at the user's request (2026-09-24), which also gives Backspace the wider
            // key of the pair - it is pressed constantly, `\` almost never.
            listOf(ESC, key("`", KeyEvent.KEYCODE_GRAVE, shiftLabel = "~")) +
                DIGITS +
                listOf(
                    key("-", KeyEvent.KEYCODE_MINUS, shiftLabel = "_"),
                    key("=", KeyEvent.KEYCODE_EQUALS, shiftLabel = "+"),
                    key("\\", KeyEvent.KEYCODE_BACKSLASH, shiftLabel = "|"),
                ),
            // Tab(1.5) + q..p(10) + [(1) + ](1) + Bksp(1.5) = 15.
            listOf(action("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.5f)) +
                letterKeys("qwertyuiop", upperCase = true) +
                listOf(
                    key("[", KeyEvent.KEYCODE_LEFT_BRACKET, shiftLabel = "{"),
                    key("]", KeyEvent.KEYCODE_RIGHT_BRACKET, shiftLabel = "}"),
                    action("Bksp", KeyEvent.KEYCODE_DEL, widthUnits = 1.5f),
                ),
            // Caps(1.75) + a..l(9) + ;(1) + '(1) + Enter(2.25) = 15.
            listOf(action("Caps", KeyEvent.KEYCODE_CAPS_LOCK, widthUnits = 1.75f)) +
                letterKeys("asdfghjkl", upperCase = true) +
                listOf(
                    key(";", KeyEvent.KEYCODE_SEMICOLON, shiftLabel = ":"),
                    key("'", KeyEvent.KEYCODE_APOSTROPHE, shiftLabel = "\""),
                    action("Enter", KeyEvent.KEYCODE_ENTER, widthUnits = 2.25f),
                ),
            // Shift(2.25) + z..m(7) + ,(1) + .(1) + /(1) + Shift(2.75) = 15.
            listOf(modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 2.25f)) +
                letterKeys("zxcvbnm", upperCase = true) +
                listOf(
                    key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"),
                    key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                    key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                    modifier("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, widthUnits = 2.75f),
                ),
            // Ctrl(1.25) + Win(1) + Alt(1.25) + Space(5.25) + Alt(1.25) + Win(1) + arrows(4) = 15.
            //
            // The arrow pads were added in the layouts round: the row used to carry only left/right,
            // which is the one pair a text caret does not need most (up/down move by line). Four
            // arrows cost the space bar one unit and nothing else.
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Win", KeyEvent.KEYCODE_META_LEFT, widthUnits = 1f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT, widthUnits = 1.25f),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 5.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT, widthUnits = 1.25f),
                modifier("Win", KeyEvent.KEYCODE_META_RIGHT, widthUnits = 1f),
                action("←", KeyEvent.KEYCODE_DPAD_LEFT),
                action("↑", KeyEvent.KEYCODE_DPAD_UP),
                action("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                action("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        ),
    )

    /**
     * The general-purpose phone keyboard: **12 units, five rows**, landscape or portrait.
     *
     * 12 units is a deliberate compromise. 15 units (PC_60) across a 360dp-wide portrait phone
     * leaves a 24dp key - below a fingertip - while 12 units leaves 30dp, which is the width a
     * system keyboard uses. Five rows is what the content needs: a number row, three letter rows and
     * a control row. Nothing here is narrower than one unit, which is what the deleted `full` got
     * wrong, and every row reads like a keyboard row: a wide key at one end (Esc/Tab/Caps/Shift) and
     * a wide key at the other (Bksp/Enter), so the row ends do not line up into a grid.
     */
    val PHONE_12: KeyboardLayout = KeyboardLayout(
        id = "phone12",
        displayName = "手机全屏",
        rows = listOf(
            // Esc(1) + 1..0(10) + Bksp(1) = 12.
            listOf(ESC) + DIGITS + listOf(BACKSPACE),
            // Tab(1) + q..p(10) + '(1) = 12. The apostrophe rides at the end of the top letter row,
            // where a physical keyboard also keeps it - and where the deleted `full` had nothing.
            listOf(action("Tab", KeyEvent.KEYCODE_TAB)) +
                letterKeys("qwertyuiop") +
                listOf(key("'", KeyEvent.KEYCODE_APOSTROPHE, shiftLabel = "\"")),
            // Caps(1) + a..l(9) + Enter(2) = 12. Enter gets two units: it is the most-pressed key
            // that is not a letter, and a one-unit Enter on a phone is a miss waiting to happen.
            listOf(action("Caps", KeyEvent.KEYCODE_CAPS_LOCK)) +
                letterKeys("asdfghjkl") +
                listOf(action("Enter", KeyEvent.KEYCODE_ENTER, widthUnits = 2f)),
            // Shift(1) + z..m(7) + ,(1) + .(1) + /(1) + Shift(1) = 12.
            // Two one-unit shifts, one at each bottom corner, so either thumb can capitalise.
            // They carry the glyph rather than the word: "Shift" is five characters on a one-unit
            // key, and on the device that rendered as "Shi" (Compose clipped the rest - see
            // KeyboardView.labelSizeFor). Every phone keyboard uses the same glyph.
            listOf(modifier("⇧", KeyEvent.KEYCODE_SHIFT_LEFT)) +
                letterKeys("zxcvbnm") +
                listOf(
                    key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"),
                    key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                    key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                    modifier("⇧", KeyEvent.KEYCODE_SHIFT_RIGHT),
                ),
            // Ctrl(1.25) + Alt(1) + -(1) + Space(3.5) + Alt(1) + Ctrl(1.25) + ←(1) + ↓(1) + →(1) = 12.
            // The hyphen lives here because it is one of the six punctuation marks ordinary text
            // needs (the deleted `full` had none, so "e-mail" was untypable) and the number row has
            // no room for it once Esc and Bksp own the ends.
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT),
                key("-", KeyEvent.KEYCODE_MINUS, shiftLabel = "_"),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 3.5f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT),
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, widthUnits = 1.25f),
                action("←", KeyEvent.KEYCODE_DPAD_LEFT),
                action("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                action("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        ),
    )

    /**
     * The two-handed landscape layout: **13 units, five rows**, split 6 + gap(1) + 6.
     *
     * This replaces the deleted `split`, which was 12 units wide and - more importantly - was
     * missing l, o and p, so it could not spell "hello" or "you". The half is 6 units here rather
     * than 5.5 because that is what the letter rows need to stay whole:
     *
     * ```
     *   1 2 3 4 5   |   6 7 8 9 0  ⌫
     *   q w e r t   |   y u i o p  '
     *   a s d f g   |   h j k l -  ⏎
     *   z x c v b   |   n m , . /  ⇧
     * ```
     *
     * The gap is one unit and sits in the same column on every row, so the two hands always land on
     * the same column, and the bottom row carries a full-width space bar under each thumb.
     */
    val SPLIT_13: KeyboardLayout = KeyboardLayout(
        id = "split13",
        displayName = "双手分体（横屏）",
        rows = listOf(
            // Esc(1) + 1..5(5) = 6 | 6..0(5) + Bksp(1) = 6.
            // Backspace sits at the top of the right half, where a split keyboard keeps it; the
            // first draft of this layout forgot it entirely and the "essential keys" test caught it.
            listOf(ESC) + DIGITS.subList(0, 5) + listOf(spacer()) +
                DIGITS.subList(5, 10) + listOf(BACKSPACE),
            // Tab(1) + q..t(4) = 6 | y..p(5) + '(1) = 6.
            listOf(action("Tab", KeyEvent.KEYCODE_TAB)) +
                letterKeys("qwert") + listOf(spacer()) +
                letterKeys("yuiop") +
                listOf(key("'", KeyEvent.KEYCODE_APOSTROPHE, shiftLabel = "\"")),
            // Caps(1) + a..g(5) = 6 | h..l(4) + -(1) + Enter(1) = 6.
            listOf(action("Caps", KeyEvent.KEYCODE_CAPS_LOCK)) +
                letterKeys("asdfg") + listOf(spacer()) +
                letterKeys("hjkl") +
                listOf(
                    key("-", KeyEvent.KEYCODE_MINUS, shiftLabel = "_"),
                    action("Enter", KeyEvent.KEYCODE_ENTER),
                ),
            // Shift(1) + z..b(5) = 6 | n..m(2) + ,(1) + .(1) + /(1) + Shift(1) = 6.
            // Glyph labels for the same reason as [PHONE_12]: five characters do not fit one unit.
            listOf(modifier("⇧", KeyEvent.KEYCODE_SHIFT_LEFT)) +
                letterKeys("zxcvb") + listOf(spacer()) +
                letterKeys("nm") +
                listOf(
                    key(",", KeyEvent.KEYCODE_COMMA, shiftLabel = "<"),
                    key(".", KeyEvent.KEYCODE_PERIOD, shiftLabel = ">"),
                    key("/", KeyEvent.KEYCODE_SLASH, shiftLabel = "?"),
                    modifier("⇧", KeyEvent.KEYCODE_SHIFT_RIGHT),
                ),
            // Ctrl(1.25) + Alt(1) + Space(3.75) = 6 | Space(3.75) + Alt(1) + Ctrl(1.25) = 6.
            // Two space bars, mirrored: with the hands apart, either thumb gets its own.
            listOf(
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.25f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 3.75f),
                spacer(),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 3.75f),
                modifier("Alt", KeyEvent.KEYCODE_ALT_RIGHT),
                modifier("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, widthUnits = 1.25f),
            ),
        ),
    )

    /**
     * A pad for games and streaming: **12 units, four rows**, two thumb zones.
     *
     * Why this exists: the reported use of this app includes playing games on the host, and a typing
     * keyboard is the wrong shape for that - the keys a game needs (WASD, QERF, digits 1-5, Space,
     * Shift, Ctrl, Tab, Esc, Enter and a real arrow cluster) are scattered over three letter rows on
     * [PHONE_12] and the arrows are squeezed into the control row. Here they get their own halves:
     *
     * ```
     *   Esc  Tab  1 2 3 4 5  Bksp  Enter
     *   Shift  Q W E R      [ ↑ ]        (↑ centred over ↓)
     *   Ctrl   A S D  F     ← ↓ →
     *   Space   Z X   [gap]  C V  Space
     * ```
     *
     * It is deliberately **not** a typing layout: it carries twelve letters, not twenty-six.
     * [KeyboardLayout.textCapable] says so, the picker names it that way, and the tests do not ask it
     * for the full alphabet.
     */
    val GAME: KeyboardLayout = KeyboardLayout(
        id = "game",
        displayName = "游戏方向键（不能打字）",
        textCapable = false,
        rows = listOf(
            // Esc(2) + Tab(1.5) + 1..5(5) + Bksp(1.5) + Enter(2) = 12.
            listOf(
                action("Esc", KeyEvent.KEYCODE_ESCAPE, widthUnits = 2f),
                action("Tab", KeyEvent.KEYCODE_TAB, widthUnits = 1.5f),
            ) + DIGITS.subList(0, 5) + listOf(
                action("Bksp", KeyEvent.KEYCODE_DEL, widthUnits = 1.5f),
                action("Enter", KeyEvent.KEYCODE_ENTER, widthUnits = 2f),
            ),
            // Shift(2) + QWER(4) = 6 | gap(2) + ↑(2) + gap(2) = 6.
            listOf(modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, widthUnits = 2f)) +
                letterKeys("qwer", upperCase = true) +
                listOf(spacer(2f), action("↑", KeyEvent.KEYCODE_DPAD_UP, widthUnits = 2f), spacer(2f)),
            // Ctrl(1.5) + ASD(3) + F(1.5) = 6 | ←(2) + ↓(2) + →(2) = 6.
            listOf(modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, widthUnits = 1.5f)) +
                letterKeys("asd", upperCase = true) +
                listOf(action("F", KeyEvent.KEYCODE_F, widthUnits = 1.5f)) +
                listOf(
                    action("←", KeyEvent.KEYCODE_DPAD_LEFT, widthUnits = 2f),
                    action("↓", KeyEvent.KEYCODE_DPAD_DOWN, widthUnits = 2f),
                    action("→", KeyEvent.KEYCODE_DPAD_RIGHT, widthUnits = 2f),
                ),
            // Space(3.5) + ZX(2) = 5.5 | gap(1) | CV(2) + Space(3.5) = 5.5.
            listOf(
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 3.5f),
                key("Z", KeyEvent.KEYCODE_Z),
                key("X", KeyEvent.KEYCODE_X),
                spacer(),
                key("C", KeyEvent.KEYCODE_C),
                key("V", KeyEvent.KEYCODE_V),
                key("Space", KeyEvent.KEYCODE_SPACE, widthUnits = 3.5f),
            ),
        ),
    )

    /** Every layout the picker offers, in display order. */
    val all: List<KeyboardLayout> = listOf(PC_60, PHONE_12, SPLIT_13, GAME)

    /** Looks up a layout by id, falling back to the first one. */
    fun byId(id: String?): KeyboardLayout = byIdOrNull(id) ?: all.first()

    /**
     * Looks up a layout by id, or `null` when there is no such layout.
     *
     * [byId]'s fallback is "the first layout", which is what a caller wants when it only needs *a*
     * keyboard. A caller that has to place the result on screen wants to tell "the user picked this"
     * apart from "the saved id is gone" - a layout that was deleted in a later version leaves a stale
     * id in the settings, and falling back to the first entry would silently hand a portrait phone the
     * 15-unit PC layout. That caller asks [byIdOrNull] and then [defaultFor] instead.
     */
    fun byIdOrNull(id: String?): KeyboardLayout? = all.firstOrNull { it.id == id }

    /**
     * The layout to use when the user has not chosen one (or the chosen one no longer exists).
     *
     * [wide] describes the window, not the device: the same phone is narrow in portrait and wide
     * in landscape. A narrow window gets [PHONE_12] and a wide one gets [PC_60]; the user's own
     * choice, once made, always wins over this.
     */
    fun defaultFor(wide: Boolean): KeyboardLayout = if (wide) PC_60 else PHONE_12
}
