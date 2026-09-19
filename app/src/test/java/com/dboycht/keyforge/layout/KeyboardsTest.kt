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
    fun `there are two layouts with distinct ids and non-empty names`() {
        assertEquals(2, layouts.size)
        assertEquals(layouts.size, layouts.map { it.id }.toSet().size)
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
    fun `phone layout is ten key units wide`() {
        assertEquals(10.0, Keyboards.PHONE_STYLE.widthUnits.toDouble(), 0.001)
        Keyboards.PHONE_STYLE.rows.forEach { row ->
            assertEquals(10.0, row.sumOf { it.widthUnits.toDouble() }, 0.001)
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
        assertEquals(Keyboards.PHONE_STYLE, Keyboards.byId("phone"))
        assertEquals(Keyboards.PC_60, Keyboards.byId("pc60"))
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
    fun `phone layout has exactly one shift key`() {
        // Regression: an earlier revision shipped two Shift keys (one per letter row),
        // which is confusing to use and was visible in the on-device screenshot.
        val shifts = Keyboards.PHONE_STYLE.allKeys.count { it.label == "Shift" }
        assertEquals(1, shifts)
    }

    @Test
    fun `every layout row shows at most twelve keys so labels stay readable`() {
        // A data-level guard for "don't cram": the phone screen is ~2412px wide in
        // landscape, and a 15-key row is only fine for the 60% layout.
        Keyboards.PHONE_STYLE.rows.forEachIndexed { index, row ->
            assertTrue("phone row $index has ${row.size} keys", row.size <= 12)
        }
    }

    @Test
    fun `editing and navigation keys support auto repeat, letters do not`() {
        // Holding Backspace must delete continuously (user feedback), while holding a
        // letter must not spray characters.
        val repeatable = listOf(
            android.view.KeyEvent.KEYCODE_DEL,
            android.view.KeyEvent.KEYCODE_FORWARD_DEL,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_SPACE,
        )
        repeatable.forEach { code ->
            val key = KeySpec("x", code)
            assertTrue("keyCode $code should auto-repeat", key.supportsAutoRepeat)
        }

        val notRepeatable = listOf(
            android.view.KeyEvent.KEYCODE_A,
            android.view.KeyEvent.KEYCODE_Z,
            android.view.KeyEvent.KEYCODE_1,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_TAB,
            android.view.KeyEvent.KEYCODE_SEMICOLON,
        )
        notRepeatable.forEach { code ->
            val key = KeySpec("x", code)
            assertFalse("keyCode $code must not auto-repeat", key.supportsAutoRepeat)
        }
    }

    @Test
    fun `the 60 percent layout has a backspace that can auto repeat`() {
        val backspace = Keyboards.PC_60.allKeys.first { it.label == "Bksp" }
        assertTrue("Bksp must auto-repeat", backspace.supportsAutoRepeat)
    }
}
