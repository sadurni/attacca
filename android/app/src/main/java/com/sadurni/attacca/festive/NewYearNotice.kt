package com.sadurni.attacca.festive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A notice in the morning (16 hours before) of a new year's midnight: something big will happen in
 * O'rama, so leave Attacca open on the screen. Set again for the next new year each time the app
 * starts and each time it is shown.
 */
class NewYearNotice : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        FestiveState.init(context)
        // Told once for each new year.
        intent.getStringExtra(EXTRA_SHOW)?.let { show ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOLD, show).apply()
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.newyear_notice_channel))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.newyear_notice_title))
            .setContentText(context.getString(R.string.newyear_notice_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.newyear_notice_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTICE_MINUTES * 60_000L + FestiveSHOW_MS)
            .build()
        runCatching { manager.notify(ID, notification) }
        schedule(context)
    }

    companion object {
        private const val CHANNEL = "new_year"
        private const val ID = 7301
        private const val PREFS = "festive"
        private const val KEY_TOLD = "new_year_told"
        private const val EXTRA_SHOW = "show"

        /** How long before midnight the notice comes. */
        const val NOTICE_MINUTES = 16 * 60L
        private const val FestiveSHOW_MS = Festive.SHOW_MINUTES * 60_000L

        /**
         * Sets the notice for the next new year after [from] not told yet: [NOTICE_MINUTES] before
         * its midnight, or in a minute when that time is past already (the app opened that day).
         * Inexact by a few minutes, no special permission.
         */
        fun schedule(context: Context, from: LocalDateTime = LocalDateTime.now()) {
            var show = FestiveState.nextShow(from) ?: return
            val told = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOLD, null)
            if (told == show.toString()) show = FestiveState.nextShow(show.plusMinutes(1)) ?: return
            val zone = ZoneId.systemDefault()
            val at = maxOf(
                show.minusMinutes(NOTICE_MINUTES).atZone(zone).toInstant().toEpochMilli(),
                System.currentTimeMillis() + 60_000L,
            )
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = PendingIntent.getBroadcast(
                context, ID, Intent(context, NewYearNotice::class.java).putExtra(EXTRA_SHOW, show.toString()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
        }
    }
}
