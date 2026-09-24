package com.dboycht.keyforge.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AutoRepeater], the schedule behind "what happens when a key is held".
 *
 * Context that makes these tests matter: the app used to send a **down/up pair** on every repeat
 * tick. In a game that reads as "tapped repeatedly" rather than "held", so a held movement key made
 * the character walk in stutters. A physical keyboard never does that - it holds the key down and
 * lets the host repeat the character - so the central assertions here are that [RepeatMode.HOLD]
 * sends **no up report until the finger lifts**, and that the up report is sent **exactly once**.
 */
class AutoRepeaterTest {

    /**
     * Records the wire traffic the host would see.
     *
     * Note the meaning of a repeated `onDown` depends on the mode, which is the whole point of the
     * fix: under HOLD it is a **keep-alive** (the report is identical, so the host still sees one
     * continuous press and does not count a new keystroke), while under PULSE it is a genuine new
     * keystroke (arriving just after a release). The recorder is told which mode is in force so the
     * expectations read the same way the host sees them.
     */
    private class Recorder(private val mode: RepeatMode = RepeatMode.HOLD) {
        val events = mutableListOf<String>()

        /** A *new* press: this is what counts as a keystroke. */
        fun down() { events += "down" }

        /** A repeat report under HOLD: byte-identical to the press, so not a new keystroke. */
        fun keepAlive() { events += "keepalive" }
        fun up() { events += "up" }

        /** What the repeater calls for every down report after the first. */
        fun repeatDown() {
            when (mode) {
                RepeatMode.PULSE -> down()      // a new keystroke, right after a release
                else -> keepAlive()
            }
        }

        fun ups() = events.count { it == "up" }
        fun downs() = events.count { it == "down" }
        fun keepAlives() = events.count { it == "keepalive" }

        /** Every report that puts the key (back) into the down state. */
        fun reportsWithKeyDown() = downs() + keepAlives()
    }

    private fun repeater(schedule: RepeatSchedule, rec: Recorder) =
        AutoRepeater(
            schedule,
            onDown = { isRepeat -> if (isRepeat) rec.repeatDown() else rec.down() },
            onUp = { rec.up() },
        )

    // ── HOLD: the fix ────────────────────────────────────────────────────────────

