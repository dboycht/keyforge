package com.dboycht.keyforge.keyboard

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.dboycht.keyforge.probe.ProbeConstants
import com.dboycht.keyforge.session.HidSessionManager
import com.dboycht.keyforge.session.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the keyboard session alive while the app is not in the foreground.
 *
 * Why a service: the HID registration and the host connection belong to the
 * process, not to a screen - pressing the home button must not drop the keyboard.
 * A foreground service is the only supported way to say "this work is active" on
 * modern Android, and the ongoing notification doubles as the user's "it is
 * connected" indicator (plus the disconnect/stop actions).
 *
 * Ownership rule: **this service owns the single registration**. Activities only
 * observe [HidSessionManager] and send keys; they never unregister on their own
 * (doing so would free the one global slot and silently kill the session).
 */
class KeyboardService : Service() {

    private val tag = ProbeConstants.TAG

    /** Own scope: the service, not a screen, owns the lifetime of the observation. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        HidNotification.ensureChannel(this)

        val session = HidSessionManager.get(this)
        // Enter the foreground immediately: Android requires it within a few
        // seconds of startForegroundService, and the state may still be "starting".
        startInForeground(session.state.value)
        session.start()

        // Keep the notification in step with the session.
        scope.launch {
            session.state.collectLatest { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            HidNotification.ACTION_STOP -> {
                Log.i(tag, "session: stop requested from notification")
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            HidNotification.ACTION_DISCONNECT -> {
                Log.i(tag, "session: disconnect requested from notification")
                val session = HidSessionManager.peek()
                val state = session?.state?.value
                val targets = state?.pairedDevices.orEmpty().filter {
                    it.address in (state?.connectedDevices ?: emptyList())
                }
                if (targets.isEmpty()) {
                    session?.releaseAll()
                } else {
                    targets.forEach { session?.disconnect(it.device) }
                }
            }
            ACTION_DEBUG_TYPE -> {
                // Dev hook: lets the background session be exercised from adb
                // (`am startservice ... --es ...`), which is how "does the keyboard
                // still type after leaving the app" gets verified on a real device.
                val usage = intent.getIntExtra(EXTRA_USAGE, 0x04)
                val session = HidSessionManager.peek()
                val result = session?.sendKey(android.view.KeyEvent.KEYCODE_A, usage)
                Log.i(tag, "session: debug type usage=0x%02X -> %s".format(usage, result))
            }
            else -> {
                // Plain start: make sure the session is running (idempotent).
                HidSessionManager.get(this).start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Started-only service: nothing to bind to.
        return null
    }

    override fun onDestroy() {
        Log.i(tag, "session: service destroyed")
        scope.cancel()
        // The session is intentionally NOT stopped here: a service restart (or a
        // configuration change) must not drop the keyboard. Use ACTION_STOP for that.
        super.onDestroy()
    }

    /** Fully tears the session down and removes the notification. */
    private fun stopSession() {
        HidSessionManager.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startInForeground(state: com.dboycht.keyforge.session.SessionUiState) {
        val notification = HidNotification.build(this, state, contentIntent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                HidNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(HidNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: com.dboycht.keyforge.session.SessionUiState) {
        val notification = HidNotification.build(this, state, contentIntent())
        // POST_NOTIFICATIONS may be denied on Android 13+; the service still runs,
        // only the notification is hidden. Never crash for that.
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(HidNotification.NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(tag, "session: notification update failed: ${error.message}")
        }
    }

    private fun contentIntent(): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            this,
            1,
            Intent(this, KeyboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    companion object {
        /** Dev-only hook used from adb to type while the app is in the background. */
        const val ACTION_DEBUG_TYPE = "com.dboycht.keyforge.action.DEBUG_TYPE"
        const val EXTRA_USAGE = "usage"

        /** Starts the session + foreground service; safe to call repeatedly. */
        fun start(context: android.content.Context) {
            val intent = Intent(context, KeyboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Asks the service to tear the session down. */
        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, KeyboardService::class.java).setAction(HidNotification.ACTION_STOP),
            )
        }

        /** True when the session is registered and can send keystrokes. */
        fun isRunning(): Boolean = HidSessionManager.peek()?.state?.value?.phase == SessionPhase.Registered

        /**
         * Starts the session and brings the foreground service up, if it is not
         * already active. Activities call this instead of creating their own
         * session: the platform allows one HID registration per process, so every
         * component must share [HidSessionManager].
         */
        fun ensureStarted(context: android.content.Context) {
            if (!isRunning()) start(context)
        }
    }
}
