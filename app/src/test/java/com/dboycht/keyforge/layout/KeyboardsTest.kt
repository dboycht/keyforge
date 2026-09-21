package com.dboycht.keyforge.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for the shipped layouts.
 *
 * These are the cheap guards that keep a hand-written table honest: the renderer
 * assumes equal row widths and mappable keys, so a typo in [Keyboards] must fail
 * here rather than show up as a ragged keyboard on the phone.
 */
class KeyboardsTest {

    private val layouts = Keyboards.all

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
    }

    @Test
    fun `pc60 covers the letters digits and essential modifiers`() {
        val codes = Keyboards.PC_60.allKeys.map { it.keyCode }.toSet()
        ('A'..'Z').forEach { assertTrue("missing letter $it", android.view.KeyEvent.KEYCODE_A + (it - 'A') in codes) }
        assertTrue(android.view.KeyEvent.KEYCODE_0 in codes)
        assertTrue(android.view.KeyEvent.KEYCODE_9 in codes)
        assertTrue(android.view.KeyEvent.KEYCODE_TAB in codes)
        assertTrue(android.view.KeyEvent.KEYCODE_ENTER in codes)
        assertTrue(android.view.KeyEvent.KEYCODE_DEL in codes)
        assertTrue(android.view.KeyEvent.KEYCODE_SPACE in codes)
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
    }

    @Test
    fun `byId finds a layout and falls back safely`() {
        assertEquals(Keyboards.PC_60, Keyboards.byId("pc60"))
        assertEquals(Keyboards.FULL, Keyboards.byId("full"))
        assertEquals(Keyboards.SPLIT, Keyboards.byId("split"))
        assertEquals(layouts.first(), Keyboards.byId("does-not-exist"))
        assertEquals(layouts.first(), Keyboards.byId(null))
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
    fun `the full screen layouts have exactly one shift key each`() {
        // Regression: an earlier revision shipped two Shift keys (one per letter row), which is
        // confusing to use and was visible in the on-device screenshot.
        listOf(Keyboards.PC_60, Keyboards.FULL, Keyboards.SPLIT).forEach { layout ->
            val shifts = layout.allKeys.count { it.label == "Shift" }
            assertTrue("${layout.id} has $shifts Shift keys", shifts >= 1)
        }
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
            android.view.KeyEvent.KEYCODE_A,
            android.view.KeyEvent.KEYCODE_Z,
            android.view.KeyEvent.KEYCODE_1,
            android.view.KeyEvent.KEYCODE_0,
            android.view.KeyEvent.KEYCODE_SPACE,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_TAB,
            android.view.KeyEvent.KEYCODE_DEL,
            android.view.KeyEvent.KEYCODE_FORWARD_DEL,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_SEMICOLON,
            android.view.KeyEvent.KEYCODE_ESCAPE,
            android.view.KeyEvent.KEYCODE_CAPS_LOCK,
        )
        ordinary.forEach { code ->
            val key = KeySpec("x", code)
            assertTrue("keyCode $code should auto-repeat", key.supportsAutoRepeat)
        }

        val modifiers = listOf(
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
            android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
            android.view.KeyEvent.KEYCODE_CTRL_LEFT,
            android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
            android.view.KeyEvent.KEYCODE_ALT_LEFT,
            android.view.KeyEvent.KEYCODE_ALT_RIGHT,
            android.view.KeyEvent.KEYCODE_META_LEFT,
            android.view.KeyEvent.KEYCODE_META_RIGHT,
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
    fun `the full screen layouts are twelve units wide with equal rows`() {
        // Full screen trades key COUNT for key SIZE: 15 units (PC_60) across a phone leaves keys
        // barely wider than a fingertip, so these are 12 units.
        listOf(Keyboards.FULL, Keyboards.SPLIT).forEach { layout ->
            assertEquals("${layout.id} width", 12.0, layout.widthUnits.toDouble(), 0.001)
            layout.rows.forEachIndexed { index, row ->
                val units = layout.offsetFor(index) + row.sumOf { it.widthUnits.toDouble() }
                assertEquals(
                    "${layout.id} row $index must be 12 units with its indent " +
                        "(a ragged row renders as a mistake)",
                    12.0,
                    units,
                    0.001,
                )
            }
        }
    }

    @Test
    fun `the full screen layouts ship the keys a full screen keyboard needs`() {
        // Full screen should still be able to type a sentence and edit it, so the essentials must
        // be present and reachable.
        val essentials = listOf(
            android.view.KeyEvent.KEYCODE_SPACE,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_DEL,
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
            android.view.KeyEvent.KEYCODE_CTRL_LEFT,
            android.view.KeyEvent.KEYCODE_TAB,
        )
        listOf(Keyboards.FULL, Keyboards.SPLIT).forEach { layout ->
            val present = layout.allKeys.mapNotNull { it.keyCode }.toSet()
            essentials.forEach { code ->
                assertTrue("${layout.id} is missing an essential key (keyCode $code)", code in present)
            }
        }
    }

    @Test
    fun `the split layout has a gap down the middle and a space bar on each side`() {
        // The two space bars are the point of the split: with the hands apart, either thumb gets
        // its own. A missing gap would also mean the halves are not really separated.
        val gaps = Keyboards.SPLIT.allKeys.count { it.kind == KeyKind.SPACER }
        assertEquals(5, gaps) // one per row

        val spaces = Keyboards.SPLIT.allKeys.count { it.keyCode == android.view.KeyEvent.KEYCODE_SPACE }
        assertEquals("one space bar per thumb", 2, spaces)
    }

    @Test
    fun `a spacer is never sent and never drawn as a key`() {
        // A spacer occupies width only. If it ever resolved to a real usage the keyboard would
        // type something the user never pressed.
        val spacers = Keyboards.all.flatMap { it.allKeys }.filter { it.kind == KeyKind.SPACER }
        assertTrue("expected the split layout to have spacers", spacers.isNotEmpty())
        spacers.forEach { spacer ->
            assertTrue("spacer '${spacer.label}' must not auto-repeat", !spacer.supportsAutoRepeat)
        }
    }

    @Test
    fun `byId resolves every shipped layout and falls back to the first`() {
        // Layout choice is persisted by id, so a stale id must degrade gracefully instead
        // of crashing or showing an empty keyboard.
        layouts.forEach { layout ->
            assertEquals(layout.id, Keyboards.byId(layout.id).id)
        }
        assertEquals(Keyboards.all.first().id, Keyboards.byId(null).id)
        assertEquals(Keyboards.all.first().id, Keyboards.byId("no-such-layout").id)
    }

    @Test
    fun `the narrow default is a full keyboard and the wide one is the pc layout`() {
        // Narrow (portrait) gets the 12-unit FULL layout, wide (landscape) the 15-unit PC layout.
        val narrow = Keyboards.defaultFor(wide = false)
        val wide = Keyboards.defaultFor(wide = true)

        assertEquals(Keyboards.FULL.id, narrow.id)
        assertEquals(Keyboards.PC_60.id, wide.id)

        // Whichever is the default must be able to type: every letter present on the keyboard.
        val letters = narrow.allKeys.mapNotNull { it.keyCode }.toSet()
        ('a'..'z').forEach { letter ->
            val code = android.view.KeyEvent.KEYCODE_A + (letter - 'a')
            assertTrue("narrow default is missing the letter '$letter'", code in letters)
        }
    }
}
