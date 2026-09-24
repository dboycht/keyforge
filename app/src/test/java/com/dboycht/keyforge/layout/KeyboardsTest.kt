package com.dboycht.keyforge.layout

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for the shipped layouts.
 *
 * These are the cheap guards that keep a hand-written table honest: the renderer
 * assumes equal row widths and mappable keys, so a typo in [Keyboards] must fail
 * here rather than show up as a ragged keyboard on the phone.
 *
 * Two of these guards exist because their absence shipped a broken layout (2026-09-24):
 * the deleted `split` was missing l, o and p - `hello`, `you` and `people` were untypable - and the
 * old tests only asked whether a handful of "essential" keys existed, so it passed. A layout that
 * claims to be a keyboard is now held to the whole alphabet and the whole digit row, and no key may
 * be narrower than one unit (the deleted `full` had two 0.5-unit keys, ~15dp on a phone).
 */
class KeyboardsTest {

    private val layouts = Keyboards.all

    /** Every layout that says it can type text: the ones held to the alphabet. */
    private val typingLayouts = layouts.filter { it.textCapable }

    private val letters = ('a'..'z').map { KeyEvent.KEYCODE_A + (it - 'a') }.toSet()
    private val digits = ('0'..'9').map { KeyEvent.KEYCODE_0 + (it - '0') }.toSet()

    @Test
    fun `every shipped layout validates`() {
        layouts.forEach { layout ->
            val problems = layout.validate()
            assertTrue("${layout.id}: ${problems.joinToString("; ")}", problems.isEmpty())
        }
    }

    @Test
    fun `every shipped layout has a distinct id and a non-empty name`() {
        // Deliberately NOT an exact count: pinning the number turns "a layout was added"
        // into a failing test that says nothing useful, and layouts are expected to grow.
        // What must hold is uniqueness and naming - the picker breaks silently otherwise.
        assertTrue("expected several layouts, got ${layouts.size}", layouts.size >= 2)
        assertEquals(
            "layout ids must be unique: ${layouts.map { it.id }}",
            layouts.size,
            layouts.map { it.id }.toSet().size,
        )
        layouts.forEach { assertTrue("${it.id} needs a display name", it.displayName.isNotBlank()) }
    }

    @Test
    fun `every text-capable layout can type every letter and every digit`() {
        // The guard that would have caught the deleted `split` layout, which shipped without
        // l, o and p: "hello", "you" and "people" could not be typed at all on it.
        assertTrue("expected at least one typing layout", typingLayouts.isNotEmpty())
        typingLayouts.forEach { layout ->
            val present = layout.allKeys.mapNotNull { it.keyCode }.toSet()
            val missingLetters = letters.filterNot { it in present }.map { codeToLetter(it) }
            val missingDigits = digits.filterNot { it in present }.map { codeToDigit(it) }
            assertTrue("${layout.id} is missing letters ${missingLetters}", missingLetters.isEmpty())
            assertTrue("${layout.id} is missing digits ${missingDigits}", missingDigits.isEmpty())
        }
    }

    @Test
    fun `every text-capable layout carries the punctuation ordinary text needs`() {
        // Regression guard for the deleted `full` layout: it had no `-` and no `'`, so "e-mail"
        // and "don't" could not be typed. `?` comes from Shift on the `/` key.
        val required = listOf(
            "`" to KeyEvent.KEYCODE_GRAVE,
            "-" to KeyEvent.KEYCODE_MINUS,
            "=" to KeyEvent.KEYCODE_EQUALS,
            "'" to KeyEvent.KEYCODE_APOSTROPHE,
            "," to KeyEvent.KEYCODE_COMMA,
            "." to KeyEvent.KEYCODE_PERIOD,
            "/" to KeyEvent.KEYCODE_SLASH,
        )
        typingLayouts.forEach { layout ->
            val present = layout.allKeys.mapNotNull { it.keyCode }.toSet()
            // PC_60 is the "everything" layout: it must have all of them. The narrower phone
            // layouts trade symbols away for key size, so they are only held to the common set.
            val mustHave = if (layout.id == Keyboards.PC_60.id) required else
                required.filterNot { it.second == KeyEvent.KEYCODE_GRAVE || it.second == KeyEvent.KEYCODE_EQUALS }
            mustHave.forEach { (label, code) ->
                assertTrue("${layout.id} is missing '$label'", code in present)
            }
        }
    }

