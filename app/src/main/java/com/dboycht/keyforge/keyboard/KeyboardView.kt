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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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
 * Auto-repeat timing for keys that support it. 400 ms before the first repeat is the
 * familiar desktop default (long enough that a deliberate press does not double up),
 * then 60 ms apart - roughly 16 keystrokes/second while held.
 */
private const val KeyRepeatDelayMs = 400L
private const val KeyRepeatIntervalMs = 60L

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
    onModifierChanged: (KeySpec, Boolean) -> Unit = { _, _ -> },
) {
    // Latched modifiers (tap once = on, tap again = off).
    var latched by remember(layout.id) { mutableStateOf(emptySet<Int>()) }
    // Which key codes currently have a finger down (used for the highlight).
    var pressed by remember(layout.id) { mutableStateOf(emptySet<Int>()) }

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
                    val lit = isModifier && key.usage in latched
                    KeyBox(
                        key = key,
                        lit = lit,
                        fingerDown = key.keyCode in pressed,
                        onPressStart = {
                            if (isModifier) {
                                val usage = key.usage ?: return@KeyBox
                                val turningOn = usage !in latched
                                latched = if (turningOn) latched + usage else latched - usage
                                onModifierChanged(key, turningOn)
                            } else {
                                pressed = pressed + key.keyCode
                                // Immediate first keystroke; repeats (if this key is
                                // repeatable) are driven below while the finger stays down.
                                onKeyTap(key)
                            }
                        },
                        onPressEnd = {
                            pressed = pressed - key.keyCode
                        },
                        onRepeatTick = { onKeyRepeat(key) },
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
    onRepeatTick: () -> Unit,
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
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        try {
                            if (key.supportsAutoRepeat) {
                                // Wait for the finger to stay down before repeating, but
                                // WITHOUT blocking the release: a plain `delay()` here gets
                                // cancelled when the gesture ends, and if the clearing code
                                // sat after it, the key would stay stuck in the pressed
                                // state - which is exactly the bug users reported.
                                val stillDown = withTimeoutOrNull(KeyRepeatDelayMs) {
                                    tryAwaitRelease()
                                    true
                                }
                                if (stillDown == true) {
                                    while (true) {
                                        onRepeatTick()
                                        delay(KeyRepeatIntervalMs)
                                    }
                                }
                            } else {
                                tryAwaitRelease()
                            }
                        } finally {
                            // Always runs, including when the gesture is cancelled: the
                            // highlight must never outlive the touch.
                            onPressEnd()
                        }
                    },
                )
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
