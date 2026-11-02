package com.dboycht.keyforge.keyboard

import com.dboycht.keyforge.keyboard.KeyTouchTracker.Action
import com.dboycht.keyforge.keyboard.KeyTouchTracker.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-key pointer tracking state machine.
 *
 * The central regression here is the simultaneous-key bug (ERROR.md E22): the
 * old code picked `changes.first { it.pressed }`, so with Shift held, a newly
 * pressed letter key tracked the SHIFT finger and never saw its own lift. The
 * key test below is the minimal reproduction: two keys' trackers fed the SAME
 * two-pointer event stream must each follow their own pointer.
 */
class KeyTouchTrackerTest {

    private val width = 100f
    private val height = 50f

    private fun tracker() = KeyTouchTracker(width, height)

    private fun change(
        id: Long,
        pressed: Boolean = true,
        changedToDown: Boolean = false,
        x: Float = 50f,
        y: Float = 25f,
    ) = Change(id, pressed, changedToDown, x, y)

    // ---------- basic press / release ----------

    @Test
    fun `a new down inside the key is a press`() {
        val t = tracker()
        assertEquals(
            listOf<Action>(Action.Press),
            t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true, x = 40f, y = 20f))),
        )
        assertTrue(t.isTracking)
    }

    @Test
    fun `the tracked pointer lifting is a release`() {
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        assertEquals(
            listOf<Action>(Action.Release),
            t.onEvent(listOf(change(id = 1, pressed = false))),
        )
        assertFalse(t.isTracking)
    }

    @Test
    fun `moves of the tracked pointer change nothing`() {
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        assertEquals(emptyList<Action>(), t.onEvent(listOf(change(id = 1, pressed = true, x = 90f, y = 10f))))
        assertTrue(t.isTracking)
    }

    @Test
    fun `the key is reusable after release`() {
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        t.onEvent(listOf(change(id = 1, pressed = false)))
        assertEquals(
            listOf<Action>(Action.Press),
            t.onEvent(listOf(change(id = 2, pressed = true, changedToDown = true))),
        )
    }

    // ---------- the E22 regression: other fingers must not drive this key ----------

    @Test
    fun `another finger already holding a different key does not get tracked`() {
        val t = tracker()
        // Pointer 1 is down on ANOTHER key; this key receives the same events.
        // A mere "pressed" change that is not a new down must not press this key.
        assertEquals(
            emptyList<Action>(),
            t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = false))),
        )
        assertFalse(t.isTracking)
    }

    @Test
    fun `a new press while another finger is down tracks the NEW pointer, not the oldest one`() {
        val t = tracker()
        // Pointer 1 pressed earlier on another key; pointer 2 now presses THIS key.
        // The old code picked pointer 1 here (`first { it.pressed }`) - the E22 bug.
        assertEquals(
            listOf<Action>(Action.Press),
            t.onEvent(
                listOf(
                    change(id = 1, pressed = true, changedToDown = false), // other key's finger
                    change(id = 2, pressed = true, changedToDown = true),  // this key's finger
                )
            ),
        )
        // And it releases when pointer 2 lifts - even if pointer 1 is still down.
        assertEquals(
            listOf<Action>(Action.Release),
            t.onEvent(
                listOf(
                    change(id = 1, pressed = true),
                    change(id = 2, pressed = false),
                )
            ),
        )
    }

    @Test
    fun `a down outside the key bounds is not a press`() {
        val t = tracker()
        assertEquals(
            emptyList<Action>(),
            t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true, x = 250f, y = 20f))),
        )
        assertEquals(
            emptyList<Action>(),
            t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true, x = 50f, y = -1f))),
        )
        assertFalse(t.isTracking)
    }

    @Test
    fun `the tracked pointer sliding outside the bounds still holds the key`() {
        // Game movement keys must survive finger wobble: the press was committed
        // at touch-down and releases only when the finger lifts.
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        assertEquals(
            emptyList<Action>(),
            t.onEvent(listOf(change(id = 1, pressed = true, x = 500f, y = 500f))),
        )
        assertTrue(t.isTracking)
    }

    @Test
    fun `a second finger on the same key is ignored while the first is held`() {
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        assertEquals(
            emptyList<Action>(),
            t.onEvent(
                listOf(
                    change(id = 1, pressed = true),
                    change(id = 2, pressed = true, changedToDown = true),
                )
            ),
        )
        // The first finger's lift still releases - the key does not stay for finger 2.
        assertEquals(
            listOf<Action>(Action.Release),
            t.onEvent(
                listOf(
                    change(id = 1, pressed = false),
                    change(id = 2, pressed = true),
                )
            ),
        )
    }

    // ---------- same-event transitions ----------

    @Test
    fun `tracked lift and a new down in the same event release then press`() {
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        assertEquals(
            listOf(Action.Release, Action.Press),
            t.onEvent(
                listOf(
                    change(id = 1, pressed = false),
                    change(id = 3, pressed = true, changedToDown = true),
                )
            ),
        )
        assertTrue(t.isTracking)
        // The new pointer is the tracked one now.
        assertEquals(
            listOf<Action>(Action.Release),
            t.onEvent(listOf(change(id = 3, pressed = false))),
        )
    }

    @Test
    fun `two fingers landing in the same event press only once`() {
        val t = tracker()
        assertEquals(
            listOf<Action>(Action.Press),
            t.onEvent(
                listOf(
                    change(id = 1, pressed = true, changedToDown = true, x = 10f, y = 10f),
                    change(id = 2, pressed = true, changedToDown = true, x = 90f, y = 40f),
                )
            ),
        )
    }

    // ---------- THE E22 minimal reproduction: two keys, one event stream ----------

    @Test
    fun `shift and letter trackers fed the same stream each follow their own pointer`() {
        val shift = KeyTouchTracker(width, height)   // the Shift key's tracker
        val letter = KeyTouchTracker(width, height)  // the letter key's tracker

        // Positions are per-node local coordinates: the finger that lands on Shift
        // is inside the Shift box but far outside the letter box, so the same
        // physical event translates to different Change lists per tracker.

        // Finger 1 (pointer 1) presses Shift; both trackers see the event.
        assertEquals(
            listOf<Action>(Action.Press),
            shift.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true, x = 50f, y = 25f))),
        )
        assertEquals(
            emptyList<Action>(),
            letter.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true, x = -120f, y = 25f))),
        )

        // Finger 2 (pointer 2) presses the letter while Shift is still held.
        // Both trackers receive the same two-pointer event (translated per node).
        assertEquals(
            emptyList<Action>(),
            shift.onEvent(
                listOf(
                    change(id = 1, pressed = true, changedToDown = false, x = 50f, y = 25f),
                    change(id = 2, pressed = true, changedToDown = true, x = 250f, y = 25f), // letter finger, outside Shift
                )
            ),
        )
        assertEquals(
            listOf<Action>(Action.Press),
            letter.onEvent(
                listOf(
                    change(id = 1, pressed = true, changedToDown = false, x = -120f, y = 25f),
                    change(id = 2, pressed = true, changedToDown = true, x = 50f, y = 25f),
                )
            ),
        )

        // The letter finger lifts first: the letter MUST release now (the old
        // code waited for the Shift finger and the host typed AAAA...).
        assertEquals(
            listOf<Action>(Action.Release),
            letter.onEvent(
                listOf(
                    change(id = 1, pressed = true, x = -120f, y = 25f),
                    change(id = 2, pressed = false, x = 50f, y = 25f),
                )
            ),
        )
        assertEquals(
            emptyList<Action>(),
            shift.onEvent(
                listOf(
                    change(id = 1, pressed = true, x = 50f, y = 25f),
                    change(id = 2, pressed = false, x = 250f, y = 25f),
                )
            ),
        )

        // Shift lifts last, releasing itself.
        assertEquals(
            listOf<Action>(Action.Release),
            shift.onEvent(listOf(change(id = 1, pressed = false, x = 50f, y = 25f))),
        )
    }

    // ---------- cancellation ----------

    @Test
    fun `cancel while tracking releases the key`() {
        val t = tracker()
        t.onEvent(listOf(change(id = 1, pressed = true, changedToDown = true)))
        assertEquals(listOf<Action>(Action.Release), t.onCancel())
        assertFalse(t.isTracking)
    }

    @Test
    fun `cancel while idle is a silent no-op`() {
        assertEquals(emptyList<Action>(), tracker().onCancel())
    }
}
