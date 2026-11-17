package com.sadurni.attacca.chain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * A list to wake up to at a set time: the system's alarm clock wakes Attacca then, which plays a
 * list rising in energy for [Wake.minutes] with the volume coming up (see [ChainLists.Kind.WAKE]).
 * Once, or every day. Set again after a restart of the phone.
 *
 * Android does not let an alarm open the app's screen from the background, and gives an app woken
 * by its alarm only a few seconds to start playing: so the list is made ahead (when the alarm is
 * set, and after each time it goes off) and kept in a file, and [Fire] plays it without a screen.
 */
object WakeAlarm {

    /** The alarm as set: [hour]:[minute], a list of [minutes], every day or once; off when not [on]. */
    data class Wake(val on: Boolean = false, val hour: Int = 7, val minute: Int = 30, val minutes: Int = 30, val daily: Boolean = true)

    const val ACTION_WAKE = "com.sadurni.attacca.action.WAKE"
    private const val TAG = "WakeAlarm"

    /** Without a list made ahead, how long making one may take (the alarm gives only seconds to start). */
    private const val MAKE_NOW_MS = 6_000L
    private const val HOLD_TIMEOUT_MS = 10_000L
    private const val PREFS = "wake_alarm"
    private const val REQUEST = 2020

    private val _wake = MutableStateFlow(Wake())
    private val wake: StateFlow<Wake> = _wake.asStateFlow()

    @Volatile
    private var loaded = false

    /** The alarm as kept (read once). */
    fun state(context: Context): StateFlow<Wake> {
        if (!loaded) {
            _wake.value = read(context)
            loaded = true
        }
        return wake
    }

    private fun read(context: Context): Wake {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Wake(p.getBoolean("on", false), p.getInt("hour", 7), p.getInt("minute", 30), p.getInt("minutes", 30), p.getBoolean("daily", true))
    }

    /** Keeps [wake] and sets (or cancels) the system alarm for it. */
    fun set(context: Context, wake: Wake) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("on", wake.on).putInt("hour", wake.hour).putInt("minute", wake.minute)
            .putInt("minutes", wake.minutes).putBoolean("daily", wake.daily).apply()
        _wake.value = wake
        loaded = true
        schedule(context)
        if (wake.on) prepare(context)
    }

    private fun preparedFile(context: Context) = java.io.File(context.filesDir, "wake_list.txt")

    /** Makes the next list to wake up to now, in the background, and keeps it for the alarm. */
    fun prepare(context: Context) {
        val app = context.applicationContext as com.sadurni.attacca.AttaccaApp
        app.appScope.launch {
            val ids = runCatching { app.chainLists.wakeList(read(context).minutes) }.getOrNull()
            if (!ids.isNullOrEmpty()) withContext(Dispatchers.IO) { runCatching { preparedFile(context).writeText(ids.joinToString("\n")) } }
        }
    }

    /** The list made ahead, its tracks still there; empty when none. */
    private fun prepared(context: Context): List<String> =
        runCatching { preparedFile(context).readLines().filter { it.isNotBlank() && java.io.File(it).isFile } }.getOrDefault(emptyList())

    /**
     * The alarm went off: plays the list made ahead (made now when there is none, if it can be in
     * time), its volume coming up, without opening the app's screen; then makes tomorrow's.
     */
    class Fire : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_WAKE) return
            val app = context.applicationContext as com.sadurni.attacca.AttaccaApp
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                try {
                    val minutes = wentOff(context)
                    val ids = prepared(context).ifEmpty { withTimeoutOrNull(MAKE_NOW_MS) { app.chainLists.wakeList(minutes) }.orEmpty() }
                    if (ids.isEmpty()) {
                        android.util.Log.w(TAG, "nothing to wake up to")
                        return@launch
                    }
                    val items = withContext(Dispatchers.IO) { com.sadurni.attacca.library.MediaItems.fromFiles(ids.map { java.io.File(it) }) }
                    if (items.isEmpty()) return@launch
                    app.ungroupUnsavedList()
                    app.noteListInclusion(null, app.chainLists.wakeInclusion())
                    app.setCurrentList(context.getString(com.sadurni.attacca.R.string.list_kind_wake), null)
                    val token = androidx.media3.session.SessionToken(app, android.content.ComponentName(app, com.sadurni.attacca.playback.PlaybackService::class.java))
                    // The app's context: a receiver's own may not bind to the player.
                    val controller = androidx.media3.session.MediaController.Builder(app, token).buildAsync().await()
                    try {
                        controller.setMediaItems(items)
                        controller.prepare()
                        // Soft from the start: the level is set as soon as the player holds the list.
                        withTimeoutOrNull(HOLD_TIMEOUT_MS) {
                            while (true) {
                                val extras = app.extras.value
                                if (extras != null && extras.holds(items.first().mediaId, items.size)) {
                                    extras.wakeUp(minutes * 60_000L)
                                    break
                                }
                                delay(100)
                            }
                        }
                        controller.play()
                        // Bound until it plays (and the player is in the foreground).
                        withTimeoutOrNull(HOLD_TIMEOUT_MS) { while (!controller.isPlaying) delay(100) }
                        android.util.Log.i(TAG, "playing ${items.size} tracks: ${controller.isPlaying}")
                    } finally {
                        controller.release()
                    }
                    if (read(context).on) prepare(context)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "could not wake up", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** The next time the alarm goes off (wall-clock ms), or null when it is off. */
    fun nextAt(wake: Wake, nowMs: Long = System.currentTimeMillis()): Long? {
        if (!wake.on) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, wake.hour)
            set(Calendar.MINUTE, wake.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Sets the system alarm as kept (after a change, a restart of the phone, or once it went off). */
    fun schedule(context: Context) {
        val wake = read(context)
        _wake.value = wake
        loaded = true
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, Fire::class.java).setAction(ACTION_WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // What the phone's clock opens when the alarm is tapped: just the app.
        val show = PendingIntent.getActivity(
            context,
            REQUEST,
            Intent(context, com.sadurni.attacca.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = nextAt(wake)
        if (at == null) {
            alarms.cancel(operation)
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
        }
    }

    /** It went off: once is done, every day is set for tomorrow. Returns the list's minutes. */
    fun wentOff(context: Context): Int {
        val wake = read(context)
        if (!wake.daily) set(context, wake.copy(on = false)) else schedule(context)
        return wake.minutes
    }

    /** Whether the alarm can go off at the exact time (Android 12: the user may have to allow it). */
    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Sets the alarm again after a restart of the phone or an update of the app. */
    class Restore : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            ) {
                schedule(context)
                if (read(context).on) prepare(context)
            }
        }
    }
}
