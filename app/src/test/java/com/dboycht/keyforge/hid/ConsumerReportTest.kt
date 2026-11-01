package com.dboycht.keyforge.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the consumer-page report (Report ID 3 body): one 16-bit usage
 * code, little endian, zero = "no key held".
 */
class ConsumerReportTest {

    // ---------- report layout ----------

    @Test
    fun `idle report is two zero bytes`() {
        val report = ConsumerReport.idle()
        assertEquals(2, report.size)
        assertArrayEquals(ByteArray(2), report.toByteArray())
        assertTrue(report.isIdle)
        assertEquals(0, report.code)
    }

    @Test
    fun `usage code is little endian`() {
        val report = ConsumerReport.of(0xE9)
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x00), report.toByteArray())
        assertEquals(0xE9, report.code)
    }

    @Test
    fun `ten bit codes span both bytes`() {
        val report = ConsumerReport.of(0x3FF)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x03), report.toByteArray())
        assertEquals(0x3FF, report.code)
        assertFalse(report.isIdle)
    }

    @Test
    fun `codes above the descriptor range are a caller bug and throw`() {
        try {
            ConsumerReport.of(0x400)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // silent truncation would send a different media key than labelled
        }
        try {
            ConsumerReport.of(-1)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `equality is by content`() {
        assertEquals(ConsumerReport.of(0xE2), ConsumerReport.of(0xE2))
        assertFalse(ConsumerReport.of(0xE2).equals(ConsumerReport.of(0xEA)))
    }

    // ---------- usage codes ----------

    @Test
    fun `media key codes match the USB HID consumer page`() {
        assertEquals(0xE2, ConsumerUsage.MUTE)
        assertEquals(0xE9, ConsumerUsage.VOLUME_UP)
        assertEquals(0xEA, ConsumerUsage.VOLUME_DOWN)
        assertEquals(0xCD, ConsumerUsage.PLAY_PAUSE)
        assertEquals(0xB6, ConsumerUsage.SCAN_PREVIOUS_TRACK)
        assertEquals(0xB5, ConsumerUsage.SCAN_NEXT_TRACK)
    }

    @Test
    fun `every shipped usage code fits the descriptor range and has a name`() {
        val shipped = listOf(
            ConsumerUsage.MUTE,
            ConsumerUsage.VOLUME_UP,
            ConsumerUsage.VOLUME_DOWN,
            ConsumerUsage.PLAY_PAUSE,
            ConsumerUsage.SCAN_PREVIOUS_TRACK,
            ConsumerUsage.SCAN_NEXT_TRACK,
        )
        for (code in shipped) {
            ConsumerReport.of(code) // must not throw
            assertFalse("missing name for 0x%03X".format(code), ConsumerUsage.nameFor(code).startsWith("Unknown"))
        }
    }

    @Test
    fun `unknown codes still get a diagnostic name`() {
        assertTrue(ConsumerUsage.nameFor(0x123).startsWith("Unknown"))
    }
}
