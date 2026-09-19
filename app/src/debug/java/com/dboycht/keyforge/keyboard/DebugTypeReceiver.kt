package com.dboycht.keyforge.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dboycht.keyforge.probe.ProbeConstants
import com.dboycht.keyforge.session.HidSessionManager

/**
 * Debug-only adb hook for typing through a *backgrounded* session.
 *
 * Declared in `src/debug/AndroidManifest.xml`, so it is absent from release builds.
 * It exists to answer one question on real hardware that nothing else can answer
 * cheaply: "after leaving the app, does the keyboard still send keystrokes?".
 *
 *     adb shell am broadcast -a com.dboycht.keyforge.action.DEBUG_TYPE --ei usage 4
 *
 * Guarded by a signature-level permission in the debug manifest so no other app
 * can inject input.
 */
internal class DebugTypeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val usage = intent.getIntExtra(KeyboardService.EXTRA_USAGE, 0x04)
        val keyCode = intent.getIntExtra("keyCode", android.view.KeyEvent.KEYCODE_A)
        val session = HidSessionManager.peek()
        val result = session?.sendKey(keyCode, usage)
        android.util.Log.i(
            ProbeConstants.TAG,
            "session: adb type keyCode=$keyCode usage=0x%02X -> %s".format(usage, result),
        )
    }
}
