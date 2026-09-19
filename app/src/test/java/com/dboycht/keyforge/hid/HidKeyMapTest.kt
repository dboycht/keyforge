package com.dboycht.keyforge.hid

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Android-key-code -> HID usage-ID table.
 *
 * These run on the JVM (`gradlew testDebugUnitTest`); [KeyEvent] is only used for
 * its integer constants, which is why this works without an Android runtime.
 */
class HidKeyMapTest {

    @Test
    fun `letters follow the HID alphabet block`() {
        assertEquals(0x04, HidKeyMap.usageFor(KeyEvent.KEYCODE_A))
        assertEquals(0x05, HidKeyMap.usageFor(KeyEvent.KEYCODE_B))
        assertEquals(0x1C, HidKeyMap.usageFor(KeyEvent.KEYCODE_Y))
        assertEquals(0x1D, HidKeyMap.usageFor(KeyEvent.KEYCODE_Z))
    }

    @Test
    fun `digit row is one through nine then zero`() {
        assertEquals(0x1E, HidKeyMap.usageFor(KeyEvent.KEYCODE_1))
        assertEquals(0x1F, HidKeyMap.usageFor(KeyEvent.KEYCODE_2))
        assertEquals(0x26, HidKeyMap.usageFor(KeyEvent.KEYCODE_9))
        assertEquals(0x27, HidKeyMap.usageFor(KeyEvent.KEYCODE_0))
    }

