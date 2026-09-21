package com.dboycht.keyforge.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TextDiff], the "what did the user just type" arithmetic behind the forwarding box.
 *
 * These matter more than they look: getting this wrong means the host receives the whole line
 * again after every keystroke (`h`, `he`, `hel` -> `hhehel`), which is immediately wrong in a way
 * the user sees on the other device.
 */
class TextDiffTest {

    @Test
    fun `typing one character at the end is an append`() {
        val change = TextDiff.between("hell", "hello")

        assertEquals("o", change.inserted)
        assertEquals(0, change.deletedFromEnd)
        assertTrue(change.isAppend)
        assertFalse(change.needsResync)
    }

    @Test
    fun `typing a run of characters reports only the new ones`() {
        // An IME can commit several characters at once (autocorrect, a candidate word).
        val change = TextDiff.between("hello", "hello world")

        assertEquals(" world", change.inserted)
        assertTrue(change.isAppend)
    }

    @Test
    fun `backspace reports one deletion and no insert`() {
        val change = TextDiff.between("hello", "hell")

        assertEquals("", change.inserted)
        assertEquals(1, change.deletedFromEnd)
        assertTrue(change.isBackspace)
        assertFalse(change.needsResync)
    }

    @Test
    fun `deleting a whole selection from the end reports the count`() {
        val change = TextDiff.between("hello world", "hello")

        assertEquals(6, change.deletedFromEnd)
        assertTrue(change.isBackspace)
    }

    @Test
    fun `an edit in the middle reports a resync instead of guessing`() {
        // Caret moved into the middle: "hel|lo" -> "heXllo". Reproducing that with end-of-line
        // keystrokes is impossible, so the honest answer is "I cannot express this".
        val change = TextDiff.between("hello", "heXllo")

        assertTrue("a mid-line edit must not be reported as a plain append", change.needsResync)
        assertEquals("", change.inserted)
    }

    @Test
    fun `replacing the whole text reports a resync`() {
        val change = TextDiff.between("hello", "goodbye")

        assertTrue(change.needsResync)
    }

    @Test
    fun `no change is a noop`() {
        val change = TextDiff.between("hello", "hello")

        assertTrue(change.isNoop)
        assertFalse(change.needsResync)
    }

    @Test
    fun `typing into an empty field is an append of everything`() {
        val change = TextDiff.between("", "hi")

        assertEquals("hi", change.inserted)
        assertTrue(change.isAppend)
    }

    @Test
    fun `clearing the field reports a backspace for every character`() {
        val change = TextDiff.between("hello", "")

        assertEquals(5, change.deletedFromEnd)
        assertTrue(change.isBackspace)
    }

    @Test
    fun `a non-ascii character is reported as an append so the sender can judge it`() {
        // The diff must not silently drop characters it cannot send: the forwarding layer decides
        // that, and the UI has to be able to say which characters were refused and why.
        val change = TextDiff.between("ni", "ni好")

        assertEquals("好", change.inserted)
        assertTrue(change.isAppend)
    }
}
