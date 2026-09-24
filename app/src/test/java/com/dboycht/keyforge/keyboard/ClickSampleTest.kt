package com.dboycht.keyforge.keyboard

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the built-in key click.
 *
 * The click is generated, not shipped as an asset, so "is the file even valid" and "does it sound
 * like a click" are things that can only be checked here (the second one only approximately - whether
 * it is *pleasant* is the user's call, and the author cannot hear it).
 *
 * The two properties that matter: it must be **audible** (not a silent buffer, which would make the
 * switch look broken) and it must **decay** (a constant-amplitude tone on every key press is a beep,
 * and a beep on every key press is unbearable).
 */
class ClickSampleTest {

    private val bytes = ClickSample.wavBytes()
    private val pcm = ClickSample.pcm()

    @Test
    fun `the file is a valid 16 bit mono pcm wav`() {
        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVE", ascii(8, 4))
        assertEquals("fmt ", ascii(12, 4))
        assertEquals("data", ascii(36, 4))
        assertEquals("RIFF chunk size", bytes.size - 8, le32(4))
        assertEquals("data chunk size", bytes.size - 44, le32(40))
        assertEquals("fmt chunk size", 16, le32(16))
        assertEquals("PCM format", 1, le16(20))
        assertEquals("channels", 1, le16(22))
        assertEquals("sample rate", 44100, le32(24))
        assertEquals("byte rate", 44100 * 2, le32(28))
        assertEquals("block align", 2, le16(32))
        assertEquals("bits per sample", 16, le16(34))
    }

    @Test
    fun `the click is short enough to feel like a key and not a tone`() {
        // 5-30 ms: shorter is a tick, longer starts to sound like a beep.
        val milliseconds = pcm.size * 1000.0 / 44100.0
        assertTrue("click is $milliseconds ms", milliseconds in 5.0..30.0)
    }

    @Test
    fun `the click is audible and never clipped`() {
        val peak = pcm.maxOf { abs(it.toInt()) }
        val fraction = peak / 32767.0
        assertTrue("peak is only $fraction of full scale (silent buffer?)", fraction >= 0.15)
        assertTrue("peak is $fraction of full scale (clipping)", fraction <= 0.95)
    }

    @Test
    fun `the click decays instead of droning`() {
        val quarter = pcm.size / 4
        val head = rms(0, quarter)
        val tail = rms(pcm.size - quarter, pcm.size)
        assertTrue("tail ($tail) is not quieter than head ($head)", tail < head / 3.0)
    }

    @Test
    fun `the same click is produced on every run`() {
        // Deterministic rendering: a test can reason about the samples, and the user hears the same
        // click after an app update unless the algorithm was changed on purpose.
        assertTrue(bytes.contentEquals(ClickSample.wavBytes()))
    }

    private fun rms(from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }

    private fun ascii(offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun le16(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
