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
 * Renders any [KeyboardLayout] and reports key events to the caller.
 *
 * Two different key semantics, matching what a soft keyboard must do:
 * - **ordinary keys**: a tap is one keystroke (`onKeyTap`). The key must NOT stay
 *   down after the finger lifts - users read a held-down key as a bug ("I tapped it
 *   and it is still pressed"). Repeated characters come from repeated taps.
 * - **modifier keys**: a latch (`onKeyDown` to latch, `onKeyUp` to unlatch) with the
 *   visual state following the latch, because "hold Shift while typing a letter" is
 *   a real need and a touch screen cannot hold a modifier reliably.
 *
 * The view knows nothing about HID: it only asks `key.usage` whether a key is a
 * modifier. Turning a tap into reports lives in the session layer.
 */
@Composable
internal fun KeyboardView(
    layout: KeyboardLayout,
    modifier: Modifier = Modifier,
    onKeyTap: (KeySpec) -> Unit = {},
    onModifierChanged: (KeySpec, Boolean) -> Unit = { _, _ -> },
) {
    // Latched modifiers (tap once = on, tap again = off).
    var latched by remember(layout.id) { mutableStateOf(emptySet<Int>()) }

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
                        // A modifier lights up for as long as it is latched; an
                        // ordinary key lights up only while the finger is down.
                        momentary = !isModifier,
                        onTap = {
                            if (isModifier) {
                                val usage = key.usage ?: return@KeyBox
                                val turningOn = usage !in latched
                                latched = if (turningOn) latched + usage else latched - usage
                                onModifierChanged(key, turningOn)
                            } else {
                                onKeyTap(key)
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
    lit: Boolean,
    /** True: highlight only while the finger is down. False: highlight while latched. */
    momentary: Boolean,
    onTap: () -> Unit,
) {
    val weight = key.widthUnits.coerceAtLeast(0.1f)
    // A momentary key tracks the finger; a latching key tracks its own state.
    var fingerDown by remember(key.keyCode) { mutableStateOf(false) }
    val pressed = if (momentary) fingerDown else lit

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
                        fingerDown = true
                        // Fire on press so the keystroke does not wait for the finger
                        // to lift (a soft keyboard must feel immediate), then always
                        // clear the highlight - even if the gesture is cancelled.
                        onTap()
                        tryAwaitRelease()
                        fingerDown = false
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
