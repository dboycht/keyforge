package com.dboycht.keyforge.session

import com.dboycht.keyforge.hid.HidReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock the test drives by hand; no waiting, no flakiness. */
private class FakeClock(var now: Long = 1_000L) : SessionClock {
    var scheduledDelay: Long? = null
    var scheduledAction: (() -> Unit)? = null

    override fun nowMs(): Long = now

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        scheduledDelay = delayMs
        scheduledAction = action
        return Cancellable { scheduledAction = null }
    }
}

class ReportThrottleTest {

    @Test
    fun `first report is never delayed`() {
        val clock = FakeClock()
        val throttle = ReportThrottle(clock)
        assertTrue(throttle.canSendNow())
        assertEquals(0L, throttle.waitMs())
    }

    @Test
    fun `second report inside the gap has to wait the remainder`() {
        val clock = FakeClock(now = 1_000)
        val throttle = ReportThrottle(clock, minGapMs = 20)
        throttle.markSent()

        clock.now = 1_005
        assertEquals(15L, throttle.waitMs())
        assertFalse(throttle.canSendNow())

        clock.now = 1_020
        assertEquals(0L, throttle.waitMs())
        assertTrue(throttle.canSendNow())
    }

    @Test
    fun `default gap is 20 ms`() {
        assertEquals(20L, ReportThrottle.DEFAULT_MIN_GAP_MS)
        val clock = FakeClock()
        val throttle = ReportThrottle(clock)
        throttle.markSent()
        clock.now += ReportThrottle.DEFAULT_MIN_GAP_MS - 1
        assertEquals(1L, throttle.waitMs())
    }

    @Test
    fun `reset forgets history so a reconnect is not throttled`() {
        val clock = FakeClock()
        val throttle = ReportThrottle(clock)
        throttle.markSent()
        clock.now += 1
        assertFalse(throttle.canSendNow())

        throttle.reset()
        assertTrue(throttle.canSendNow())
    }
}

class KeyboardKeyTest {

    @Test
    fun `letter key resolves to a usable usage id`() {
        val a = KeyboardKey.minimalSet().first { it.label == "a" }
        assertEquals(0x04, a.usage)
        assertEquals("0x04", a.usageLabel)
        assertFalse(a.isModifier)
        assertNull(a.modifierLabel)
    }

    @Test
    fun `shift is recognised as a modifier with a usage id, not a bit`() {
        val shift = KeyboardKey.minimalSet().first { it.label == "Shift" }
        assertTrue(shift.isModifier)
        assertEquals(0xE1, shift.usage)                        // modifier usage, not 0x02
        assertEquals("Left Shift", shift.modifierLabel)
        assertEquals(HidReport.Modifier.LEFT_SHIFT, HidReport.Modifier.bitForUsage(shift.usage!!))
    }

    @Test
    fun `every minimal key is mappable and printable`() {
        val set = KeyboardKey.minimalSet()
        assertTrue("minimal set should not be empty", set.isNotEmpty())
        set.forEach { key ->
            assertNotNull("${key.label} must map to a usage id", key.usage)
            assertTrue("${key.label} usage out of range", key.usage!! in 0x00..0xFF)
            assertTrue("${key.label} label missing", key.usageLabel.startsWith("0x"))
        }
        assertEquals("minimal set has no duplicate codes", set.size, set.map { it.keyCode }.toSet().size)
    }
}
