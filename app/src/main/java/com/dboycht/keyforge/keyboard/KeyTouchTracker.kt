package com.dboycht.keyforge.keyboard

/**
 * Per-key pointer tracking: decides, from the raw multi-pointer event stream,
 * whether THIS key was just pressed or just released.
 *
 * Why this exists (ERROR.md E22): Compose's `PointerEvent.changes` contains
 * **every active pointer on the screen**, not only the ones touching this key.
 * The previous gesture code picked `changes.first { it.pressed }` as "my"
 * pointer - which is the oldest finger still down, i.e. usually a finger
 * holding a *different* key. With Shift held, a newly pressed letter key then
 * tracked the Shift finger: the letter was never released on lift, so the host
 * typed `AAAAA…` until Shift itself lifted. Every simultaneous-key scenario
 * (Shift+letter, game WASD, roll typing) broke one way or another.
 *
 * The rule that replaces it, as a unit-tested state machine (same seam as
 * [AutoRepeater] / [TouchpadEngine] - the Composable is a thin translator):
 *
 * - a key is pressed by the pointer whose change is a **new down** and whose
 *   position lands **inside this key's bounds** - never by any other pointer;
 * - a key is released exactly when **its own tracked pointer** lifts;
 * - one key tracks at most one pointer (a second finger on the same key is
 *   ignored while the first is held);
 * - a tracked pointer lifting in the same event that a new pointer lands here
 *   yields Release-then-Press (fast roll typing must not drop the new press).
 *
 * Sliding the tracked finger off the key keeps the key held: game movement keys
 * must survive finger wobble, and the press was already committed at touch-down.
 */
internal class KeyTouchTracker(private val width: Float, private val height: Float) {

    /** One pointer change, translated from Compose's `PointerInputChange`. */
    data class Change(
        /** Compose's `PointerId.value` (Long). */
        val id: Long,
        val pressed: Boolean,
        /** True only on the down transition (was up, now down) - `PointerInputChange.changedToDown()`. */
        val changedToDown: Boolean,
        /** Position in this key's local coordinates (pixels). */
        val x: Float,
        val y: Float,
    )

    /** What the key should do, in order. */
    sealed interface Action {
        /** A genuine new press on this key. */
        data object Press : Action

        /** The tracked pointer lifted (or the stream was cancelled): release the key. */
        data object Release : Action
    }

    private var trackedId: Long? = null

    /** True while a pointer is being tracked (the key is "held" in gesture terms). */
    val isTracking: Boolean get() = trackedId != null

    /**
     * Feeds one pointer event; returns the actions to perform, in order (at most
     * [Action.Release] followed by [Action.Press]).
     */
    fun onEvent(changes: List<Change>): List<Action> {
        val actions = mutableListOf<Action>()

        val tracked = trackedId
        if (tracked != null) {
            val mine = changes.firstOrNull { it.id == tracked }
            if (mine == null || !mine.pressed) {
                trackedId = null
                actions += Action.Release
            }
        }
        if (trackedId == null) {
            val down = changes.firstOrNull { it.changedToDown && it.pressed && it.isInside() }
            if (down != null) {
                trackedId = down.id
                actions += Action.Press
            }
        }
        return actions
    }

    /**
     * The pointer stream was aborted (composition left, gesture cancelled): the
     * key must be released if it was held - a stuck key is exactly the failure
     * class this project paid for before (ERROR.md E10/E11).
     */
    fun onCancel(): List<Action> {
        if (trackedId == null) return emptyList()
        trackedId = null
        return listOf(Action.Release)
    }

    private fun Change.isInside(): Boolean =
        x >= 0f && x <= width && y >= 0f && y <= height
}
