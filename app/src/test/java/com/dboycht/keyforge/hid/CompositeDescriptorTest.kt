package com.dboycht.keyforge.hid

import com.dboycht.keyforge.probe.ProbeConstants
import com.dboycht.keyforge.session.HidSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the composite report descriptor.
 *
 * Two failure modes this test exists for:
 * 1. an accidental byte edit changes what every host parses (the full pin);
 * 2. the descriptor's Report IDs and `HidSession`'s send IDs drift apart -
 *    before T7 that sync rested on comments alone, and a silent mismatch would
 *    make every host ignore the reports with no error anywhere.
 */
class CompositeDescriptorTest {

    private val expected = byteArrayOf(
        // ---- Report ID 1: keyboard ----
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
        0x85.toByte(), 0x01,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
        0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0x95.toByte(), 0x05, 0x75, 0x01,
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01,
        0xC0.toByte(),
        // ---- Report ID 2: mouse ----
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
        0x85.toByte(), 0x02,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x06,
        0xC0.toByte(),
        0xC0.toByte(),
        // ---- Report ID 3: consumer ----
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01,
        0x85.toByte(), 0x03,
        0x19, 0x00, 0x2A.toByte(), 0xFF.toByte(), 0x03,
        0x15, 0x00, 0x26.toByte(), 0xFF.toByte(), 0x03,
        0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00,
        0xC0.toByte(),
    )

    @Test
    fun `composite descriptor is pinned byte for byte`() {
        assertArrayEquals(expected, ProbeConstants.REPORT_DESCRIPTOR)
    }

    @Test
    fun `descriptor declares exactly three report ids matching the session constants`() {
        val ids = reportIds(ProbeConstants.REPORT_DESCRIPTOR)
        assertEquals(
            listOf(HidSession.REPORT_ID_KEYBOARD, HidSession.REPORT_ID_MOUSE, HidSession.REPORT_ID_CONSUMER),
            ids,
        )
    }

    @Test
    fun `no report id is zero - zero is reserved by the HID spec`() {
        val ids = reportIds(ProbeConstants.REPORT_DESCRIPTOR)
        assertTrue("report ids must not contain 0: $ids", ids.none { it == 0 })
    }

    @Test
    fun `descriptor contains one keyboard one mouse and one consumer top level collection`() {
        val descriptor = ProbeConstants.REPORT_DESCRIPTOR
        assertEquals(3, countApplicationCollections(descriptor))
        assertTrue(descriptor.contains(byteArrayOf(0x05, 0x01, 0x09, 0x06))) // keyboard
        assertTrue(descriptor.contains(byteArrayOf(0x05, 0x01, 0x09, 0x02))) // mouse
        assertTrue(descriptor.contains(byteArrayOf(0x05, 0x0C, 0x09, 0x01))) // consumer
    }

    /** Report ID items (`0x85 nn`) in descriptor order. */
    private fun reportIds(descriptor: ByteArray): List<Int> {
        val ids = mutableListOf<Int>()
        var index = 0
        while (index < descriptor.size - 1) {
            if (descriptor[index] == 0x85.toByte()) {
                ids += descriptor[index + 1].toInt() and 0xFF
            }
            index++
        }
        return ids
    }

    /** Count of `Collection (Application)` items (0xA1 0x01) - one per top-level report. */
    private fun countApplicationCollections(descriptor: ByteArray): Int {
        var count = 0
        var index = 0
        while (index < descriptor.size - 1) {
            if (descriptor[index] == 0xA1.toByte() && descriptor[index + 1] == 0x01.toByte()) count++
            index++
        }
        return count
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
