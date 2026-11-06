package com.sadurni.attacca.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Folders and files left out of Music and of every list Attacca makes by itself (generated, smart,
 * endless, by listening, the next folder, a folder played with its subfolders), whatever the
 * inclusion list says. Unlike the inclusion list, which only filters the lists that ask for it,
 * these are never taken. They are still scanned and analysed (a list can be made from one of
 * them), shown and found in Folders, and kept in the user's own lists.
 */
object IgnoredPaths {

    @Volatile
    private var paths: List<String> = emptyList()

    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all: StateFlow<List<String>> = _all.asStateFlow()

    /** Changes each time the list does (for caches built from the music folder). */
    @Volatile
    var version = 0
        private set

    private const val PREFS = "ignored_paths"
    private const val KEY = "all"

    fun load(context: Context) {
        val list = runCatching {
            val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        set(list)
    }

    private fun set(list: List<String>) {
        paths = list.map { it.trimEnd('/') }
        version++
        _all.value = list
    }

    fun isIgnored(path: String): Boolean {
        val current = paths
        if (current.isEmpty()) return false
        return current.any { path == it || path.startsWith("$it/") }
    }

    /** Ignores [path] (a folder or a file), or stops ignoring it; returns whether it is ignored now. */
    fun toggle(context: Context, path: String): Boolean {
        val clean = path.trimEnd('/')
        val now = if (clean in _all.value) _all.value - clean else _all.value + clean
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(now).toString()).apply()
        set(now)
        AudioFiles.forgetListing()
        return clean in now
    }
}
