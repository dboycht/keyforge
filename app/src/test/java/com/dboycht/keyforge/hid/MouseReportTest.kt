package com.dboycht.keyforge.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the mouse report layout (Report ID 2 body): button bitmap,
 * relative X/Y and wheel, each movement an int8.
 *
 * This is the half that can be proven on the JVM: it checks the exact bytes
 * handed to `BluetoothHidDevice.sendReport`. Whether the host accepts them is a
 * separate, on-device question.
 */
class MouseReportTest {

    // ---------- report layout ----------

    @Test
    fun `idle report is four zero bytes`() {
        val report = MouseReport.idle()
        assertEquals(4, report.size)
        assertArrayEquals(ByteArray(4), report.toByteArray())
        assertTrue(report.isIdle)
    }

    @Test
    fun `report layout is buttons then x y wheel`() {
        val report = MouseReport.of(buttons = 0x01, x = -3, y = 7, wheel = -1)
        assertArrayEquals(byteArrayOf(0x01, 0xFD.toByte(), 0x07, 0xFF.toByte()), report.toByteArray())
        assertEquals(0x01, report.buttons)
        assertEquals(-3, report.x)
        assertEquals(7, report.y)
        assertEquals(-1, report.wheel)
        assertFalse(report.isIdle)
    }

    @Test
    fun `button-only helper leaves no movement`() {
        val report = MouseReport.ofButtons(MouseReport.Button.RIGHT or MouseReport.Button.MIDDLE)
        assertArrayEquals(byteArrayOf(0x06, 0x00, 0x00, 0x00), report.toByteArray())
    }

    @Test
    fun `movement values clamp into the int8 range instead of wrapping`() {
        val report = MouseReport.of(0, x = 200, y = -200, wheel = 1000)
        assertEquals(127, report.x)
        assertEquals(-127, report.y)
        assertEquals(127, report.wheel)
    }

    @Test
    fun `extreme int8 values survive the round trip`() {
        val report = MouseReport.of(0, x = -127, y = 127, wheel = 0)
        assertEquals(-127, report.x)
        assertEquals(127, report.y)
    }

    @Test
    fun `equality is by content`() {
        assertEquals(MouseReport.of(0x01, 1, 2, 0), MouseReport.of(0x01, 1, 2, 0))
        assertNotEquals(MouseReport.of(0x01, 1, 2, 0), MouseReport.of(0x01, 1, 3, 0))
        assertNotEquals(HidReport.idle(), MouseReport.idle())
    }

    @Test
    fun `toByteArray is a copy - caller cannot mutate the report`() {
        val report = MouseReport.ofButtons(0x01)
        report.toByteArray()[0] = 0x7F
        assertEquals(0x01, report.buttons)
    }

    // ---------- button bitmap ----------

    @Test
    fun `button bits are left right middle`() {
        assertEquals(0x01, MouseReport.Button.LEFT)
        assertEquals(0x02, MouseReport.Button.RIGHT)
        assertEquals(0x04, MouseReport.Button.MIDDLE)
    }

    @Test
    fun `only single known button bits are valid`() {
        assertTrue(MouseReport.Button.isValid(MouseReport.Button.LEFT))
        assertTrue(MouseReport.Button.isValid(MouseReport.Button.RIGHT))
        assertTrue(MouseReport.Button.isValid(MouseReport.Button.MIDDLE))
        assertFalse(MouseReport.Button.isValid(0))
        assertFalse(MouseReport.Button.isValid(MouseReport.Button.LEFT or MouseReport.Button.RIGHT))
        assertFalse(MouseReport.Button.isValid(0x08))
    }

    @Test
    fun `out of range button bitmap is a caller bug and throws`() {
        try {
            MouseReport.of(buttons = 0x100, x = 0, y = 0, wheel = 0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // an over-wide bitmap would silently corrupt other report fields
        }
    }

    @Test
    fun `button names are human readable`() {
        assertEquals("Left", MouseReport.Button.nameFor(MouseReport.Button.LEFT))
        assertEquals("Right", MouseReport.Button.nameFor(MouseReport.Button.RIGHT))
        assertEquals("Middle", MouseReport.Button.nameFor(MouseReport.Button.MIDDLE))
        assertTrue(MouseReport.Button.nameFor(0x09).startsWith("Unknown"))
    }
}
