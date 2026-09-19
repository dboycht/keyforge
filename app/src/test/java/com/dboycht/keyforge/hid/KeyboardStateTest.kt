package com.dboycht.keyforge.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the report layout and the modifier state machine.
 *
 * This is the half of the keyboard that can be proven on the JVM: it checks the
 * exact bytes handed to `BluetoothHidDevice.sendReport`. Whether the host accepts
 * them is a separate, on-device question.
 */
class KeyboardStateTest {

    // ---------- report layout ----------

    @Test
    fun `idle report is eight zero bytes`() {
        val report = HidReport.idle()
        assertEquals(8, report.size)
        assertArrayEquals(ByteArray(8), report.toByteArray())
        assertTrue(report.isIdle)
    }

    @Test
    fun `report layout is modifier reserved then six key slots`() {
        val report = HidReport.of(modifiers = 0x02, keySlots = listOf(0x04))
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00),
            report.toByteArray(),
        )
        assertEquals(0x02, report.modifiers)
        assertEquals(listOf(0x04), report.keys)
    }

    @Test
    fun `more than six keys are truncated to the report capacity`() {
        val report = HidReport.of(0, listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B))
        assertEquals(6, report.keys.size)
        assertEquals(listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09), report.keys)
    }

    @Test
    fun `modifier bits combine`() {
        val both = HidReport.Modifier.LEFT_CTRL or HidReport.Modifier.LEFT_SHIFT
        val report = HidReport.of(both, listOf(0x04))
        assertEquals(0x03, report.modifiers)
    }

    @Test
    fun `equality is by content`() {
        assertEquals(HidReport.of(0x01, listOf(0x04)), HidReport.of(0x01, listOf(0x04)))
        assertNotEquals(HidReport.of(0x01, listOf(0x04)), HidReport.of(0x01, listOf(0x05)))
    }

    // ---------- modifier usage <-> bit ----------

    @Test
    fun `modifier usages map to their bit and back`() {
        val expected = mapOf(
            0xE0 to HidReport.Modifier.LEFT_CTRL,
            0xE1 to HidReport.Modifier.LEFT_SHIFT,
            0xE2 to HidReport.Modifier.LEFT_ALT,
            0xE3 to HidReport.Modifier.LEFT_GUI,
            0xE4 to HidReport.Modifier.RIGHT_CTRL,
            0xE5 to HidReport.Modifier.RIGHT_SHIFT,
            0xE6 to HidReport.Modifier.RIGHT_ALT,
            0xE7 to HidReport.Modifier.RIGHT_GUI,
        )
        expected.forEach { (usage, bit) ->
            assertTrue("usage $usage", HidReport.Modifier.isModifierUsage(usage))
            assertEquals("usage $usage", bit, HidReport.Modifier.bitForUsage(usage))
        }
        assertFalse(HidReport.Modifier.isModifierUsage(0x04))
        assertEquals(0, HidReport.Modifier.bitForUsage(0x04))
    }

    // ---------- state machine ----------

    @Test
    fun `press then release returns to idle`() {
        val state = KeyboardState()
        assertTrue(state.isIdle)

        val down = state.press(0x04)                    // 'a'
        assertTrue(down.accepted)
        assertFalse(down.dropped)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00),
            down.report.toByteArray(),
        )

        val up = state.release(0x04)
        assertTrue(up.report.isIdle)
        assertTrue(state.isIdle)
    }

    @Test
    fun `modifiers set the bit without occupying a key slot`() {
        val state = KeyboardState()
        val shift = state.press(0xE1)                   // left shift
        assertEquals(0x02, shift.report.modifiers)
        assertTrue(shift.report.keys.isEmpty())

        val a = state.press(0x04)
        assertEquals(0x02, a.report.modifiers)
        assertEquals(listOf(0x04), a.report.keys)

        // Shift released first, then the key: the key must survive.
        state.release(0xE1)
        assertEquals(0x00, state.report().modifiers)
        assertEquals(listOf(0x04), state.report().keys)
    }

    @Test
    fun `key repeat is idempotent`() {
        val state = KeyboardState()
        state.press(0x04)
        val again = state.press(0x04)
        assertTrue(again.accepted)
        assertFalse(again.dropped)
        assertEquals(listOf(0x04), again.report.keys)
        assertEquals(1, state.keys.size)
    }

    @Test
    fun `seventh simultaneous key is dropped and reported`() {
        val state = KeyboardState()
        (0x04..0x09).forEach { assertTrue("press $it", state.press(it).accepted) }

        val overflow = state.press(0x0A)
        assertFalse("must not be accepted", overflow.accepted)
        assertTrue("must be reported as dropped", overflow.dropped)
        assertTrue(overflow.reason!!.contains("rollover"))
        // The report still describes the six keys that are really held.
        assertEquals(6, overflow.report.keys.size)
        assertEquals(listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09), overflow.report.keys)
    }

    @Test
    fun `releasing a key frees a slot for the next press`() {
        val state = KeyboardState()
        (0x04..0x09).forEach { state.press(it) }
        state.release(0x06)
        val accepted = state.press(0x0A)
        assertTrue(accepted.accepted)
        assertFalse(accepted.dropped)
        assertEquals(listOf(0x04, 0x05, 0x07, 0x08, 0x09, 0x0A), accepted.report.keys)
    }

    @Test
    fun `release of a key that is not held is harmless`() {
        val state = KeyboardState()
        val result = state.release(0x04)
        assertTrue(result.accepted)
        assertTrue(result.report.isIdle)
        assertEquals("was not held", result.reason)
    }

    @Test
    fun `tap emits down report then up report`() {
        val state = KeyboardState()
        val reports = state.tap(0x04)
        assertEquals(2, reports.size)
        assertEquals(listOf(0x04), reports[0].keys)
        assertTrue(reports[1].isIdle)
        assertTrue(state.isIdle)
    }

    @Test
    fun `tap of a modifier does not leave it stuck`() {
        val state = KeyboardState()
        val reports = state.tap(0xE1)                   // left shift
        assertEquals(0x02, reports[0].modifiers)
        assertEquals(0x00, reports[1].modifiers)
        assertTrue(state.isIdle)
    }

    @Test
    fun `releaseAll clears modifiers and keys at once`() {
        val state = KeyboardState()
        state.press(0xE1)
        state.press(0xE0)
        state.press(0x04)
        assertFalse(state.isIdle)

        val cleared = state.releaseAll()
        assertTrue(cleared.isIdle)
        assertTrue(state.isIdle)
    }

    @Test
    fun `press order is preserved in the slots`() {
        val state = KeyboardState()
        state.press(0x07)
        state.press(0x04)
        state.press(0x05)
        assertEquals(listOf(0x07, 0x04, 0x05), state.report().keys)
    }
}