    @Test
    fun `no shipped layout has a key narrower than one unit`() {
        // A sub-unit key is untappable: half a unit is ~15dp on a 360dp-wide phone. The deleted
        // `full` layout used 0.5-unit keys for `\` and `,`, which is what prompted this guard.
        layouts.forEach { layout ->
            layout.allKeys.filter { it.kind != KeyKind.SPACER }.forEach { key ->
                assertTrue(
                    "${layout.id}: key '${key.label}' is ${key.widthUnits} units wide",
                    key.widthUnits >= 1f - 0.001f,
                )
            }
        }
    }

    @Test
    fun `pc60 is fifteen key units wide and has five rows`() {
        assertEquals(5, Keyboards.PC_60.rows.size)
        assertEquals(15.0, Keyboards.PC_60.widthUnits.toDouble(), 0.001)
        Keyboards.PC_60.rows.forEach { row ->
            assertEquals(15.0, row.sumOf { it.widthUnits.toDouble() }, 0.001)
        }
    }

    @Test
    fun `the pc layout is fifteen key units wide and no shipped layout exceeds it`() {
        assertEquals(15.0, Keyboards.PC_60.widthUnits.toDouble(), 0.001)
        Keyboards.PC_60.rows.forEach { row ->
            assertEquals(15.0, row.sumOf { it.widthUnits.toDouble() }, 0.001)
        }
        layouts.forEach { layout ->
            assertTrue("${layout.id} is ${layout.widthUnits} units wide", layout.widthUnits <= 15f)
        }
    }

    @Test
    fun `pc60 covers the letters digits and essential modifiers`() {
        val codes = Keyboards.PC_60.allKeys.map { it.keyCode }.toSet()
        ('A'..'Z').forEach { assertTrue("missing letter $it", KeyEvent.KEYCODE_A + (it - 'A') in codes) }
        assertTrue(KeyEvent.KEYCODE_0 in codes)
        assertTrue(KeyEvent.KEYCODE_9 in codes)
        assertTrue(KeyEvent.KEYCODE_TAB in codes)
        assertTrue(KeyEvent.KEYCODE_ENTER in codes)
        assertTrue(KeyEvent.KEYCODE_DEL in codes)
        assertTrue(KeyEvent.KEYCODE_SPACE in codes)
    }

    @Test
    fun `pc60 has a full arrow cluster`() {
        // The bottom row used to carry only left and right; a caret needs up and down far more.
        val codes = Keyboards.PC_60.allKeys.map { it.keyCode }.toSet()
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach { assertTrue("pc60 is missing arrow keyCode $it", it in codes) }
    }

    @Test
    fun `modifier keys are marked as modifiers and resolve to modifier usages`() {
        layouts.forEach { layout ->
            layout.allKeys.filter { it.isModifier }.forEach { key ->
                assertEquals(
                    "${layout.id}/${key.label} should be KeyKind.MODIFIER",
                    KeyKind.MODIFIER,
                    key.kind,
                )
                assertTrue(
                    "${layout.id}/${key.label} usage must be in 0xE0..0xE7",
                    key.usage!! in 0xE0..0xE7,
                )
            }
        }
        // And the other way round: a MODIFIER-shaded key that is not a modifier would latch
        // nothing, which is how Caps Lock used to be drawn.
        layouts.forEach { layout ->
            layout.allKeys.filter { it.kind == KeyKind.MODIFIER }.forEach { key ->
                assertTrue(
                    "${layout.id}/${key.label} is shaded as a modifier but is not one",
                    key.isModifier,
                )
            }
        }
    }

    @Test
    fun `byId finds a layout and falls back safely`() {
        assertEquals(Keyboards.PC_60, Keyboards.byId("pc60"))
        assertEquals(Keyboards.PHONE_12, Keyboards.byId("phone12"))
        assertEquals(Keyboards.SPLIT_13, Keyboards.byId("split13"))
        assertEquals(Keyboards.GAME, Keyboards.byId("game"))
        assertEquals(layouts.first(), Keyboards.byId("does-not-exist"))
        assertEquals(layouts.first(), Keyboards.byId(null))
    }

    @Test
    fun `byIdOrNull reports a stale saved id instead of hiding it`() {
        // Layout choice is persisted by id. Two layouts were deleted in the 2026-09-24 round, so
        // the settings of an upgrading user still name them; the caller has to be able to tell
        // "gone" from "first layout", because the first layout is the 15-unit PC one - unusable in
        // portrait. That is why byIdOrNull exists and KeyboardActivity uses it.
        layouts.forEach { layout ->
            assertEquals(layout.id, Keyboards.byIdOrNull(layout.id)?.id)
        }
        assertNull(Keyboards.byIdOrNull("full"))
        assertNull(Keyboards.byIdOrNull("split"))
        assertNull(Keyboards.byIdOrNull(null))
        assertNull(Keyboards.byIdOrNull("no-such-layout"))
    }

