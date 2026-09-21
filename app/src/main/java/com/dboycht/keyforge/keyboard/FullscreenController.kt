package com.dboycht.keyforge.keyboard

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Turns "full screen keyboard" on and off for an [Activity].
 *
 * Why a separate class: hiding the system bars has several details that are easy to get
 * subtly wrong (which controller, which behavior, what happens when the window regains
 * focus or the user swipes). Keeping it here means the screens only express intent.
 *
 * Full screen here means: **the app owns the whole screen, and the system bars come back
 * on a swipe**. That "come back on a swipe" part is the escape hatch - a keyboard that
 * hides the bars and offers no way out is a trap, so the UI also shows its own
 * "exit full screen" control.
 */
internal class FullscreenController(private val activity: Activity) {

    private val controller: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    /** True while the system bars are hidden by us. */
    var isFullscreen: Boolean = false
        private set

    /**
     * Applies [enabled] and remembers it.
     *
     * Cutout mode is left alone deliberately: forcing `SHORT_EDGES` on every device is a
     * known source of "content under the camera hole" bugs, and the keyboard layout does
     * not need it.
     */
    fun set(enabled: Boolean) {
        isFullscreen = enabled
        val c = controller
        if (enabled) {
            // Transient bars = the user can still swipe them back temporarily.
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Re-applies the current state. Call from `onWindowFocusChanged(true)`: the system can
     * restore the bars (for example after a permission dialog or returning from the
     * launcher), and the window is the only thing that knows it happened.
     */
    fun reapply() = set(isFullscreen)
}