    @Test
    fun `editing keys use their documented usage ids`() {
        assertEquals(0x28, HidKeyMap.usageFor(KeyEvent.KEYCODE_ENTER))
        assertEquals(0x29, HidKeyMap.usageFor(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(0x2A, HidKeyMap.usageFor(KeyEvent.KEYCODE_DEL))        // backspace
        assertEquals(0x2B, HidKeyMap.usageFor(KeyEvent.KEYCODE_TAB))
        assertEquals(0x2C, HidKeyMap.usageFor(KeyEvent.KEYCODE_SPACE))
        assertEquals(0x39, HidKeyMap.usageFor(KeyEvent.KEYCODE_CAPS_LOCK))
    }

    @Test
    fun `punctuation uses the US layout positions`() {
        assertEquals(0x2D, HidKeyMap.usageFor(KeyEvent.KEYCODE_MINUS))
        assertEquals(0x2E, HidKeyMap.usageFor(KeyEvent.KEYCODE_EQUALS))
        assertEquals(0x2F, HidKeyMap.usageFor(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals(0x30, HidKeyMap.usageFor(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(0x31, HidKeyMap.usageFor(KeyEvent.KEYCODE_BACKSLASH))
        assertEquals(0x33, HidKeyMap.usageFor(KeyEvent.KEYCODE_SEMICOLON))
        assertEquals(0x34, HidKeyMap.usageFor(KeyEvent.KEYCODE_APOSTROPHE))
        assertEquals(0x35, HidKeyMap.usageFor(KeyEvent.KEYCODE_GRAVE))
        assertEquals(0x36, HidKeyMap.usageFor(KeyEvent.KEYCODE_COMMA))
        assertEquals(0x37, HidKeyMap.usageFor(KeyEvent.KEYCODE_PERIOD))
        assertEquals(0x38, HidKeyMap.usageFor(KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun `function row is contiguous from F1`() {
        for (i in 0..11) {
            assertEquals("F${i + 1}", 0x3A + i, HidKeyMap.usageFor(KeyEvent.KEYCODE_F1 + i))
        }
    }

    @Test
    fun `navigation cluster`() {
        assertEquals(0x49, HidKeyMap.usageFor(KeyEvent.KEYCODE_INSERT))
        assertEquals(0x4A, HidKeyMap.usageFor(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(0x4B, HidKeyMap.usageFor(KeyEvent.KEYCODE_PAGE_UP))
        // KEYCODE_FORWARD_DEL is the forward delete; KEYCODE_DEL is backspace.
        assertEquals(0x4C, HidKeyMap.usageFor(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(0x4D, HidKeyMap.usageFor(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(0x4E, HidKeyMap.usageFor(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(0x4F, HidKeyMap.usageFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0x50, HidKeyMap.usageFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(0x51, HidKeyMap.usageFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(0x52, HidKeyMap.usageFor(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `modifier key codes map to modifier usage ids and bits`() {
        // Regression: these used to return the *bit* (0x01/0x02/0x04...) where a
        // *usage* was expected, so pressing Ctrl produced a report that pressed 'a'
        // (0x04). Usage and bit are different numbers and must not be conflated.
        val expected = mapOf(
            KeyEvent.KEYCODE_CTRL_LEFT to (0xE0 to HidReport.Modifier.LEFT_CTRL),
            KeyEvent.KEYCODE_SHIFT_LEFT to (0xE1 to HidReport.Modifier.LEFT_SHIFT),
            KeyEvent.KEYCODE_ALT_LEFT to (0xE2 to HidReport.Modifier.LEFT_ALT),
            KeyEvent.KEYCODE_META_LEFT to (0xE3 to HidReport.Modifier.LEFT_GUI),
            KeyEvent.KEYCODE_CTRL_RIGHT to (0xE4 to HidReport.Modifier.RIGHT_CTRL),
            KeyEvent.KEYCODE_SHIFT_RIGHT to (0xE5 to HidReport.Modifier.RIGHT_SHIFT),
            KeyEvent.KEYCODE_ALT_RIGHT to (0xE6 to HidReport.Modifier.RIGHT_ALT),
            KeyEvent.KEYCODE_META_RIGHT to (0xE7 to HidReport.Modifier.RIGHT_GUI),
        )
        expected.forEach { (code, pair) ->
            val (usage, bit) = pair
            assertTrue("$code must be a modifier", HidKeyMap.isModifierKeyCode(code))
            assertEquals("usage for $code", usage, HidKeyMap.usageFor(code))
            assertEquals("usage for $code", usage, HidKeyMap.modifierUsageFor(code))
            assertEquals("bit for 0x%02X".format(usage), bit, HidReport.Modifier.bitForUsage(usage))
            assertEquals("usage back from bit", usage, HidReport.Modifier.usageForBit(bit))
        }
        assertFalse(HidKeyMap.isModifierKeyCode(KeyEvent.KEYCODE_A))
        assertNull(HidKeyMap.modifierUsageFor(KeyEvent.KEYCODE_A))
    }

    /** Ctrl must never resolve to the usage of the letter 'a'. */
    @Test
    fun `modifier bits are not mistaken for usage ids`() {
        val ctrlUsage = HidKeyMap.usageFor(KeyEvent.KEYCODE_CTRL_LEFT)!!
        assertEquals(0xE0, ctrlUsage)
        assertNotEquals("Ctrl must not match 'a'", HidKeyMap.usageFor(KeyEvent.KEYCODE_A), ctrlUsage)
        listOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_RIGHT,
        ).forEach { code ->
            val usage = HidKeyMap.usageFor(code)!!
            assertTrue("$code -> 0x%02X must be in the modifier usage block".format(usage), usage in 0xE0..0xE7)
        }
    }

    @Test
    fun `unknown keys return null rather than a wrong usage id`() {
        assertNull(HidKeyMap.usageFor(KeyEvent.KEYCODE_UNKNOWN))
        assertNull(HidKeyMap.usageFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(HidKeyMap.usageFor(KeyEvent.KEYCODE_CAMERA))
        assertNull(HidKeyMap.usageFor(-1))
    }

    @Test
    fun `table contract - every mapping is a valid, unique usage id`() {
        // Structural guard instead of a hard-coded table size: a size literal breaks
        // on every legitimate addition and proves nothing about correctness.
        val expectedCodes = buildList {
            addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
            addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
            addAll(KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12)
            add(KeyEvent.KEYCODE_CTRL_LEFT)
            add(KeyEvent.KEYCODE_SHIFT_LEFT)
            add(KeyEvent.KEYCODE_ALT_LEFT)
            add(KeyEvent.KEYCODE_META_LEFT)
            add(KeyEvent.KEYCODE_CTRL_RIGHT)
            add(KeyEvent.KEYCODE_SHIFT_RIGHT)
            add(KeyEvent.KEYCODE_ALT_RIGHT)
            add(KeyEvent.KEYCODE_META_RIGHT)
            add(KeyEvent.KEYCODE_ENTER)
            add(KeyEvent.KEYCODE_ESCAPE)
            add(KeyEvent.KEYCODE_DEL)
            add(KeyEvent.KEYCODE_TAB)
            add(KeyEvent.KEYCODE_SPACE)
            add(KeyEvent.KEYCODE_FORWARD_DEL)
        }

        val missing = expectedCodes.filter { HidKeyMap.usageFor(it) == null }
        assertTrue("these key codes must be mapped: $missing", missing.isEmpty())

        // Every key code must resolve to exactly one usage (a map can not hold two
        // values per key, but this catches an accidental overwrite expectation).
        val byUsage = HashMap<Int, Int>()
        expectedCodes.forEach { code ->
            val usage = HidKeyMap.usageFor(code)!!
            val previous = byUsage.put(usage, code)
            // Two DIFFERENT key codes legitimately share one usage: KEYCODE_0 and
            // KEYCODE_NUMPAD_0 are both HID '0' (0x27). Only flag the same code twice.
            if (previous != null) {
                assertEquals("key code $code mapped twice", code, previous)
            }
        }

        // Every value must be a real usage ID, and non-modifiers must not use the
        // 0xE0..0xE7 modifier block.
        expectedCodes.forEach { code ->
            val usage = HidKeyMap.usageFor(code)!!
            assertTrue("usage out of byte range: $usage", usage in 0x00..0xFF)
            if (usage in 0xE0..0xE7) {
                assertTrue(
                    "only modifier keys may map to 0xE0..0xE7, but $code does",
                    code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_SHIFT_LEFT ||
                        code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_META_LEFT ||
                        code == KeyEvent.KEYCODE_CTRL_RIGHT || code == KeyEvent.KEYCODE_SHIFT_RIGHT ||
                        code == KeyEvent.KEYCODE_ALT_RIGHT || code == KeyEvent.KEYCODE_META_RIGHT,
                )
            }
        }
    }
}
