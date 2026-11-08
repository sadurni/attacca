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
 * A notice a few minutes before a new year's midnight: something big is about to happen in
 * O'rama, so leave Attacca open on the screen. Set again for the next new year each time the app
 * starts and each time it is shown.
 */
class NewYearNotice : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        FestiveState.init(context)
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
        schedule(context, LocalDateTime.now().plusMinutes(NOTICE_MINUTES + 1))
    }

    companion object {
        private const val CHANNEL = "new_year"
        private const val ID = 7301

        /** How long before midnight the notice comes. */
        const val NOTICE_MINUTES = 10L
        private const val FestiveSHOW_MS = Festive.SHOW_MINUTES * 60_000L

        /** Sets the notice for the next new year after [from] (inexact by a few minutes, no special permission). */
        fun schedule(context: Context, from: LocalDateTime = LocalDateTime.now()) {
            val next = FestiveState.nextShow(from.plusMinutes(NOTICE_MINUTES)) ?: return
            val at = next.minusMinutes(NOTICE_MINUTES).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = PendingIntent.getBroadcast(
                context, ID, Intent(context, NewYearNotice::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
        }
    }
}
