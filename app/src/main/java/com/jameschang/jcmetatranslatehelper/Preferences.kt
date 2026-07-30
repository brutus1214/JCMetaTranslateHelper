package com.jameschang.jcmetatranslatehelper

import android.content.Context

object Preferences {
    private const val FILE = "helper_preferences"
    private const val SPEAK_ENABLED = "speak_enabled"
    private const val LAST_SPOKEN = "last_spoken"

    fun isSpeakEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(SPEAK_ENABLED, true)

    fun setSpeakEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(SPEAK_ENABLED, enabled).apply()
    }

    fun lastSpoken(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(LAST_SPOKEN, "") ?: ""

    fun setLastSpoken(context: Context, text: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(LAST_SPOKEN, text).apply()
    }
}
