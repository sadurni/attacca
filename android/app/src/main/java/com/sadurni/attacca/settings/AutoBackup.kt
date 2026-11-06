package com.sadurni.attacca.settings

import android.content.Context
import android.util.Log
import com.sadurni.attacca.library.UserLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How often the automatic backup is made. */
enum class BackupEvery(val days: Int) { OFF(0), DAILY(1), WEEKLY(7), MONTHLY(30) }

/**
 * Backups made by themselves (the same zip as Settings → Backup), into the folder chosen (by
 * default Music/Attacca/Backups), every
 * day, week or month, keeping the last few. Checked when the app starts and when playback starts,
 * so a backup is made at the first chance after it is due; only the automatic ones are deleted.
 */
object AutoBackup {

    class State(val every: BackupEvery, val keep: Int, val lastMs: Long, val folder: String? = null)

    private const val PREFS = "auto_backup"
    private const val PREFIX = "attacca-auto-"
    const val DEFAULT_KEEP = 5

    private val _state = MutableStateFlow(State(BackupEvery.OFF, DEFAULT_KEEP, 0L))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Where they go: the folder chosen, or Music/Attacca/Backups. */
    val directory: File get() = state.value.folder?.let(::File) ?: File(UserLists.directory, "Backups")

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val every = p.getString("every", null)?.let { name -> BackupEvery.entries.firstOrNull { it.name == name } } ?: BackupEvery.OFF
        _state.value = State(every, p.getInt("keep", DEFAULT_KEEP), p.getLong("last", 0L), p.getString("folder", null))
    }

    fun set(context: Context, every: BackupEvery = state.value.every, keep: Int = state.value.keep) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("every", every.name).putInt("keep", keep).apply()
        val s = state.value
        _state.value = State(every, keep, s.lastMs, s.folder)
    }

    /** Saves them in [folder] from now on (null: the default one). */
    fun setFolder(context: Context, folder: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("folder", folder).apply()
        val s = state.value
        _state.value = State(s.every, s.keep, s.lastMs, folder)
    }

    /** Makes a backup if one is due. Blocking; call off the main thread. */
    fun runIfDue(context: Context) {
        val s = state.value
        if (s.every == BackupEvery.OFF) return
        val now = System.currentTimeMillis()
        if (s.lastMs > 0 && now - s.lastMs < s.every.days * DAY_MS - SLACK_MS) return
        runCatching {
            directory.mkdirs()
            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(now)) + ".zip"
            val file = File(directory, name)
            val partial = File(directory, "$name.part")
            partial.outputStream().use { Backup.write(context, it) }
            if (!partial.renameTo(file)) error("cannot rename $partial")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", now).apply()
            _state.value = State(s.every, s.keep, now, s.folder)
            prune(s.keep)
            Log.d(TAG, "automatic backup: ${file.name}, ${file.length() / 1024} KB")
        }.onFailure { Log.w(TAG, "automatic backup failed", it) }
    }

    /** Leaves the [keep] newest automatic backups. */
    private fun prune(keep: Int) {
        val autos = directory.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name }.orEmpty()
        autos.drop(keep.coerceAtLeast(1)).forEach { it.delete() }
    }

    private const val TAG = "Backup"
    private const val DAY_MS = 86_400_000L

    /** A backup due "every day" is made even if the app was opened a little earlier than yesterday. */
    private const val SLACK_MS = 2 * 3_600_000L
}
