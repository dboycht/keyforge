package com.dboycht.keyforge.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dboycht.keyforge.layout.KeyKind
import com.dboycht.keyforge.layout.KeySpec
import com.dboycht.keyforge.layout.KeyboardLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Width of one key unit; the whole keyboard scales from this single number. */
internal val KeyUnit = 38.dp

/**
 * Upper bound for the computed key unit. Without it a portrait phone (which has far more height
 * than a keyboard needs) would blow the keys up to fill the screen.
 */
private val MaxKeyUnit = 64.dp

/**
 * How much taller than wide a row may be. A key cap is never square and never a pillar: this
 * bounds the shape from both sides.
 */
private const val RowHeightFactor = 1.15f

/**
 * Lower bound for the computed key unit: keeps keys tappable in a very short window.
 *
 * There is deliberately **no upper bound**. One used to exist (64dp) and it caused a real bug:
 * in portrait it capped a key at 23dp wide, so a 15-unit layout collapsed into a strip at the
 * bottom of an otherwise empty screen. The width of the window bounds the unit and the row
 * height factor bounds the proportions, so a ceiling adds nothing but a way to be wrong.
 */
private val MinKeyUnit = 24.dp

/** Padding around the whole grid. */
private val GridPadding = 2.dp

/** Gap between rows and between keys in a row. */
private val RowGap = 2.dp

/**
 * Average glyph advance of the keyboard's label font, in em.
 *
 * Used to decide whether a label fits its key. It is an estimate on purpose: measuring the real
 * text needs a [androidx.compose.ui.text.TextMeasurer] per key, while the estimate only has to be
 * good enough to stop the clipping described in [labelSizeFor].
 */
private const val AVERAGE_GLYPH_EM = 0.62f

/** Never shrink a label below this, however narrow the key: below it the label is not readable. */
private const val MIN_LABEL_SP = 8f

/**
 * Label size for one key, given the width of one key unit in dp ([keyUnitDp]).
 *
 * ⚠️ [keyUnitDp] must be the **width**-derived unit (`maxWidth / layout.widthUnits`), not the
 * `unit` inside the grid: in a portrait window that one is raised by the available height and
 * clamped to `MaxKeyUnit`, so it can be twice the real key width. Sizing labels from it is what made
 * `Esc` render as "Es" and `Shift` as "Shi" on the device (font scale 1.0, so it was not the system
 * font size) - Compose clips an overflowing label silently, so neither the app nor a screenshot at
 * normal size shows it; it took a pixel-level crop of a device screenshot.
 *
 * Two bounds are applied: the unit-scaled size (so a whole grid scales together) and the key's own
 * inner width divided by the estimated glyph run. The layout-data test (`KeyboardsTest`) refuses a
 * long label on a narrow key, so this stays a safety net rather than the only defence.
 */
private fun labelSizeFor(keyUnitDp: Dp, label: String, widthUnits: Float, scale: Float = 1f): TextUnit {
    val byUnit = (keyUnitDp.value * 0.36f).coerceIn(9f, 17f) * scale
    // The key's inner width: its share of the row minus the gap that sits next to it and a hair of
    // padding, so the glyphs never touch the rounded corners.
    val boxDp = keyUnitDp.value * widthUnits - RowGap.value - 2f
    val byWidth = boxDp / (AVERAGE_GLYPH_EM * label.length.coerceAtLeast(1))
    return minOf(byUnit, byWidth).coerceAtLeast(MIN_LABEL_SP).sp
}

/**
 * Auto-repeat is configured per key by [KeySpec.supportsAutoRepeat]; the timing lives
 * in [KeySpec.AUTO_REPEAT_DELAY_MS] / [KeySpec.AUTO_REPEAT_INTERVAL_MS] so the data
 * layer owns the contract and the view just honours it.
 */

private const val HighlightTag = "KeyForgeHighlight"

/**
 * How often the hold-loop ticks while a key is held down.
 *
 * Short enough that the key's keep-alive (and, in the opt-in pulse mode, its repeat) lands close to
 * on time, cheap enough not to matter: a tick with nothing due does no work and sends nothing, since
 * [AutoRepeater] decides when something is actually owed to the host.
 */
private const val KEEP_ALIVE_TICK_MS = 25L

/**
 * Diagnostics for the "key stays lit" investigation (ERROR.md E10).
 *
 * A missing gesture callback leaves no trace in normal logs, so the highlight state is
 * logged explicitly. Cheap enough to keep: it only fires when the pressed set changes.
 */
private fun traceKeyHighlight(pressed: Set<Int>) {
    android.util.Log.i(HighlightTag, "pressed-set = $pressed")
}