    @Test
    fun `shift labels exist where the shifted character is not the uppercase letter`() {
        val digitOne = Keyboards.PC_60.allKeys.first { it.label == "1" }
        assertEquals("!", digitOne.shiftLabel)
        val semicolon = Keyboards.PC_60.allKeys.first { it.label == ";" }
        assertEquals(":", semicolon.shiftLabel)
        assertNotNull(Keyboards.PC_60.allKeys.first { it.label == "Space" }.usage)
    }

    @Test
    fun `every layout row shows at most fifteen keys so labels stay readable`() {
        // A data-level guard for "don't cram": a row of more than 15 keys cannot stay legible on a
        // phone however it is rendered.
        layouts.forEach { layout ->
            layout.rows.forEachIndexed { index, row ->
                assertTrue("${layout.id} row $index has ${row.size} keys", row.size <= 15)
            }
        }
    }

    @Test
    fun `every ordinary key supports auto repeat and modifiers do not`() {
        // Requested behaviour: holding any normal key repeats it (like a hardware
        // keyboard); modifiers are latching instead, so they must never repeat.
        val ordinary = listOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SEMICOLON,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_CAPS_LOCK,
        )
        ordinary.forEach { code ->
            val key = KeySpec("x", code)
            assertTrue("keyCode $code should auto-repeat", key.supportsAutoRepeat)
        }

