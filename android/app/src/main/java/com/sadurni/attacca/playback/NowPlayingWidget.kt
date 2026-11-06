package com.sadurni.attacca.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.SizeF
import android.view.KeyEvent
import android.widget.RemoteViews
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.MainActivity
import java.io.File

/**
 * The "Now playing" home-screen widget: cover, title and artist, previous / play-pause / next.
 * One row, or two rows high with a larger cover (Android 12+ picks the layout by size).
 *
 * The playback service pushes each change ([update]); the last state is kept so the widget can
 * be drawn again while the app is closed. The buttons go straight to the playback service as
 * media buttons, so they also start playback with the app closed (it resumes what played last).
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds)
    }

    companion object {
        private const val PREFS = "widget"
        private const val ART_FILE = "widget_art.png"

        /** The service's state for the widgets: shown at once on every widget placed. */
        fun update(context: Context, title: String?, subtitle: String?, playing: Boolean, art: Bitmap?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", title)
                .putString("subtitle", subtitle)
                .putBoolean("playing", playing)
                .apply()
            val file = File(context.filesDir, ART_FILE)
            if (art != null) {
                runCatching { file.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            } else {
                file.delete()
            }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, art)
        }

        /** Only the play/pause state changed: no need to touch the cover. */
        fun updatePlaying(context: Context, playing: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("playing", false) == playing && prefs.contains("playing")) return
            prefs.edit().putBoolean("playing", playing).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, art: Bitmap? = null) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString("title", null) ?: context.getString(R.string.app_name)
            val subtitle = prefs.getString("subtitle", null).orEmpty()
            val playing = prefs.getBoolean("playing", false)
            val cover = art ?: File(context.filesDir, ART_FILE).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
            fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_subtitle, subtitle)
                if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_note)
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.action_pause else R.string.action_play))
                setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                setOnClickPendingIntent(R.id.widget_previous, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
            }
            val remote = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The launcher picks the largest layout that fits.
                RemoteViews(
                    mapOf(
                        SizeF(180f, 40f) to views(R.layout.widget_now_playing),
                        SizeF(250f, 110f) to views(R.layout.widget_now_playing_large),
                    ),
                )
            } else {
                views(R.layout.widget_now_playing)
            }
            manager.updateAppWidget(ids, remote)
        }

        /** A media button for the playback service (the session handles it, as from a headset). */
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getForegroundService(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
