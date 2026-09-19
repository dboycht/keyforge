package com.dboycht.keyforge.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
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

/** Height the grid asks for: the tallest shipped layout (5 rows) at [KeyUnit]. */
internal val KeyboardGridHeight = KeyUnit * 5 * 1.12f

/** Height of one key row. */
private val KeyRowHeight = KeyUnit * 1.12f

/** Label size derived from the key unit, so both layouts stay legible. */
private val KeyLabelSize = 14.sp
private val ShiftLabelSize = 10.sp

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
 * Three key behaviours, matching a soft keyboard that must stay usable:
 * - **ordinary keys**: a tap is one keystroke ([onKeyTap]). The key must NOT stay
 *   lit after the finger lifts - a stuck-looking key reads as a bug. Repeated
 *   characters come from repeated taps.
 * - **repeatable keys** (Backspace, arrows, space...): tap once immediately, and
 *   if the finger stays down, keep sending after a short delay - holding Backspace
 *   must delete continuously, exactly like a hardware keyboard.
 * - **modifier keys**: a latch ([onModifierChanged]) with the highlight following
 *   the latch, because "hold Shift while typing a letter" is a real need and a
 *   touch screen cannot hold a modifier reliably.
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        layout.rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KeyRowHeight),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                            // Physical-keyboard modifier: releasing the finger releases the
                            // modifier. In latch mode the modifier stays until tapped again,
                            // so nothing is released here.
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

@Composable
private fun RowScope.KeyBox(
    key: KeySpec,
    /** Latched modifier: highlight follows the latch, not the finger. */
    lit: Boolean,
    /** Finger currently down on this key (highlight for non-modifiers). */
    fingerDown: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    val weight = key.widthUnits.coerceAtLeast(0.1f)
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
                fontSize = KeyLabelSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            key.shiftLabel?.let { shift ->
                Text(
                    text = shift,
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = ShiftLabelSize,
                    maxLines = 1,
                )
            }
        }
    }
}
