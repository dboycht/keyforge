package com.dboycht.keyforge.keyboard

import com.dboycht.keyforge.hid.MouseReport
import kotlin.math.sqrt

/**
 * Turns raw touchpad pointer events into HID mouse actions.
 *
 * Pure Kotlin on purpose (same seam as [AutoRepeater]): Compose feeds it
 * discrete pointer snapshots, it returns the actions to perform against the
 * session (`moveMouse` / `tapMouseButton` / `pressMouseButton` /
 * `releaseMouseButton` / `scrollWheel`). Everything testable about the touchpad
 * lives here; the composable is a thin translator.
 *
 * Gesture model (v1):
 * - single finger slide = move the pointer;
 * - single finger tap (down..up inside [TAP_MAX_MS] and within [TAP_SLOP_PX])
 *   = left click, any rest duration;
 * - press-hold (past [LONG_PRESS_MS]) then slide = left button held drag
 *   (drag-select); release ends the hold;
 * - two-finger tap = right click;
 * - two-finger slide = wheel scroll (traditional direction: fingers up scrolls
 *   up - flip [SCROLL_DIRECTION] if the host feel is inverted);
 * - three or more fingers are ignored;
 * - a gesture that loses a finger mid-way (2 -> 1) ends without action.
 *
 * Pointer contract: every call receives the positions of ALL currently active
 * pointers, with the gesture's original pointer first (the UI keeps that order
 * by pointer id). Positions are in view pixels; all thresholds are in the same
 * unit, so the engine is density-agnostic and the UI never rescales.
 */
internal class TouchpadEngine(private val sensitivity: Float = DEFAULT_SENSITIVITY) {

    /** One touchpad pointer position, in view pixels. */
    data class Pad(val x: Float, val y: Float)

    /** What the engine wants done to the session. */
    sealed interface Action {
        /** Relative pointer movement; each value fits one int8 report field. */
        data class Move(val x: Int, val y: Int) : Action

        /** Wheel delta in ticks (positive = away from the user). */
        data class Scroll(val wheel: Int) : Action

        /** Click (down + up) of one [MouseReport.Button] bit. */
        data class Click(val button: Int) : Action

        /** Left button goes down (start of a press-hold drag). */
        data object HoldStart : Action

        /** Left button goes up (end of a press-hold drag). */
        data object HoldEnd : Action
    }

    private enum class Phase { IDLE, TRACKING, HOLDING, TWO_FINGER }

    private var phase = Phase.IDLE

    // Single-finger tracking
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    // Movement / scroll accumulators keep the fractional remainder so slow
    // drags still advance the pointer pixel by pixel.
    private var moveAccX = 0f
    private var moveAccY = 0f
    private var scrollAcc = 0f

    // Two-finger tracking
    private var twoDownTime = 0L
    private var twoLastMidX = 0f
    private var twoLastMidY = 0f
    private var twoMoved = false

    /** Down with one pointer: begins a tap/move/drag candidate gesture. */
    fun onPointerDown(time: Long, positions: List<Pad>): List<Action> {
        if (positions.isEmpty()) return emptyList()
        return when {
            phase == Phase.IDLE && positions.size == 1 -> {
                phase = Phase.TRACKING
                downTime = time
                downX = positions[0].x
                downY = positions[0].y
                lastX = downX
                lastY = downY
                moveAccX = 0f
                moveAccY = 0f
                emptyList()
            }
            phase == Phase.TRACKING && positions.size == 2 -> beginTwoFinger(time, positions)
            phase == Phase.TWO_FINGER && positions.size > 2 -> {
                // a third finger lands: the right-tap candidacy dies (three
                // fingers tap nothing), scrolling - which reads the first two
                // pointers - continues untouched.
                twoMoved = true
                emptyList()
            }
            else -> emptyList() // extra fingers and stray downs are ignored
        }
    }

    /** One or more pointers moved: emits Move/Scroll (and HoldStart for press-hold drags). */
    fun onPointerMove(time: Long, positions: List<Pad>): List<Action> {
        if (positions.isEmpty()) return emptyList()
        return when (phase) {
            Phase.TRACKING -> trackMove(time, positions)
            Phase.HOLDING -> emitMoves(positions[0].x - lastX, positions[0].y - lastY).also {
                lastX = positions[0].x
                lastY = positions[0].y
            }
            Phase.TWO_FINGER -> if (positions.size >= 2) twoFingerMove(positions) else emptyList()
            Phase.IDLE -> emptyList()
        }
    }

    /** Pointers lifted; [positions] holds the ones still down (empty = gesture over). */
    fun onPointerUp(time: Long, positions: List<Pad>): List<Action> {
        return when (phase) {
            Phase.TRACKING, Phase.HOLDING -> endSingle(time, positions)
            Phase.TWO_FINGER -> endTwoFinger(time, positions)
            Phase.IDLE -> emptyList()
        }
    }

    /** The gesture stream was cancelled (Compose discarded the rest of the events). */
    fun onCancel(): List<Action> {
        val actions = if (phase == Phase.HOLDING) listOf<Action>(Action.HoldEnd) else emptyList()
        reset()
        return actions
    }

