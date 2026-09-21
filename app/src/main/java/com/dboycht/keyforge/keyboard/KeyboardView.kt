package com.dboycht.keyforge.keyboard

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
import kotlinx.coroutines.launch

/** Width of one key unit; the whole keyboard scales from this single number. */
internal val KeyUnit = 38.dp

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
 * Label size for a key of [unit] size: scaled with the unit, so a shrunk grid stays
 * readable instead of keeping a fixed 14sp that would overflow a 24dp key.
 */
private fun labelSizeFor(unit: Dp): TextUnit = (unit.value * 0.36f).coerceIn(9f, 17f).sp

/**
 * Auto-repeat is configured per key by [KeySpec.supportsAutoRepeat]; the timing lives
 * in [KeySpec.AUTO_REPEAT_DELAY_MS] / [KeySpec.AUTO_REPEAT_INTERVAL_MS] so the data
 * layer owns the contract and the view just honours it.
 */

private const val HighlightTag = "KeyForgeHighlight"

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
    onKeyTap: (KeySpec) -> Unit = {},
    onKeyRepeat: (KeySpec) -> Unit = {},
    /** True: modifiers latch (phone-keyboard style). False: physical-keyboard style. */
    modifierLatch: Boolean = false,
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

        // How much taller than wide a row may be. Few columns (a 10-unit phone layout) get
        // slightly taller keys, many columns get the flatter key-cap look; either way rows stay
        // under 1.25x so keys never become squares again.
        val rowHeightFactor = if (layout.widthUnits <= 11f) 1.05f else 0.95f

        // The tallest the unit may be without the grid overflowing the height budget.
        val unitFromHeight = (maxHeight - gapsAndPadding) / (rows * rowHeightFactor)
        val unit = minOf(unitFromWidth, unitFromHeight).coerceAtLeast(MinKeyUnit)

        val heightPerRow = (maxHeight - gapsAndPadding) / rows
        val rowHeight = minOf(unit * rowHeightFactor, heightPerRow)

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
            layout.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(RowGap),
                ) {
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
                            labelSp = labelSizeFor(unit),
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
                                    // Immediate first keystroke; the repeat loop (started
                                    // here for repeatable keys) takes over if the finger stays.
                                    onKeyTap(key)
                                    if (key.supportsAutoRepeat) {
                                        repeatJob?.cancel()
                                        repeatJob = gestureScope.launch {
                                            delay(KeySpec.AUTO_REPEAT_DELAY_MS)
                                            while (true) {
                                                onKeyRepeat(key)
                                                delay(KeySpec.AUTO_REPEAT_INTERVAL_MS)
                                            }
                                        }
                                    }
                                }
                            },
                            onPressEnd = {
                                repeatJob?.cancel()
                                repeatJob = null
                                pressed = pressed - key.keyCode
                                // Physical-keyboard modifier: releasing the finger releases
                                // the modifier. In latch mode it stays until tapped again.
                                if (isModifier && !modifierLatch) {
                                    onModifierChanged(key, false)
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
    /** Label size in sp, scaled with the key unit so small grids stay readable. */
    labelSp: TextUnit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
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
                    fontSize = labelSp * 0.68f,
                    maxLines = 1,
                )
            }
        }
    }
}
