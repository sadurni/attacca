package com.sadurni.attacca.library

import android.content.Context
import org.json.JSONArray

/** Folders the user keeps at hand: shown under the storages at the top of Folders, in the order added. */
object FavoriteFolders {

    private const val PREFS = "favorite_folders"
    private const val KEY = "all"

    fun all(context: Context): List<String> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    fun contains(context: Context, path: String): Boolean = path in all(context)

    /** Adds [path], or takes it out when it is there; returns whether it is a favorite now. */
    fun toggle(context: Context, path: String): Boolean {
        val current = all(context)
        val now = if (path in current) current - path else current + path
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(now).toString()).apply()
        return path in now
    }
}
