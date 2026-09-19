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

    /**
     * When true, Shift/Ctrl/Alt/Win behave like phone-keyboard toggles (tap to latch,
     * tap again to release). When false (default) they behave like a physical keyboard:
     * held while the finger is down, released on lift.
     */
    val modifierLatch: StateFlow<Boolean> = _modifierLatch.asStateFlow()

    /** Reserved: whether to vibrate on key press. */
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    fun setModifierLatch(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_MODIFIER_LATCH, enabled) }
        _modifierLatch.value = enabled
    }

    fun setHaptics(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HAPTICS, enabled) }
        _haptics.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "keyforge.settings"
        private const val KEY_MODIFIER_LATCH = "modifier_latch"
        private const val KEY_HAPTICS = "haptics"

        @Volatile
        private var instance: KeyboardSettings? = null

        fun get(context: Context): KeyboardSettings =
            instance ?: synchronized(this) {
                instance ?: KeyboardSettings(context).also { instance = it }
            }
    }
}