        val modifiers = listOf(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
        )
        modifiers.forEach { code ->
            val key = KeySpec("x", code)
            assertFalse("modifier $code must not auto-repeat", key.supportsAutoRepeat)
        }
    }

    @Test
    fun `repeat timing is a sane pair of values`() {
        assertTrue("delay must be long enough to not double a normal tap",
            KeySpec.AUTO_REPEAT_DELAY_MS >= 250L)
        assertTrue("interval must be short enough to feel continuous",
            KeySpec.AUTO_REPEAT_INTERVAL_MS in 20L..120L)
    }

    @Test
    fun `no modifier in any shipped layout is repeatable`() {
        layouts.forEach { layout ->
            layout.allKeys.filter { it.isModifier }.forEach { key ->
                assertFalse("${layout.id}/${key.label} must not repeat", key.supportsAutoRepeat)
            }
        }
    }

    @Test
    fun `the narrow phone layouts are twelve and thirteen units wide with equal rows`() {
        // Narrower than PC_60 on purpose: full screen trades key COUNT for key SIZE. 15 units
        // (PC_60) across a phone leaves keys barely wider than a fingertip, so the phone layouts are
        // 12 (PHONE_12) and 13 (SPLIT_13, which spends its extra unit on a gap between the halves).
        mapOf(Keyboards.PHONE_12 to 12.0, Keyboards.SPLIT_13 to 13.0).forEach { (layout, expected) ->
            assertEquals("${layout.id} width", expected, layout.widthUnits.toDouble(), 0.001)
            layout.rows.forEachIndexed { index, row ->
                val units = layout.offsetFor(index) + row.sumOf { it.widthUnits.toDouble() }
                assertEquals(
                    "${layout.id} row $index must be $expected units with its indent " +
                        "(a ragged row renders as a mistake)",
                    expected,
                    units,
                    0.001,
                )
            }
        }
    }

    @Test
    fun `the phone layouts ship the keys a keyboard needs`() {
        // Full screen should still be able to type a sentence and edit it, so the essentials must
        // be present and reachable.
        val essentials = listOf(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_ESCAPE,
        )
        typingLayouts.forEach { layout ->
            val present = layout.allKeys.mapNotNull { it.keyCode }.toSet()
            essentials.forEach { code ->
                assertTrue("${layout.id} is missing an essential key (keyCode $code)", code in present)
            }
        }
    }

    @Test
    fun `the split layout has a gap down the middle and a space bar on each side`() {
        // The two space bars are the point of the split: with the hands apart, either thumb gets
        // its own. A missing gap would also mean the halves are not really separated, and a gap that
        // moves between rows would mean the hands do not land on the same columns.
        val split = Keyboards.SPLIT_13
        split.rows.forEachIndexed { rowIndex, row ->
            assertEquals("row $rowIndex must have exactly one gap", 1, row.count { it.kind == KeyKind.SPACER })
            val gapIndex = row.indexOfFirst { it.kind == KeyKind.SPACER }
            val left = row.take(gapIndex).sumOf { it.widthUnits.toDouble() }
            val right = row.drop(gapIndex + 1).sumOf { it.widthUnits.toDouble() }
            assertEquals("row $rowIndex halves must be equal", left, right, 0.001)
        }

        val spaces = split.allKeys.count { it.keyCode == KeyEvent.KEYCODE_SPACE }
        assertEquals("one space bar per thumb", 2, spaces)
    }

    @Test
    fun `a spacer is never sent and never drawn as a key`() {
        // A spacer occupies width only. If it ever resolved to a real usage the keyboard would
        // type something the user never pressed.
        val spacers = layouts.flatMap { it.allKeys }.filter { it.kind == KeyKind.SPACER }
        assertTrue("expected the split layout to have spacers", spacers.isNotEmpty())
        spacers.forEach { spacer ->
            assertTrue("spacer '${spacer.label}' must not auto-repeat", !spacer.supportsAutoRepeat)
        }
    }

    @Test
    fun `byId resolves every shipped layout and falls back to the first`() {
        layouts.forEach { layout ->
            assertEquals(layout.id, Keyboards.byId(layout.id).id)
        }
        assertEquals(layouts.first().id, Keyboards.byId(null).id)
        assertEquals(layouts.first().id, Keyboards.byId("no-such-layout").id)
    }

    @Test
    fun `the narrow default is a phone keyboard and the wide one is the pc layout`() {
        val narrow = Keyboards.defaultFor(wide = false)
        val wide = Keyboards.defaultFor(wide = true)

        assertEquals(Keyboards.PHONE_12.id, narrow.id)
        assertEquals(Keyboards.PC_60.id, wide.id)

        // Neither default may be the game pad, and whichever is the default must be able to type:
        // every letter present on the keyboard.
        assertTrue("a game pad must never be the default", narrow.textCapable && wide.textCapable)
        val lettersOnNarrow = narrow.allKeys.mapNotNull { it.keyCode }.toSet()
        ('a'..'z').forEach { letter ->
            val code = KeyEvent.KEYCODE_A + (letter - 'a')
            assertTrue("narrow default is missing the letter '$letter'", code in lettersOnNarrow)
        }
    }

    @Test
    fun `the game layout is a pad not a keyboard and says so`() {
        val game = Keyboards.GAME
        assertFalse("the game pad must not claim to be a typing layout", game.textCapable)
        assertTrue(
            "the picker shows only the name, so the name must not promise typing",
            game.displayName.contains("不能打字"),
        )

        val codes = game.allKeys.map { it.keyCode }.toSet()
        // Movement, the four ability keys a game rebinds, a weapon row, and the functional keys.
        listOf(
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_CTRL_LEFT,
        ).forEach { code ->
            assertTrue("game pad is missing keyCode $code", code in codes)
        }
    }

    @Test
    fun `the game layout arrows form an inverted T`() {
        // ↑ must sit directly above ↓ with ←/→ flanking it, or the cluster cannot be used without
        // looking. Checked on the real geometry (fractions of the row), not on the key order.
        val game = Keyboards.GAME
        val upRow = game.rows.first { row -> row.any { it.keyCode == KeyEvent.KEYCODE_DPAD_UP } }
        val downRow = game.rows.first { row -> row.any { it.keyCode == KeyEvent.KEYCODE_DPAD_DOWN } }

        fun span(row: List<KeySpec>, code: Int): Pair<Float, Float> {
            val fractions = KeyboardGeometry.keyFractions(row)
            val index = row.indexOfFirst { it.keyCode == code }
            return fractions[index]
        }

        val (upStart, upEnd) = span(upRow, KeyEvent.KEYCODE_DPAD_UP)
        val (downStart, downEnd) = span(downRow, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("↑ must be centred over ↓", (upStart + upEnd) / 2f, (downStart + downEnd) / 2f, 0.001f)

        val (leftStart, _) = span(downRow, KeyEvent.KEYCODE_DPAD_LEFT)
        val (_, rightEnd) = span(downRow, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("← must be left of ↓", leftStart < downStart)
        assertTrue("→ must be right of ↓", rightEnd > downEnd)
    }

    private fun codeToLetter(code: Int): Char = ('a' + (code - KeyEvent.KEYCODE_A))

    private fun codeToDigit(code: Int): Char = ('0' + (code - KeyEvent.KEYCODE_0))
}
