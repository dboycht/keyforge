package com.dboycht.keyforge.text

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TextSender] - the part that turns a [TextForwarder] plan into real sink calls.
 *
 * These use a fake [KeySink] instead of a Bluetooth session on purpose: the rules that
 * matter here (ordering, modifier pairing, aborting safely, counting what went out) are
 * pure logic, and a fake makes the failure paths reachable - you cannot easily make a real
 * host disconnect at the third keystroke.
 */
class TextSenderTest {

    /** Records every call; can be told to start failing at a given call number. */
    private class FakeSink(private val failFromCall: Int = Int.MAX_VALUE) : KeySink {
        val calls = mutableListOf<String>()
        var releaseAllCount = 0
            private set
        private var callCount = 0

        override fun press(usageCode: Int): Boolean {
            callCount++
            calls += "down:0x%02X".format(usageCode)
            return callCount < failFromCall
        }

        override fun release(usageCode: Int): Boolean {
            callCount++
            calls += "up:0x%02X".format(usageCode)
            return callCount < failFromCall
        }

        override fun releaseSinkKeys() {
            releaseAllCount++
        }
    }

    private fun send(sink: KeySink, text: String): TextSender.Outcome = runBlocking {
        // Pauses are irrelevant to the logic under test, so they resolve instantly.
        TextSender(sink).send(text) { /* no sleeping */ }
    }

    @Test
    fun `plain ascii is pressed and released in order`() {
        val sink = FakeSink()
        val outcome = send(sink, "ab")

        assertEquals("down:0x04", sink.calls[0])
        assertEquals("up:0x04", sink.calls[1])
        assertEquals("down:0x05", sink.calls[2])
        assertEquals("up:0x05", sink.calls[3])
        assertEquals(2, outcome.sent)
        assertTrue(outcome.allSent)
        assertFalse(outcome.aborted)
    }

    @Test
    fun `an uppercase letter is wrapped in a press and release of shift`() {
        val sink = FakeSink()
        send(sink, "A")

        // Shift down, A down, A up, Shift up - shift must not survive the character.
        assertEquals("down:0xE1", sink.calls.first())
        assertEquals("up:0xE1", sink.calls.last())
        assertTrue("A must be pressed between the shift events", sink.calls.contains("down:0x04"))
    }

    @Test
    fun `shifted punctuation is wrapped in shift`() {
        val sink = FakeSink()
        send(sink, "!")

        assertEquals("down:0xE1", sink.calls.first())
        assertEquals("up:0xE1", sink.calls.last())
        assertTrue(sink.calls.contains("down:0x1E"))
    }

    @Test
    fun `shift usage counts as a modifier press not a character`() {
        val sink = FakeSink()
        // "aA" = two characters, four key events plus two shift events.
        val outcome = send(sink, "aA")
        assertEquals("the reported count is characters, not events", 2, outcome.sent)
    }

    @Test
    fun `unmappable characters are reported and do not abort the rest`() {
        val sink = FakeSink()
        // Chinese has no HID scancode; the ASCII around it must still go out.
        val outcome = send(sink, "a中b")

        assertEquals(2, outcome.sent)
        assertEquals(1, outcome.skipped.size)
        assertEquals('中', outcome.skipped.first().char)
        assertTrue(
            "the reason must explain why, not just that it failed",
            outcome.skipped.first().reason.isNotBlank(),
        )
        assertFalse("skipping one character is not a failure", outcome.aborted)
    }

    @Test
    fun `a refused step aborts the plan and releases everything`() {
        // Fail on the very first call: the session is not ready / no host is connected.
        val sink = FakeSink(failFromCall = 1)
        val outcome = send(sink, "abc")

        assertTrue("a refused step must be reported as a failure", outcome.aborted)
        assertNotNull(outcome.failure)
        assertEquals(0, outcome.sent)
        assertEquals(
            "aborting must not leave a modifier or key held down",
            1,
            sink.releaseAllCount,
        )
    }

    @Test
    fun `aborting on the shift of an uppercase letter also releases`() {
        val sink = FakeSink(failFromCall = 2) // shift down ok, A down refused
        val outcome = send(sink, "A")

        assertTrue(outcome.aborted)
        // No "Shift up" was ever sent, so the release-all is the only thing clearing it.
        assertEquals(1, sink.releaseAllCount)
    }

    @Test
    fun `empty text sends nothing and reports nothing missing`() {
        val sink = FakeSink()
        val outcome = send(sink, "")

        assertTrue("no calls for empty input", sink.calls.isEmpty())
        assertEquals(0, outcome.sent)
        assertTrue(outcome.skipped.isEmpty())
        assertTrue("empty input is not a failure", outcome.allSent)
    }

    @Test
    fun `text that is entirely unmappable is not reported as sent`() {
        val sink = FakeSink()
        val outcome = send(sink, "中文")

        assertEquals(0, outcome.sent)
        assertEquals(2, outcome.skipped.size)
        assertFalse(outcome.allSent)
        assertFalse("nothing was refused, so this is not an abort", outcome.aborted)
    }

    @Test
    fun `a sentence round-trips into paired events`() {
        val sink = FakeSink()
        val outcome = send(sink, "Hi 5")

        // Every "down" must have a matching "up" - otherwise keys stay stuck on the host.
        val downs = sink.calls.filter { it.startsWith("down:") }.map { it.removePrefix("down:") }
        val ups = sink.calls.filter { it.startsWith("up:") }.map { it.removePrefix("up:") }
        assertEquals("unbalanced down/up: ${sink.calls}", downs.sorted(), ups.sorted())
        assertEquals(4, outcome.sent)
        assertTrue(outcome.allSent)
    }
}
