package com.timewarpscan.nativecamera.core.preferences

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_FIRST_LAUNCH = "is_first_launch"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"
    private const val KEY_SOUND_ENABLED = "sound_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var selectedLanguage: String
        get() = prefs.getString(KEY_SELECTED_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_SELECTED_LANGUAGE, value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()
}
