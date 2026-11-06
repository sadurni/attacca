package com.sadurni.attacca

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why Attacca closed when nobody closed it, written to `Music/Attacca/attacca-exits.txt` for the
 * user to send: what Android says of each past exit (out of memory, killed for memory, an error,
 * not answering…), read at the next start, and the error itself when the app fails.
 */
object ExitLog {

    private const val FILE = "attacca-exits.txt"
    private const val MAX_BYTES = 256 * 1024
    private const val PREFS = "exit_log"
    private const val KEY_SEEN = "seen"

    private fun file(): File = File(com.sadurni.attacca.library.UserLists.directory, FILE)

    /** Keeps the error of an app failure before the process ends. Call once, at the start. */
    fun watchFailures(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val app = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val runtime = Runtime.getRuntime()
                val memory = "heap ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} of ${runtime.maxMemory() shr 20} MB"
                append(app, "${now()}  error on thread ${thread.name} ($memory)\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes the exits Android remembers that were not written yet. Blocking: off the main thread. */
    fun recordPastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull().orEmpty()
            .filter { it.timestamp > seen && it.reason !in ORDINARY }
            .sortedBy { it.timestamp }
        val newest = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1) }.getOrNull()?.firstOrNull()?.timestamp
        for (e in exits) {
            val text = buildString {
                append(stamp(e.timestamp)).append("  ").append(reasonName(e.reason))
                e.description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                append("  (memory ").append(e.pss shr 10).append(" MB, resident ").append(e.rss shr 10).append(" MB")
                append(", ").append(if (e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) "in use" else "in the background").append(")")
            }
            append(context, text)
        }
        if (newest != null && newest > seen) prefs.edit().putLong(KEY_SEEN, newest).apply()
    }

    /** Notes something worth knowing about a start (e.g. the music not reachable yet). */
    fun note(context: Context, text: String) {
        runCatching { append(context.applicationContext, "${now()}  $text") }
    }

    private fun append(context: Context, entry: String) {
        val f = file()
        f.parentFile?.mkdirs()
        // Only the latest: the oldest half goes once it grows too big.
        if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
        f.appendText("$entry  [${context.packageManager.getPackageInfo(context.packageName, 0).versionName}]\n")
    }

    private fun now(): String = stamp(System.currentTimeMillis())

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    /** Exits that are no news: the user or the app itself closing it, an update. */
    private val ORDINARY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        setOf(ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)
    } else {
        emptySet()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by the system for lack of memory"
        ApplicationExitInfo.REASON_CRASH -> "app error"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native error"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much resource use"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $reason"
    }
}
