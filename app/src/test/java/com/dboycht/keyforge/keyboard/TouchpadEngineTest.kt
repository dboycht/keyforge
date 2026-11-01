package com.dboycht.keyforge.keyboard

import com.dboycht.keyforge.hid.MouseReport
import com.dboycht.keyforge.keyboard.TouchpadEngine.Action
import com.dboycht.keyforge.keyboard.TouchpadEngine.Pad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the touchpad gesture state machine. The engine is fed
 * synthetic pointer snapshots (times in ms, positions in view pixels); the
 * assertions are on the emitted actions, in order.
 */
class TouchpadEngineTest {

    // ---------- taps ----------

    @Test
    fun `quick still tap is a left click`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        assertEquals(listOf<Action>(Action.Click(MouseReport.Button.LEFT)), engine.onPointerUp(80, emptyList()))
    }

    @Test
    fun `a long rest within the slop is still a tap`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerMove(1_500, listOf(Pad(101f, 100f)))
        assertEquals(listOf<Action>(Action.Click(MouseReport.Button.LEFT)), engine.onPointerUp(1_600, emptyList()))
    }

    @Test
    fun `slide beyond the slop is a move, not a click`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerMove(30, listOf(Pad(160f, 100f)))
        val actions = engine.onPointerUp(60, emptyList())
        assertTrue("expected no click, got $actions", actions.none { it is Action.Click })
    }

    @Test
    fun `tap and then tap again - the engine is reusable`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerUp(50, emptyList())
        engine.onPointerDown(100, listOf(Pad(300f, 300f)))
        assertEquals(listOf<Action>(Action.Click(MouseReport.Button.LEFT)), engine.onPointerUp(150, emptyList()))
    }

    // ---------- movement ----------

    @Test
    fun `slide emits whole steps and keeps the fractional remainder`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        // +3.4 px: one step of 3, remainder 0.4 stays in the accumulator
        assertEquals(listOf<Action>(Action.Move(3, 0)), engine.onPointerMove(10, listOf(Pad(103.4f, 100f))))
        // +3.8 px on top of 0.4 = 4.2: one step of 4, remainder 0.2
        assertEquals(listOf<Action>(Action.Move(4, 0)), engine.onPointerMove(20, listOf(Pad(107.2f, 100f))))
    }

    @Test
    fun `a sub pixel move emits nothing`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        assertEquals(emptyList<Action>(), engine.onPointerMove(10, listOf(Pad(100.4f, 100f))))
    }

    @Test
    fun `sensitivity scales the emitted steps`() {
        val engine = TouchpadEngine(sensitivity = 2f)
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        assertEquals(listOf<Action>(Action.Move(12, 0)), engine.onPointerMove(10, listOf(Pad(106f, 100f))))
    }

    @Test
    fun `a flick longer than the int8 field is split into several moves`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(0f, 0f)))
        assertEquals(
            listOf(Action.Move(127, 0), Action.Move(127, 0), Action.Move(46, 0)),
            engine.onPointerMove(10, listOf(Pad(300f, 0f))),
        )
    }

    @Test
    fun `negative movement truncates toward zero and keeps the sign of the remainder`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        // -3.4 px: step -3, remainder -0.4 (not rounded up to 0)
        assertEquals(listOf<Action>(Action.Move(-3, 0)), engine.onPointerMove(10, listOf(Pad(96.6f, 100f))))
        // -3.8 more: -4.2 accumulated, step -4, remainder -0.2
        assertEquals(listOf<Action>(Action.Move(-4, 0)), engine.onPointerMove(20, listOf(Pad(92.8f, 100f))))
    }

    // ---------- press-hold drag ----------

    @Test
    fun `slow drag past the long press grabs the left button and releases it on lift`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        assertEquals(
            listOf(Action.HoldStart, Action.Move(100, 0)),
            engine.onPointerMove(400, listOf(Pad(200f, 100f))),
        )
        assertEquals(listOf<Action>(Action.Move(50, 0)), engine.onPointerMove(500, listOf(Pad(250f, 100f))))
        assertEquals(listOf<Action>(Action.HoldEnd), engine.onPointerUp(600, emptyList()))
    }

    @Test
    fun `quick drag never grabs the button`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        val actions = engine.onPointerMove(100, listOf(Pad(200f, 100f)))
        assertTrue("expected no HoldStart, got $actions", actions.none { it is Action.HoldStart })
    }

    @Test
    fun `micro jitter after a long rest does not grab the button`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        val actions = engine.onPointerMove(500, listOf(Pad(103f, 100f)))
        assertTrue("expected no HoldStart, got $actions", actions.none { it is Action.HoldStart })
    }

    // ---------- two finger gestures ----------

    @Test
    fun `two fingers down then up quickly is a right click, never a left click`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerDown(10, listOf(Pad(100f, 100f), Pad(150f, 100f)))
        val actions = engine.onPointerUp(60, emptyList())
        assertEquals(listOf<Action>(Action.Click(MouseReport.Button.RIGHT)), actions)
    }

    @Test
    fun `two finger slide scrolls with whole ticks and a remainder`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerDown(10, listOf(Pad(100f, 100f), Pad(150f, 100f)))
        // each move slides the midpoint 30 px down: 30 -> no tick, 60 -> -1, 90 -> -1
        assertEquals(emptyList<Action>(), engine.onPointerMove(20, listOf(Pad(100f, 130f), Pad(150f, 130f))))
        assertEquals(listOf<Action>(Action.Scroll(-1)), engine.onPointerMove(30, listOf(Pad(100f, 160f), Pad(150f, 160f))))
        assertEquals(listOf<Action>(Action.Scroll(-1)), engine.onPointerMove(40, listOf(Pad(100f, 190f), Pad(150f, 190f))))
    }

    @Test
    fun `scroll direction is traditional - fingers up scroll up`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerDown(10, listOf(Pad(100f, 100f), Pad(150f, 100f)))
        assertEquals(
            listOf<Action>(Action.Scroll(1)),
            engine.onPointerMove(20, listOf(Pad(100f, 60f), Pad(150f, 60f))), // midpoint -40 px
        )
    }

    @Test
    fun `two finger slide cancels the right tap`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerDown(10, listOf(Pad(100f, 100f), Pad(150f, 100f)))
        engine.onPointerMove(30, listOf(Pad(100f, 200f), Pad(150f, 200f)))
        val actions = engine.onPointerUp(60, emptyList())
        assertTrue("expected no click, got $actions", actions.none { it is Action.Click })
    }

    @Test
    fun `one finger lifting after two ends the gesture silently`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerDown(10, listOf(Pad(100f, 100f), Pad(150f, 100f)))
        assertEquals(emptyList<Action>(), engine.onPointerUp(30, listOf(Pad(150f, 100f))))
        assertEquals(emptyList<Action>(), engine.onPointerUp(40, emptyList()))
    }

    @Test
    fun `three fingers are ignored without corrupting the state`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerDown(10, listOf(Pad(100f, 100f), Pad(150f, 100f)))
        assertEquals(emptyList<Action>(), engine.onPointerDown(20, listOf(Pad(100f, 100f), Pad(150f, 100f), Pad(200f, 100f))))
        val actions = engine.onPointerUp(40, emptyList())
        assertTrue("expected no click, got $actions", actions.none { it is Action.Click })
    }

    // ---------- cancellation ----------

    @Test
    fun `cancel during a held drag releases the button`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerMove(400, listOf(Pad(200f, 100f)))
        assertEquals(listOf<Action>(Action.HoldEnd), engine.onCancel())
    }

    @Test
    fun `cancel resets the engine so the next gesture starts clean`() {
        val engine = TouchpadEngine()
        engine.onPointerDown(0, listOf(Pad(100f, 100f)))
        engine.onPointerMove(10, listOf(Pad(200f, 100f)))
        engine.onCancel()
        engine.onPointerDown(100, listOf(Pad(0f, 0f)))
        assertEquals(listOf<Action>(Action.Click(MouseReport.Button.LEFT)), engine.onPointerUp(150, emptyList()))
    }

    @Test
    fun `cancel outside a gesture is a silent no-op`() {
        assertEquals(emptyList<Action>(), TouchpadEngine().onCancel())
    }
}
