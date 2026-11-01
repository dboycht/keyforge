package com.dboycht.keyforge.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dboycht.keyforge.R
import com.dboycht.keyforge.hid.ConsumerUsage
import com.dboycht.keyforge.hid.MouseReport
import com.dboycht.keyforge.session.HidSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The touchpad mode surface: a media key row on top, a pointer area below.
 *
 * Pointer handling is delegated to [TouchpadEngine] (pure Kotlin, unit tested);
 * this composable only translates Compose pointer events into engine calls and
 * engine actions into session sends - the same thin-UI-over-state-machine split
 * the keyboard grid uses around [AutoRepeater].
 *
 * All sends go through [keyDispatcher] (the single-worker dispatcher the keys
 * use), so mouse reports can never overtake or interleave with keyboard reports
 * mid-keystroke.
 */
@Composable
internal fun TouchpadSurface(
    session: HidSession,
    scope: CoroutineScope,
    keyDispatcher: CoroutineDispatcher,
    sensitivity: Float,
    haptics: Boolean,
    palette: KeyboardPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        MediaKeyRow(
            session = session,
            scope = scope,
            keyDispatcher = keyDispatcher,
            haptics = haptics,
            palette = palette,
        )
        Spacer(Modifier.height(6.dp))
        TouchpadArea(
            session = session,
            scope = scope,
            keyDispatcher = keyDispatcher,
            sensitivity = sensitivity,
            palette = palette,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** The media keys: one tap sends one consumer-page code (Report ID 3). */
@Composable
private fun MediaKeyRow(
    session: HidSession,
    scope: CoroutineScope,
    keyDispatcher: CoroutineDispatcher,
    haptics: Boolean,
    palette: KeyboardPalette,
) {
    val view = LocalView.current
    val keys = listOf(
        TouchpadMediaKey(R.string.touchpad_key_mute, ConsumerUsage.MUTE),
        TouchpadMediaKey(R.string.touchpad_key_volume_down, ConsumerUsage.VOLUME_DOWN),
        TouchpadMediaKey(R.string.touchpad_key_volume_up, ConsumerUsage.VOLUME_UP),
        TouchpadMediaKey(R.string.touchpad_key_play_pause, ConsumerUsage.PLAY_PAUSE),
        TouchpadMediaKey(R.string.touchpad_key_prev, ConsumerUsage.SCAN_PREVIOUS_TRACK),
        TouchpadMediaKey(R.string.touchpad_key_next, ConsumerUsage.SCAN_NEXT_TRACK),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { key ->
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(palette.modifierFill)
                    .clickable {
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        scope.launch(keyDispatcher) { session.tapConsumer(key.code) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(key.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.modifierText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A media key definition: the label shown on the button and the consumer code it sends. */
private data class TouchpadMediaKey(val labelRes: Int, val code: Int)

/**
 * The pointer area itself: drag to move, tap to click, two fingers to scroll
 * and right-click. Gesture semantics live in [TouchpadEngine].
 */
@Composable
private fun TouchpadArea(
    session: HidSession,
    scope: CoroutineScope,
    keyDispatcher: CoroutineDispatcher,
    sensitivity: Float,
    palette: KeyboardPalette,
    modifier: Modifier = Modifier,
) {
    val hint = stringResource(R.string.keyboard_touchpad_hint)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.keyFill.copy(alpha = 0.3f))
            .border(1.dp, palette.keyFill.copy(alpha = 0.5f), shape)
            .pointerInput(sensitivity) {
                val engine = TouchpadEngine(sensitivity)
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val primaryId = first.id

                    // Active pointer positions, gesture-starting pointer first
                    // (the engine's contract; Compose's change order is by id).
                    fun positionsOf(event: PointerEvent): List<TouchpadEngine.Pad> {
                        val pressed = event.changes.filter { it.pressed }
                        val primary = pressed.firstOrNull { it.id == primaryId }
                        return (listOfNotNull(primary) + pressed.filter { it.id != primaryId })
                            .map { TouchpadEngine.Pad(it.position.x, it.position.y) }
                    }

                    try {
                        sendToSession(scope, keyDispatcher, session,
                            engine.onPointerDown(first.uptimeMillis, positionsOf(currentEvent)))
                        while (true) {
                            val event = awaitPointerEvent()
                            val allUp = event.changes.all { !it.pressed }
                            val anyUp = event.changes.any { !it.pressed }
                            if (allUp) {
                                sendToSession(scope, keyDispatcher, session,
                                    engine.onPointerUp(event.changes.first().uptimeMillis, emptyList()))
                                break
                            }
                            if (anyUp) {
                                // 2 -> 1: the gesture ends without action; the
                                // remaining finger is ignored until all lift.
                                sendToSession(scope, keyDispatcher, session,
                                    engine.onPointerUp(event.changes.first().uptimeMillis, positionsOf(event)))
                                continue
                            }
                            sendToSession(scope, keyDispatcher, session,
                                engine.onPointerMove(event.changes.first().uptimeMillis, positionsOf(event)))
                        }
                    } finally {
                        // Normal end: engine is idle, this is a no-op. Cancelled
                        // gesture (composition gone, pointer stream aborted): a
                        // held left button MUST be released - a stuck button is
                        // exactly the "gesture end is not a key up" bug class
                        // this project already paid for once (ERROR.md E10/E11).
                        sendToSession(scope, keyDispatcher, session, engine.onCancel())
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = palette.keyText.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Performs the engine's actions against the session, in order, on the key dispatcher. */
private fun sendToSession(
    scope: CoroutineScope,
    keyDispatcher: CoroutineDispatcher,
    session: HidSession,
    actions: List<TouchpadEngine.Action>,
) {
    for (action in actions) {
        when (action) {
            is TouchpadEngine.Action.Move ->
                scope.launch(keyDispatcher) { session.moveMouse(action.x, action.y) }
            is TouchpadEngine.Action.Scroll ->
                scope.launch(keyDispatcher) { session.scrollWheel(action.wheel) }
            is TouchpadEngine.Action.Click ->
                scope.launch(keyDispatcher) { session.tapMouseButton(action.button) }
            TouchpadEngine.Action.HoldStart ->
                scope.launch(keyDispatcher) { session.pressMouseButton(MouseReport.Button.LEFT) }
            TouchpadEngine.Action.HoldEnd ->
                scope.launch(keyDispatcher) { session.releaseMouseButton(MouseReport.Button.LEFT) }
        }
    }
}
