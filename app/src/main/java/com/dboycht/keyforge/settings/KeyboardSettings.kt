package com.dboycht.keyforge.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the keyboard.
 *
 * Backed by `SharedPreferences` (no extra dependency for two booleans) and exposed as
 * [StateFlow]s so the Compose layer reacts to a change immediately - a settings toggle
 * whose effect needs an app restart is not a setting, it is a bug report waiting to
 * happen.
 */
internal class KeyboardSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _modifierLatch = MutableStateFlow(prefs.getBoolean(KEY_MODIFIER_LATCH, false))
    private val _haptics = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, true))
    private val _sound = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    private val _fullscreen = MutableStateFlow(prefs.getBoolean(KEY_FULLSCREEN, false))
    private val _layoutId = MutableStateFlow(prefs.getString(KEY_LAYOUT_ID, null))
    private val _themeId = MutableStateFlow(prefs.getString(KEY_THEME_ID, null))

    /**
     * When true, Shift/Ctrl/Alt/Win behave like phone-keyboard toggles (tap to latch,
     * tap again to release). When false (default) they behave like a physical keyboard:
     * held while the finger is down, released on lift.
     */
    val modifierLatch: StateFlow<Boolean> = _modifierLatch.asStateFlow()

    /**
     * Whether pressing a key buzzes the phone (default on).
     *
     * Applied by [com.dboycht.keyforge.keyboard.KeyboardView] through
     * `View.performHapticFeedback(VIRTUAL_KEY)`, so it also honours the system-wide touch-feedback
     * setting: with that off, nothing buzzes no matter what this switch says.
     */
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    /**
     * Whether pressing a key clicks (default on).
     *
     * Applied by [com.dboycht.keyforge.keyboard.KeyClickPlayer], which uses the system touch sound
     * when the system allows one and a short built-in click otherwise - so this switch always has an
     * audible effect, unlike one wired only to the system sound.
     */
    val sound: StateFlow<Boolean> = _sound.asStateFlow()

    /**
     * "Full screen keyboard": the app hides the system bars so the keyboard owns the
     * screen. Remembered, because a mode that forgets itself reads as a bug.
     */
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    /**
     * Id of the keyboard layout to show, or `null` when the user has never chosen one
     * (the caller then falls back to the default layout).
     */
    val layoutId: StateFlow<String?> = _layoutId.asStateFlow()

    /**
     * Id of the keyboard colour palette to use, or `null` when the user has never chosen one (the
     * caller then applies the default). Same contract as [layoutId]: a saved choice wins, a missing or
     * stale one degrades to the default instead of showing an empty keyboard.
     */
    val themeId: StateFlow<String?> = _themeId.asStateFlow()

    fun setModifierLatch(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_MODIFIER_LATCH, enabled) }
        _modifierLatch.value = enabled
    }

    fun setHaptics(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HAPTICS, enabled) }
        _haptics.value = enabled
    }

    fun setSound(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SOUND, enabled) }
        _sound.value = enabled
    }

    fun setFullscreen(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FULLSCREEN, enabled) }
        _fullscreen.value = enabled
    }

    fun setLayoutId(id: String?) {
        prefs.edit { putString(KEY_LAYOUT_ID, id) }
        _layoutId.value = id
    }

    fun setThemeId(id: String?) {
        prefs.edit { putString(KEY_THEME_ID, id) }
        _themeId.value = id
    }

    companion object {
        private const val PREFS_NAME = "keyforge.settings"
        private const val KEY_MODIFIER_LATCH = "modifier_latch"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SOUND = "sound"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_LAYOUT_ID = "layout_id"
        private const val KEY_THEME_ID = "theme_id"

        @Volatile
        private var instance: KeyboardSettings? = null

        fun get(context: Context): KeyboardSettings =
            instance ?: synchronized(this) {
                instance ?: KeyboardSettings(context).also { instance = it }
            }
    }
}