private fun traceKeyHighlightEnd(keyCode: Int) {
    android.util.Log.i(HighlightTag, "gesture ended for keyCode=$keyCode")
}

/**
 * Renders any [KeyboardLayout] and reports key events to the caller.
 *
 * The grid scales to fit the space it is given: the unit size is derived from the
 * available width, then clamped so the whole grid also fits the available height.
 * Without the height clamp the bottom row is pushed off screen on a short landscape
 * window (measured: the Shift key ended up with an 11 px tall hit area on a 1080p
 * landscape phone, which makes the bottom row nearly untappable).
 *
 * Three key behaviours, matching a soft keyboard that must stay usable:
 * - **ordinary keys**: a tap is one keystroke ([onKeyTap]). The key must NOT stay
 *   lit after the finger lifts - a stuck-looking key reads as a bug. Repeated
 *   characters come from repeated taps.
 * - **repeatable keys**: tap once immediately, and if the finger stays down keep
 *   sending after a short delay (holding Backspace deletes continuously).
 * - **modifier keys**: physical-keyboard style by default (down while held, up on
 *   lift); [modifierLatch] switches them to phone-style tap-to-latch.
 *
 * The view knows nothing about HID: it only asks `key.usage` whether a key is a
 * modifier. Turning a press into reports lives in the session layer.
 */
