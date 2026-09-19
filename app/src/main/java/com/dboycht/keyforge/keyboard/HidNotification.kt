package com.dboycht.keyforge.keyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dboycht.keyforge.R
import com.dboycht.keyforge.session.SessionPhase
import com.dboycht.keyforge.session.SessionUiState

/**
 * The ongoing notification for a keyboard session.
 *
 * Kept apart from the service so the wording and the channel setup can change
 * without touching session logic, and so a later unit test can assert the text for
 * each [SessionPhase] without an Android runtime.
 */
internal object HidNotification {

    const val CHANNEL_ID = "keyforge.session"
    const val NOTIFICATION_ID = 1001

    const val ACTION_STOP = "com.dboycht.keyforge.action.STOP"
    const val ACTION_DISCONNECT = "com.dboycht.keyforge.action.DISCONNECT"

    /** Creates the channel once; safe to call repeatedly (Android ignores duplicates). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.session_channel_name),
            // LOW: a keyboard session should be visible but must never make noise.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.session_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Title for the current phase, e.g. "键盘已连接" / "正在注册为键盘…". */
    fun titleFor(context: Context, state: SessionUiState): String = when (state.phase) {
        SessionPhase.Registered -> if (state.connectedDevices.isEmpty()) {
            context.getString(R.string.session_title_ready)
        } else {
            context.getString(R.string.session_title_connected)
        }
        SessionPhase.Registering, SessionPhase.ProfileConnecting ->
            context.getString(R.string.session_title_connecting)
        SessionPhase.PermissionMissing -> context.getString(R.string.session_title_no_permission)
        SessionPhase.BluetoothOff -> context.getString(R.string.session_title_bt_off)
        SessionPhase.ProfileUnavailable -> context.getString(R.string.session_title_unsupported)
        SessionPhase.Failed -> context.getString(R.string.session_title_failed)
        SessionPhase.Idle -> context.getString(R.string.session_title_idle)
    }

    /** Body text: which host is connected, or what the user should do next. */
    fun textFor(context: Context, state: SessionUiState): String = when {
        state.connectedDevices.isNotEmpty() ->
            context.getString(R.string.session_text_hosts, state.connectedDevices.joinToString(", "))
        state.phase == SessionPhase.Registered ->
            context.getString(R.string.session_text_waiting)
        state.phase == SessionPhase.BluetoothOff ->
            context.getString(R.string.session_text_enable_bt)
        state.phase == SessionPhase.PermissionMissing ->
            context.getString(R.string.session_text_grant)
        else -> state.log.lastOrNull() ?: context.getString(R.string.session_text_starting)
    }

    /** Builds the ongoing notification; [contentIntent] opens the keyboard screen. */
    fun build(context: Context, state: SessionUiState, contentIntent: PendingIntent?): Notification {
        val disconnect = PendingIntent.getService(
            context,
            2,
            Intent(context, KeyboardService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            3,
            Intent(context, KeyboardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard_notification)
            .setContentTitle(titleFor(context, state))
            .setContentText(textFor(context, state))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, context.getString(R.string.session_action_disconnect), disconnect)
            .addAction(0, context.getString(R.string.session_action_stop), stop)

        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }
}
