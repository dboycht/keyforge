package com.dboycht.keyforge.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the mouse button state machine: which buttons are held, and
 * the report each operation puts on the wire. The invariant under test is the
 * same one `KeyboardState` guards: a down/up pair can never disagree about what
 * is held.
 */
class MouseStateTest {

    @Test
    fun `fresh state holds nothing`() {
        val mouse = MouseState()
        assertEquals(0, mouse.buttons)
        assertTrue(mouse.isIdle)
        assertTrue(mouse.releaseAll().isIdle)
    }

    @Test
    fun `press sets the bit and returns a button-only report`() {
        val mouse = MouseState()
        val report = mouse.press(MouseReport.Button.LEFT)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00), report.toByteArray())
        assertEquals(MouseReport.Button.LEFT, mouse.buttons)
    }

    @Test
    fun `release clears the bit and returns the remaining bitmap`() {
        val mouse = MouseState()
        mouse.press(MouseReport.Button.LEFT)
        mouse.press(MouseReport.Button.RIGHT)
        val report = mouse.release(MouseReport.Button.LEFT)
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x00, 0x00), report.toByteArray())
        assertEquals(MouseReport.Button.RIGHT, mouse.buttons)
    }

    @Test
    fun `pressing a held button is a no-op that keeps the bitmap`() {
        val mouse = MouseState()
        mouse.press(MouseReport.Button.LEFT)
        val report = mouse.press(MouseReport.Button.LEFT)
        assertEquals(MouseReport.Button.LEFT, report.buttons)
        assertEquals(MouseReport.Button.LEFT, mouse.buttons)
    }

    @Test
    fun `releasing a button that is not held is a no-op`() {
        val mouse = MouseState()
        val report = mouse.release(MouseReport.Button.RIGHT)
        assertTrue(report.isIdle)
        assertEquals(0, mouse.buttons)
    }

    @Test
    fun `move carries the held buttons with the delta`() {
        val mouse = MouseState()
        mouse.press(MouseReport.Button.LEFT)
        val report = mouse.move(x = 5, y = -5)
        assertArrayEquals(byteArrayOf(0x01, 0x05, 0xFB.toByte(), 0x00), report.toByteArray())
    }

    @Test
    fun `scroll carries the held buttons with the wheel delta`() {
        val mouse = MouseState()
        val report = mouse.scroll(wheel = 1)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01), report.toByteArray())
    }

    @Test
    fun `releaseAll clears every button and returns the idle report`() {
        val mouse = MouseState()
        mouse.press(MouseReport.Button.LEFT)
        mouse.press(MouseReport.Button.MIDDLE)
        val report = mouse.releaseAll()
        assertTrue(report.isIdle)
        assertEquals(0, mouse.buttons)
    }

    @Test
    fun `reset clears state without a report - session teardown path`() {
        val mouse = MouseState()
        mouse.press(MouseReport.Button.LEFT)
        mouse.reset()
        assertEquals(0, mouse.buttons)
    }

    @Test
    fun `combining two buttons keeps both bits`() {
        val mouse = MouseState()
        mouse.press(MouseReport.Button.LEFT)
        mouse.press(MouseReport.Button.RIGHT)
        assertEquals(0x03, mouse.buttons)
        val report = mouse.move(1, 1)
        assertEquals(0x03, report.buttons)
    }
}
