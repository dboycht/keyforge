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
 * Renders any [KeyboardLayout] and reports key down/up to [onKeyDown]/[onKeyUp].
 *
 * The view knows nothing about HID: it looks up `key.usage` only to know whether a
 * key is a modifier (which decides tap-on/tap-off behaviour). Everything that
 * turns a press into a report lives in the session layer.
 *
 * Layout math: rows are `Row`s with `weight = widthUnits`, so the horizontal grid
 * comes from Compose. The keyboard keeps a uniform key shape by fixing its height
 * from the unit width, which is why the 60% layout (15 units, 5 rows) is wider
 * and the phone layout (10 units, 5 rows) is taller per key.
 */
@Composable
internal fun KeyboardView(
    layout: KeyboardLayout,
    modifier: Modifier = Modifier,
    onKeyDown: (KeySpec) -> Unit = {},
    onKeyUp: (KeySpec) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // Non-modifier keys are held while the finger is down.
    var heldKeys by remember(layout.id) { mutableStateOf(emptySet<Int>()) }
    // Modifier keys latch: tap to turn on, tap again to turn off. This is the
    // familiar phone-keyboard behaviour and it matches the report layout, where a
    // modifier lives in byte 0 and never occupies one of the six key slots.
    var latchedModifiers by remember(layout.id) { mutableStateOf(emptySet<Int>()) }

    // Rows share the height the parent grants (see the caller's `weight`), so the
    // keyboard scales to the screen instead of forcing a fixed height.

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
                    val pressed = if (isModifier) {
                        key.usage in latchedModifiers
                    } else {
                        key.keyCode in heldKeys
                    }
                    KeyBox(
                        key = key,
                        pressed = pressed,
                        onPress = {
                            if (isModifier) {
                                val usage = key.usage ?: return@KeyBox
                                if (usage in latchedModifiers) {
                                    latchedModifiers = latchedModifiers - usage
                                    onKeyUp(key)
                                } else {
                                    latchedModifiers = latchedModifiers + usage
                                    onKeyDown(key)
                                }
                            } else {
                                heldKeys = heldKeys + key.keyCode
                                onKeyDown(key)
                            }
                        },
                        onRelease = {
                            if (!isModifier) {
                                heldKeys = heldKeys - key.keyCode
                                onKeyUp(key)
                            }
                        },
                    )
                }
            }
        }
    }

    // Font sizes come from the key-unit constants (KeyLabelSize / ShiftLabelSize)
    // rather than a mutable module-level value: global mutable UI state is a trap.
}

@Composable
private fun RowScope.KeyBox(
    key: KeySpec,
    pressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val weight = key.widthUnits.coerceAtLeast(0.1f)
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
            .pointerInput(key.keyCode, pressed) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
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