    @Test
    fun `hold sends one down immediately and nothing else while the finger stays`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 300, intervalMs = 50, keepAliveMs = 100), rec)

        r.press()

        // A normal tap must never be swallowed: the first keystroke goes out with the press.
        assertEquals(listOf("down"), rec.events)
    }

    @Test
    fun `hold never sends an up report while the finger is down`() {
        // This is the regression test for the user's report: holding a movement key made the
        // character stutter because every repeat tick sent a release.
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 300, intervalMs = 50, keepAliveMs = 100), rec)

        r.press()
        repeat(50) { r.advance(deltaMs = 100) }   // five seconds of holding

        assertEquals("the key must stay down for the whole hold", 0, rec.ups())
        assertTrue("keep-alives should still be flowing", rec.reportsWithKeyDown() > 1)
        assertEquals("keep-alives are not new keystrokes", 1, rec.downs())
        assertTrue("the key must still be held", r.isHeld)
    }

    @Test
    fun `hold sends exactly one up report, when the finger lifts`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(keepAliveMs = 100), rec)

        r.press()
        repeat(10) { r.advance(100) }
        assertTrue(r.release())

        // One press, one release - no matter how long it was held or how many keep-alives went out.
        assertEquals("exactly one release per press", 1, rec.ups())
        assertEquals("down, ..., down, up", "up", rec.events.last())
        assertFalse(r.isHeld)
    }

    @Test
    fun `hold re-sends the same report as a keep-alive so a dropped report self-heals`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 300, intervalMs = 50, keepAliveMs = 100), rec)

        r.press()                                   // t=0
        assertFalse("nothing due before the keep-alive interval", r.advance(50))
        assertTrue("keep-alive is due at 100ms", r.advance(50))

        // Two reports with the key down and no up in between: the host sees "still held", which is
        // exactly what a real keyboard reports while a key is held down.
        assertEquals(listOf("down", "keepalive"), rec.events)
    }

    @Test
    fun `hold keeps a steady cadence when the caller ticks evenly`() {
        // The real caller ticks every 25ms and the keep-alive is 50ms, so the steady state is one
        // keep-alive per second under a 100ms cadence... scaled here to 100ms interval / 25ms tick.
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(keepAliveMs = 100), rec)

        r.press()
        repeat(10) { r.advance(25) }   // 250ms: keep-alives due at 100 and 200

        assertEquals(1, rec.downs())
        assertEquals(2, rec.keepAlives())
        assertEquals(0, rec.ups())
    }

    @Test
    fun `a short tap under hold mode is exactly one press and one release`() {
        // The behaviour the user already relies on for ordinary typing must not regress: a quick tap
        // produces one down ... one up, with **no release until the finger lifts**.
        //
        // A keep-alive may land in between (this finger rests 80ms, and the first keep-alive is due
        // after the keep-alive interval) - that is fine and intended: a keep-alive re-sends the same
        // held report, so the host sees one continuous press, not two keystrokes. The host counts
        // keystrokes by *state changes*, and there is exactly one of those in each direction.
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 300, keepAliveMs = 50), rec)

        r.press()
        r.advance(80)            // shorter than the typing repeat delay
        r.release()

        assertEquals("exactly one release", 1, rec.ups())
        assertEquals("no release before the lift", "up", rec.events.last())
        assertEquals(
            "one press, possibly a keep-alive, then the release",
            listOf("down", "keepalive", "up"),
            rec.events,
        )
        assertEquals("only the real press counts as a keystroke", 1, rec.downs())
    }

    @Test
    fun `the typing repeat delay is not a keep-alive delay`() {
        // Guards the distinction: HOLD keeps the key alive on the keep-alive cadence (a dropped
        // report must self-heal quickly), while the typing repeat delay only governs PULSE mode
        // and what the UI shows about the host's repeat rate.
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 500, keepAliveMs = 40), rec)

        r.press()
        assertFalse("nothing due yet", r.advance(20))
        assertTrue("the first keep-alive ignores the 500ms typing delay", r.advance(20))
    }

    // ── PULSE: the legacy behaviour, now opt-in ──────────────────────────────────

    @Test
    fun `hold is not pulse even after the typing repeat delay has passed`() {
        // The user's bug in one assertion: this is what the app used to do after 300ms of holding a
        // key - and it must never do it again. No release may appear until the finger lifts.
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 300, keepAliveMs = 50), rec)

        r.press()
        r.advance(300)          // the old code started pulsing here
        r.advance(50)

        assertEquals("never a release while held", 0, rec.ups())
        assertEquals("one keystroke, then keep-alives", 1, rec.downs())
        assertEquals(listOf("down", "keepalive", "keepalive"), rec.events)
    }

    // ── PULSE: the legacy behaviour, now opt-in ──────────────────────────────────

    @Test
    fun `pulse alternates down and up, which is why it is not the default`() {
        // Timing uses the real tick size (25ms) and an interval that is a multiple of it, so the
        // expected count is arithmetic rather than a guess about overshoot.
        val rec = Recorder(RepeatMode.PULSE)
        val r = repeater(RepeatSchedule.pulse(delayMs = 100, intervalMs = 100), rec)

        r.press()
        repeat(12) { r.advance(25) }   // 300ms; pulses are due at 100, 200 and 300ms

        // down (press) + (up,down) x3
        assertEquals(4, rec.downs())
        assertEquals(3, rec.ups())
        assertEquals(
            listOf("down", "up", "down", "up", "down", "up", "down"),
            rec.events,
        )
    }

    @Test
    fun `one tick sends at most one pulse however far it overshoots`() {
        // A long stall must not dump a burst of keystrokes the user never typed.
        val rec = Recorder(RepeatMode.PULSE)
        val r = repeater(RepeatSchedule.pulse(delayMs = 100, intervalMs = 100), rec)

        r.press()
        r.advance(1000)   // ten intervals' worth of stall

        assertEquals("one pulse, not ten", 1, rec.ups())
        assertEquals(2, rec.downs())
    }

    @Test
    fun `pulse still releases exactly once at the end`() {
        val rec = Recorder(RepeatMode.PULSE)
        val r = repeater(RepeatSchedule.pulse(delayMs = 100, intervalMs = 100), rec)

        r.press()
        repeat(12) { r.advance(25) }   // three pulses
        r.release()

        assertEquals("three pulse-ups plus the final release", 4, rec.ups())
        assertEquals("up", rec.events.last())
    }

    // ── ONESHOT ─────────────────────────────────────────────────────────────────

    @Test
    fun `oneshot never repeats however long it is held`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.ONESHOT, rec)

        r.press()
        repeat(100) { r.advance(100) }
        r.release()

        assertEquals(listOf("down", "up"), rec.events)
    }

    // ── Release safety ──────────────────────────────────────────────────────────

    @Test
    fun `release without a press sends nothing`() {
        // A stray release would clear a key another finger is still holding.
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(), rec)

        assertFalse(r.release())
        assertEquals(emptyList<String>(), rec.events)
    }

    @Test
    fun `release twice sends only one up report`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(), rec)

        r.press()
        assertTrue(r.release())
        assertFalse("the second release must be a no-op", r.release())

        assertEquals(1, rec.ups())
    }

    @Test
    fun `advance after release sends nothing`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(keepAliveMs = 10), rec)

        r.press()
        r.release()
        val before = rec.events.toList()
        repeat(10) { r.advance(100) }

        assertEquals("a released key must not come back to life", before, rec.events)
    }

    @Test
    fun `a second press after a release works again`() {
        val rec = Recorder()
        val r = repeater(RepeatSchedule.hold(delayMs = 300), rec)

        r.press(); r.release()
        r.press(); r.release()

        assertEquals(listOf("down", "up", "down", "up"), rec.events)
    }

    // ── Schedule data ───────────────────────────────────────────────────────────

    @Test
    fun `hold schedule carries sane typing timing for the UI to display`() {
        val schedule = RepeatSchedule.hold()
        assertEquals(RepeatMode.HOLD, schedule.mode)
        assertTrue("repeat delay must not double a normal tap", schedule.delayMs >= 250L)
        assertTrue(
            "repeat interval must feel continuous",
            schedule.intervalMs in 20L..120L,
        )
        assertTrue(
            "keep-alive must be frequent enough to survive a dropped report",
            schedule.keepAliveMs in 20L..200L,
        )
    }

    @Test
    fun `the default mode is hold, not pulse`() {
        // Guards the actual regression: if someone flips the default back to PULSE, held keys break
        // in games again (stuttering movement) and this test says so.
        assertEquals(RepeatMode.HOLD, RepeatSchedule.hold().mode)
    }
}