@Composable
internal fun KeyboardView(
    layout: KeyboardLayout,
    modifier: Modifier = Modifier,
    /** One keystroke: down then up. Used by [RepeatMode.ONESHOT] keys. */
    onKeyTap: (KeySpec) -> Unit = {},
    /**
     * Put a key down and leave it down (no release). Used at the start of a held key, and re-sent
     * unchanged as a keep-alive so a dropped report cannot be read as a release.
     *
     * [isRepeat] is true for the keep-alive re-sends: the report is byte-identical to the first one,
     * so the caller can skip logging them (a 50ms cadence would otherwise flood the event log).
     */
    onKeyDown: (KeySpec, Boolean) -> Unit = { _, _ -> },
    /** Release a held key. Sent exactly once, when the finger lifts. */
    onKeyUp: (KeySpec) -> Unit = {},
    /** True: modifiers latch (phone-keyboard style). False: physical-keyboard style. */
    modifierLatch: Boolean = false,
    /**
     * Whether a key press makes the phone buzz.
     *
     * Off is a real choice, not a fallback: on a device held in two hands the keyboard is the only
     * feedback surface there is, and some people find the buzzing tiring - so the setting is a
     * switch next to the modifier mode, and the hint says which way it is set.
     *
     * The feedback goes through [android.view.View.performHapticFeedback] rather than a Compose
     * haptic type so it lands on the same [android.view.HapticFeedbackConstants.VIRTUAL_KEY] path a
     * system keyboard uses - including the user's system-wide "touch feedback" setting, which can
     * switch it off entirely (then this switch does nothing, which is correct).
     */
    haptics: Boolean = true,
    onModifierChanged: (KeySpec, Boolean) -> Unit = { _, _ -> },
) {
    // Latched modifiers (tap once = on, tap again = off).
    var latched by remember(layout.id) { mutableStateOf(emptySet<Int>()) }
    // Which key codes currently have a finger down (used for the highlight).
    var pressed by remember(layout.id) { mutableStateOf(emptySet<Int>()) }
    // Auto-repeat runs in the composable's own scope, so the gesture code below only
    // reports press/release and never has to manage coroutines.
    val gestureScope = rememberCoroutineScope()
    var repeatJob by remember(layout.id) { mutableStateOf<Job?>(null) }

    // Probe for the "key stays lit" bug (ERROR.md E10): the failure is a MISSING
    // callback, so nothing is logged by default. This records the pressed-set value
    // whenever it changes, which is the ground truth for the highlight.
    LaunchedEffect(pressed) { traceKeyHighlight(pressed) }

    // Fit the grid to the space granted by the parent.
    //
    // Two rules, in this order:
    //  1. the key UNIT comes from the width - that is what decides how big a key looks;
    //  2. the ROW HEIGHT follows from the unit (a key cap is slightly wider than it is tall),
    //     and the height only ever *limits* the grid, never stretches it.
    //
    // History, because this was wrong four times in a row:
    //  1. `unit * 1.12` per row with no height budget -> 63px empty band under the last row.
    //  2. "container height / rows"                   -> in portrait every key became a tall
    //     thin pillar (72dp wide, 153dp high) with the label stranded at the top.
    //  3. a fixed `MaxKeyUnit` ceiling                -> in portrait it capped the width to
    //     23dp per key, so the layout collapsed into a strip at the bottom.
    //  4. rows taller than they are wide              -> user feedback: "the portrait keyboard is
    //     a square grid, very hard to operate". Keys came out ~1:1, so it read as a grid of
    //     boxes instead of a keyboard.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rows = layout.rows.size.coerceAtLeast(1)
        val gapsAndPadding = GridPadding + RowGap * (rows - 1)

        val unitFromWidth = maxWidth / layout.widthUnits
        // The tallest the unit may be without the grid overflowing the height budget.
        val unitFromHeight = (maxHeight - gapsAndPadding) / (rows * RowHeightFactor)

        // A portrait phone has far more height than a keyboard needs, so the width alone would
        // leave most of the screen empty and the keys small. Letting the height raise the unit
        // (up to MaxKeyUnit) spreads the keys out to fill the space instead.
        //
        // ⚠️ `unit` is therefore NOT the width of a key. Keys get their width from the Row weights
        // (`maxWidth / layout.widthUnits`, i.e. [unitFromWidth]); only their HEIGHT follows `unit`.
        // Anything that has to fit inside a key - above all its label - must use [unitFromWidth],
        // which is what [labelSizeFor] is given below. Sizing labels from `unit` was the "Esc renders
        // as Es" bug: in portrait `unit` clamps to MaxKeyUnit (64dp) while the key is ~30dp wide, so
        // the label came out at 17sp and Compose clipped it without a word.
        val unit = minOf(maxOf(unitFromWidth, unitFromHeight), MaxKeyUnit)
            .coerceAtLeast(MinKeyUnit)

        val heightPerRow = (maxHeight - gapsAndPadding) / rows
        val rowHeight = minOf(unit * RowHeightFactor, heightPerRow)

        // When the rows cannot use the whole height (portrait: the window is much taller than
        // the keys need), the leftover goes ABOVE the grid so the keyboard sits at the bottom -
        // where thumbs are. Without this the keys would float in the middle of the screen.
        val anchorToBottom = rowHeight < heightPerRow

        Column(
            modifier = Modifier
                // Fills the height so the bottom-anchoring Spacer below can claim the slack.
                .fillMaxSize()
                // No bottom padding: when the rows do fill the height they reach the bottom
                // edge exactly, and reserving space below the last row would reopen the gap
                // that was measured at 63px under the bottom key row.
                .padding(start = GridPadding, end = GridPadding, top = GridPadding),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            // The slack above the keys, so the keyboard sits where thumbs are.
            if (anchorToBottom) {
                Spacer(Modifier.fillMaxWidth().weight(1f))
            }
            layout.rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(RowGap),
                ) {
                    // The row's stagger: a leading gap so this row's keys sit under the finger
                    // reach instead of lining up with every other row. This is what stops the
                    // grid from reading as a spreadsheet - see KeyboardLayout.rowOffsets.
                    val leader = layout.offsetFor(rowIndex)
                    if (leader > 0f) {
                        Spacer(modifier = Modifier.weight(leader))
                    }
                    row.forEach { key ->
                        val isModifier = key.isModifier
                        // Two modifier personalities, chosen in settings:
                        // - physical-keyboard style (default): down while held, up on lift;
                        // - latch style: tap to toggle, for people who prefer it.
                        val lit = isModifier && (if (modifierLatch) key.usage in latched else key.keyCode in pressed)
                        KeyBox(
                            key = key,
                            lit = lit,
                            fingerDown = key.keyCode in pressed,
                            haptics = haptics,
                            // Sized from the key's real WIDTH ([unitFromWidth]), never from `unit`.
                            labelSp = labelSizeFor(unitFromWidth, key.label, key.widthUnits),
                            // The shifted character is drawn smaller, and fitted to the key as well:
                            // on a one-unit key it is the only label that may not fit otherwise.
                            shiftLabelSp = key.shiftLabel?.let { shift ->
                                labelSizeFor(unitFromWidth, shift, key.widthUnits, scale = 0.68f)
                            },
                            onPressStart = {
                                if (isModifier) {
                                    pressed = pressed + key.keyCode
                                    if (modifierLatch) {
                                        val usage = key.usage ?: return@KeyBox
                                        val turningOn = usage !in latched
                                        latched = if (turningOn) latched + usage else latched - usage
                                        onModifierChanged(key, turningOn)
                                    } else {
                                        onModifierChanged(key, true)
                                    }
                                } else {
                                    pressed = pressed + key.keyCode
                                    repeatJob?.cancel()
                                    repeatJob = null

                                    when (key.repeatMode) {
                                        // One keystroke per touch. Used by keys where repeating is
                                        // meaningless (and by any layout that opts out).
                                        RepeatMode.ONESHOT -> onKeyTap(key)

                                        // Physical-keyboard behaviour, and the fix for "held keys
                                        // pulse in games": put the key **down and leave it down**.
                                        // The host generates the repeat characters itself, exactly as
                                        // it does for a USB keyboard - so a held movement key keeps
                                        // the character moving instead of stuttering.
                                        //
                                        // The repeating reports at the keep-alive cadence are
                                        // byte-identical to the first one, so the host sees "still
                                        // held", never a release. The only release is on finger-lift.
                                        RepeatMode.HOLD,
                                        RepeatMode.PULSE,
                                        -> {
                                            val repeater = AutoRepeater(
                                                schedule = if (key.repeatMode == RepeatMode.PULSE) {
                                                    RepeatSchedule.pulse()
                                                } else {
                                                    RepeatSchedule.hold()
                                                },
                                                onDown = { isRepeat -> onKeyDown(key, isRepeat) },
                                                onUp = { onKeyUp(key) },
                                            )
                                            repeater.press()
                                            repeatJob = gestureScope.launch {
                                                while (isActive) {
                                                    delay(KEEP_ALIVE_TICK_MS)
                                                    repeater.advance(KEEP_ALIVE_TICK_MS)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onPressEnd = {
                                repeatJob?.cancel()
                                repeatJob = null
                                pressed = pressed - key.keyCode
                                if (isModifier) {
                                    // Physical-keyboard modifier: releasing the finger releases
                                    // the modifier. In latch mode it stays until tapped again.
                                    if (!modifierLatch) onModifierChanged(key, false)
                                } else if (key.repeatMode != RepeatMode.ONESHOT) {
                                    // The single release for a held key. A one-shot key already
                                    // released itself in onKeyTap.
                                    onKeyUp(key)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyBox(
    key: KeySpec,
    /** Latched modifier: highlight follows the latch, not the finger. */
    lit: Boolean,
    /** Finger currently down on this key (highlight for non-modifiers). */
    fingerDown: Boolean,
    /** Whether pressing this key should buzz the phone. */
    haptics: Boolean,
    /** Label size in sp, fitted to this key (see [labelSizeFor]). */
    labelSp: TextUnit,
    /** Size for the shifted character, or `null` when the key has none. */
    shiftLabelSp: TextUnit?,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    // Read before the spacer's early return: a composable must call its hooks unconditionally, and
    // the spacer branch below returns early.
    val view = LocalView.current
    val weight = key.widthUnits.coerceAtLeast(0.1f)

    // A spacer is width and nothing else: it holds the two halves of a split layout apart, so
    // it must not be drawn (a visible box would read as a key) and must not take touches.
    if (key.kind == KeyKind.SPACER) {
        Spacer(modifier = Modifier.weight(weight))
        return
    }

    val pressed = if (key.isModifier) lit else fingerDown

    val background = when {
        pressed -> MaterialTheme.colorScheme.primary
        key.kind == KeyKind.MODIFIER -> Color(0xFF3A3A3C)
        key.kind == KeyKind.ACTION -> Color(0xFF2C2C2E)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .background(background, RoundedCornerShape(6.dp))
            .pointerInput(key.keyCode) {
                // Raw pointer handling instead of detectTapGestures.
                //
                // Why: detectTapGestures reports "the press ended" by CANCELLING the
                // onPress coroutine, and clearing the highlight in a `finally` there
                // proved unreliable for quick taps (measured: a 150 ms press left the
                // key stuck lit, while 600 ms+ was fine). Here the release is an
                // explicit branch in the same code path as the press, so the highlight
                // is cleared by a normal statement - no cancellation semantics involved.
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.changes.none { it.pressed }) continue

                        // The buzz happens on the press, once: the keep-alive re-sends that follow
                        // a held key are not new presses and must not buzz again (a 50ms cadence of
                        // vibration would be a bug, not feedback).
                        if (haptics) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        onPressStart()
                        val pointerId = down.changes.first { it.pressed }.id
                        try {
                            // Stay until this pointer lifts.
                            while (true) {
                                val move = awaitPointerEvent()
                                if (move.changes.none { it.id == pointerId && it.pressed }) break
                            }
                        } finally {
                            // Normal statement, not a cancellation side effect: this is
                            // the fix for "the key stays lit" (ERROR.md E10).
                            onPressEnd()
                            traceKeyHighlightEnd(key.keyCode)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.label,
                color = textColor,
                fontSize = labelSp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            key.shiftLabel?.let { shift ->
                Text(
                    text = shift,
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = shiftLabelSp ?: labelSp * 0.68f,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
