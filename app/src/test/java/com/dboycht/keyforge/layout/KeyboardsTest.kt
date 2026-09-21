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
        // Full screen trades key COUNT for key SIZE: 15 units (PC_60) across a phone in
        // landscape leaves keys barely wider than a fingertip, so these are 12 units.
        listOf(Keyboards.FULL, Keyboards.FULL_COMPACT).forEach { layout ->
            assertEquals("${layout.id} width", 12.0, layout.widthUnits.toDouble(), 0.001)
            layout.rows.forEachIndexed { index, row ->
                val units = row.sumOf { it.widthUnits.toDouble() }
                assertEquals(
                    "${layout.id} row $index must be 12 units (a ragged row renders as a mistake)",
                    12.0,
                    units,
                    0.001,
                )
            }
        }
    }

    @Test
    fun `the full screen layouts ship the keys a full screen keyboard needs`() {
        // The point of these two: full screen should still be able to type a sentence and
        // edit it, so the essentials must be present and reachable.
        val essentials = listOf(
            android.view.KeyEvent.KEYCODE_SPACE,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_DEL,
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
            android.view.KeyEvent.KEYCODE_CTRL_LEFT,
            android.view.KeyEvent.KEYCODE_TAB,
        )
        listOf(Keyboards.FULL, Keyboards.FULL_COMPACT).forEach { layout ->
            val present = layout.allKeys.mapNotNull { it.keyCode }.toSet()
            essentials.forEach { code ->
                assertTrue("${layout.id} is missing an essential key (keyCode $code)", code in present)
            }
        }
    }

    @Test
    fun `full compact drops the number row so the keys can be taller`() {
        assertEquals(5, Keyboards.FULL.rows.size)
        assertEquals(4, Keyboards.FULL_COMPACT.rows.size)
        assertTrue(
            "the compact layout must not carry the number row",
            Keyboards.FULL_COMPACT.allKeys.none { it.keyCode == android.view.KeyEvent.KEYCODE_1 },
        )
        // Fewer rows at the same width and height means taller keys - that is the whole
        // reason the compact variant exists.
        assertTrue(Keyboards.FULL_COMPACT.rows.size < Keyboards.FULL.rows.size)
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
}
