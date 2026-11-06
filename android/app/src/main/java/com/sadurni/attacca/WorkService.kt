package com.sadurni.attacca

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app in the foreground while a long job over the music folder runs (its tag scan, its
 * analysis), under that job's progress notification. Without it, with the screen off and nothing
 * playing, the system counts the app as idle and gives it little CPU: a night's analysis went at
 * a third of its speed.
 */
class WorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must be followed by startForeground, even when the job ended meanwhile.
        val shown = latest?.let { goForeground(it) } ?: false
        if (!shown || !running) stop()
        return START_NOT_STICKY
    }

    private fun goForeground(notification: Notification): Boolean = try {
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, WorkNotifications.ID_SCAN, notification, type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not stay in the foreground: $e")
        false
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "WorkService"

        /** The job's latest notification, for the service to show. */
        @Volatile private var latest: Notification? = null

        /** A job runs and the service was started for it. */
        @Volatile private var running = false

        private val handler = Handler(Looper.getMainLooper())
        private var stopping: Runnable? = null

        /** A job over the music folder runs (or advanced): keep the app in the foreground under [shown]. */
        fun follow(context: Context, shown: Notification) {
            latest = shown
            stopping?.let(handler::removeCallbacks)
            stopping = null
            if (running) return
            running = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background on recent Android (no screen in view, no music):
                // the job goes on, only slower with the screen off.
                // Tried again with the next progress update (the app may come into view).
                Log.w(TAG, "could not start: $e")
                running = false
            }
        }

        /**
         * The job ended. The service stays a few seconds for the job that may follow (the analysis
         * after the tag scan): once stopped, with the screen off it could not start again.
         */
        fun end(context: Context) {
            if (!running || stopping != null) return
            stopping = Runnable {
                stopping = null
                running = false
                context.stopService(Intent(context, WorkService::class.java))
            }.also { handler.postDelayed(it, LINGER_MS) }
        }

        private const val LINGER_MS = 10_000L
    }
}