    // ---------- phases ----------

    private fun trackMove(time: Long, positions: List<Pad>): List<Action> {
        val primary = positions[0]
        val actions = mutableListOf<Action>()
        val dragging = distance(downX, downY, primary.x, primary.y) > TAP_SLOP_PX
        if (dragging && time - downTime > LONG_PRESS_MS) {
            // press-hold-drag: the button goes down only once the finger moves
            // past the slop after a long rest - a resting finger must not grab
            // the button, or every micro-jitter would start a drag.
            actions += Action.HoldStart
            phase = Phase.HOLDING
        }
        actions += emitMoves(primary.x - lastX, primary.y - lastY)
        lastX = primary.x
        lastY = primary.y
        return actions
    }

    private fun endSingle(time: Long, positions: List<Pad>): List<Action> {
        if (positions.isNotEmpty()) return emptyList() // a finger stays down: wait for it
        val actions = mutableListOf<Action>()
        if (phase == Phase.HOLDING) {
            actions += Action.HoldEnd
        } else if (distance(downX, downY, lastX, lastY) <= TAP_SLOP_PX) {
            // a still finger lifts: a click, however long it rested. The host
            // decides what a long click means; we only promise "button pressed
            // and released".
            actions += Action.Click(MouseReport.Button.LEFT)
        }
        reset()
        return actions
    }

    private fun beginTwoFinger(time: Long, positions: List<Pad>): List<Action> {
        phase = Phase.TWO_FINGER
        twoDownTime = time
        twoLastMidX = (positions[0].x + positions[1].x) / 2f
        twoLastMidY = (positions[0].y + positions[1].y) / 2f
        twoMoved = false
        scrollAcc = 0f
        return emptyList()
    }

    private fun twoFingerMove(positions: List<Pad>): List<Action> {
        val midX = (positions[0].x + positions[1].x) / 2f
        val midY = (positions[0].y + positions[1].y) / 2f
        val dx = midX - twoLastMidX
        val dy = midY - twoLastMidY
        twoLastMidX = midX
        twoLastMidY = midY
        if (distance(0f, 0f, dx, dy) > TAP_SLOP_PX) twoMoved = true
        // Fingers up (dy < 0) must produce a positive wheel delta with the
        // traditional direction, hence the subtraction; DIR = -1 flips to natural.
        scrollAcc -= dy * SCROLL_DIRECTION
        val ticks = (scrollAcc / SCROLL_TICK_PX).toInt()
        if (ticks == 0) return emptyList()
        scrollAcc -= ticks * SCROLL_TICK_PX
        return listOf(Action.Scroll(ticks))
    }

    private fun endTwoFinger(time: Long, positions: List<Pad>): List<Action> {
        val actions = mutableListOf<Action>()
        // Right-click only when BOTH fingers lift quickly and stayed still; a
        // scroll or a lingering finger is not a click.
        if (positions.isEmpty() && time - twoDownTime <= TAP_MAX_MS && !twoMoved) {
            actions += Action.Click(MouseReport.Button.RIGHT)
        }
        reset()
        return actions
    }

    private fun reset() {
        phase = Phase.IDLE
        moveAccX = 0f
        moveAccY = 0f
        scrollAcc = 0f
        twoMoved = false
    }

    // ---------- movement math ----------

    /**
     * Accumulates a pixel delta and turns it into whole report-count steps.
     * Steps beyond the int8 range are split into several reports, so a flick
     * never saturates (the report builder also clamps, but that would lose
     * motion; splitting preserves it).
     */
    private fun emitMoves(dx: Float, dy: Float): List<Action> {
        moveAccX += dx * sensitivity
        moveAccY += dy * sensitivity
        val stepX = moveAccX.toInt() // truncates toward zero; the remainder keeps its sign
        val stepY = moveAccY.toInt()
        moveAccX -= stepX
        moveAccY -= stepY
        val actions = mutableListOf<Action>()
        var restX = stepX
        var restY = stepY
        while (restX != 0 || restY != 0) {
            val x = restX.coerceIn(-MAX_STEP, MAX_STEP)
            val y = restY.coerceIn(-MAX_STEP, MAX_STEP)
            actions += Action.Move(x, y)
            restX -= x
            restY -= y
        }
        return actions
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    companion object {

        /** Pointer sensitivity multiplier (settings: 慢/中/快). */
        const val DEFAULT_SENSITIVITY = 1f

        /** A down..up pair inside this duration with no slop escape is a tap. */
        const val TAP_MAX_MS = 200L

        /** Tap travel budget in view pixels (a tap may wiggle, a drag may not). */
        const val TAP_SLOP_PX = 24f

        /** Rest duration after which a slide starts a held-left-button drag. */
        const val LONG_PRESS_MS = 300L

        /** Two-finger travel (pixels) per wheel tick. */
        const val SCROLL_TICK_PX = 40f

        /** +1 = traditional trackpad (fingers up scrolls up); flip to -1 if the host feel is inverted. */
        const val SCROLL_DIRECTION = 1

        /** One Move action never exceeds the int8 report field. */
        const val MAX_STEP = 127
    }
}
