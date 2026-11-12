package com.sadurni.attacca

import android.content.Context

/**
 * Whether the app keeps its logs for reports (exits, song transitions, Attacca World's song
 * changes): off unless turned on in About, so nothing is written all the time.
 */
object Logs {
    private const val PREFS = "logs"
    private const val KEY_ON = "on"

    @Volatile
    var on: Boolean = false
        private set

    fun init(context: Context) {
        on = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    }

    fun set(context: Context, value: Boolean) {
        on = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, value).apply()
    }
}
