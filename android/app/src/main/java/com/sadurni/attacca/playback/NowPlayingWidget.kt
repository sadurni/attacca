package com.sadurni.attacca.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.MainActivity
import java.io.File

/**
 * The "Now playing" home-screen widget, in four sizes the launcher picks as it is resized:
 * - mini: the cover and play / pause;
 * - bar (one row): cover, title, artist, the time going on, previous / play-pause / next;
 * - large (two rows): a big cover, title, artist, album, what comes next, the list, the time and the buttons;
 * - O'rama (three rows or more): a still of the track's landscape, renewed as it plays, with the texts and buttons on it.
 *
 * Its card and buttons take the colours of the app's theme ([setColors]). The playback service
 * pushes each change ([update]); the last state is kept so the widget is drawn again while the app
 * is closed. The buttons go straight to the playback service as media buttons, so they also
 * start playback with the app closed (it resumes what played last).
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        render(context, manager, intArrayOf(appWidgetId))
    }

    /** The heart: the playing track into or out of Favorites, also with the app closed. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FAVORITE) {
            super.onReceive(context, intent)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("mediaId", null)?.takeIf { !it.contains("://") } ?: return
        // As the lists keep it (their paths are read in full, /sdcard as /storage/emulated/0).
        val id = runCatching { File(raw).canonicalPath }.getOrDefault(raw)
        val now = !prefs.getBoolean("favorite", false)
        prefs.edit().putBoolean("favorite", now).apply()
        renderAll(context)
        val pending = goAsync()
        Thread {
            try {
                val lists = com.sadurni.attacca.library.UserLists
                if (now) {
                    lists.append(lists.favorites, com.sadurni.attacca.library.MediaItems.fromFiles(listOf(File(id))))
                } else {
                    lists.remove(lists.favorites, id)
                }
                (context.applicationContext as? com.sadurni.attacca.AttaccaApp)?.favoritesChanged?.tryEmit(Unit)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** What the widget shows of the playing track. */
    data class Info(
        val title: String?,
        val artist: String?,
        val album: String?,
        val next: String?,
        val list: String?,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val mediaId: String? = null,
        val favorite: Boolean = false,
    )

    /** What the widget shows when made big: the cover, or O'rama's landscape. */
    enum class Big { COVER, ORAMA }

    companion object {
        private const val PREFS = "widget"
        private const val ART_FILE = "widget_art.png"
        private const val SCENE_FILE = "widget_scene.png"
        private const val ACTION_FAVORITE = "com.sadurni.attacca.widget.FAVORITE"

        /** The smallest size (dp) of the O'rama layout. */
        const val SCENE_MIN_WIDTH = 250f
        const val SCENE_MIN_HEIGHT = 180f

        /** The service's state for the widgets: shown at once on every widget placed. */
        fun update(context: Context, info: Info, art: Bitmap?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", info.title)
                .putString("artist", info.artist)
                .putString("album", info.album)
                .putString("next", info.next)
                .putString("list", info.list)
                .putBoolean("playing", info.playing)
                .putLong("position", info.positionMs)
                .putLong("duration", info.durationMs)
                .putString("mediaId", info.mediaId)
                .putBoolean("favorite", info.favorite)
                .putLong("at", SystemClock.elapsedRealtime())
                .apply()
            val file = File(context.filesDir, ART_FILE)
            if (art != null) {
                runCatching { file.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            } else {
                file.delete()
            }
            renderAll(context, art)
        }

        /** Play / pause or the place in the track changed: the cover stays. */
        fun updatePlaying(context: Context, playing: Boolean, positionMs: Long? = null) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (positionMs == null && prefs.getBoolean("playing", false) == playing && prefs.contains("playing")) return
            prefs.edit().apply {
                putBoolean("playing", playing)
                if (positionMs != null) putLong("position", positionMs)
                // Where it was when it stopped stays shown; going on, the time counts from there.
                if (positionMs == null && !playing) putLong("position", positionNow(prefs))
                putLong("at", SystemClock.elapsedRealtime())
            }.apply()
            renderAll(context)
        }

        /** Whether the playing track is a favorite (the heart). */
        fun setFavorite(context: Context, favorite: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("favorite", false) == favorite && prefs.contains("favorite")) return
            prefs.edit().putBoolean("favorite", favorite).apply()
            renderAll(context)
        }

        /** What the big widget shows (Settings → Appearance). */
        fun setBig(context: Context, big: Big) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString("big", Big.ORAMA.name) == big.name) return
            prefs.edit().putString("big", big.name).apply()
            renderAll(context)
        }

        /** A new still of the landscape for the O'rama widgets. */
        fun updateScene(context: Context, scene: Bitmap?) {
            val file = File(context.filesDir, SCENE_FILE)
            if (scene != null) runCatching { file.outputStream().use { scene.compress(Bitmap.CompressFormat.PNG, 100, it) } } else file.delete()
            renderAll(context)
        }

        /** The theme's colours: the card, the texts, the play button. Each change of theme sends them. */
        fun setColors(context: Context, card: Int, text: Int, textSecondary: Int, accent: Int, onAccent: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt("card", 0) == card && prefs.getInt("text", 0) == text && prefs.getInt("accent", 0) == accent &&
                prefs.getInt("text2", 0) == textSecondary && prefs.getInt("onAccent", 0) == onAccent
            ) return
            prefs.edit().putInt("card", card).putInt("text", text).putInt("text2", textSecondary).putInt("accent", accent).putInt("onAccent", onAccent).apply()
            renderAll(context)
        }

        /** Whether any widget placed is big enough for the landscape (so a still is worth making). */
        fun wantsScene(context: Context): Boolean {
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("big", Big.ORAMA.name) != Big.ORAMA.name) return false
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            return ids.any { id ->
                val o = manager.getAppWidgetOptions(id)
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) >= SCENE_MIN_WIDTH && o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) >= SCENE_MIN_HEIGHT
            }
        }

        /** The size (px) a still is made at: the largest widget's, kept small enough to send. */
        fun sceneSize(context: Context): Pair<Int, Int> {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            var w = SCENE_MIN_WIDTH
            var h = SCENE_MIN_HEIGHT
            for (id in ids) {
                val o = manager.getAppWidgetOptions(id)
                w = maxOf(w, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat())
                h = maxOf(h, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat())
            }
            val density = context.resources.displayMetrics.density
            val scale = minOf(1f, 720f / (w * density), 720f / (h * density))
            return (w * density * scale).toInt().coerceAtLeast(64) to (h * density * scale).toInt().coerceAtLeast(64)
        }

        private fun positionNow(prefs: android.content.SharedPreferences): Long {
            val position = prefs.getLong("position", 0L)
            if (!prefs.getBoolean("playing", false)) return position
            val since = SystemClock.elapsedRealtime() - prefs.getLong("at", SystemClock.elapsedRealtime())
            val duration = prefs.getLong("duration", 0L)
            return (position + since).let { if (duration > 0) it.coerceAtMost(duration) else it }
        }

        private fun renderAll(context: Context, art: Bitmap? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, art)
        }

        private fun time(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, art: Bitmap? = null) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString("title", null) ?: context.getString(R.string.app_name)
            val artist = prefs.getString("artist", null).orEmpty()
            val album = prefs.getString("album", null).orEmpty()
            val next = prefs.getString("next", null)
            val list = prefs.getString("list", null)
            val playing = prefs.getBoolean("playing", false)
            val duration = prefs.getLong("duration", 0L)
            val position = positionNow(prefs)
            val card = prefs.getInt("card", 0xF0202124.toInt())
            val text = prefs.getInt("text", 0xFFFFFFFF.toInt())
            val text2 = prefs.getInt("text2", 0xB3FFFFFF.toInt())
            val accent = prefs.getInt("accent", 0xFFFFFFFF.toInt())
            val onAccent = prefs.getInt("onAccent", 0xFF202124.toInt())
            val cover = art ?: File(context.filesDir, ART_FILE).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
            val sceneFile = File(context.filesDir, SCENE_FILE)
            val favorite = prefs.getBoolean("favorite", false)
            val bigCover = prefs.getString("big", Big.ORAMA.name) == Big.COVER.name

            fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
                // The square one is the cover itself under its box, as the big one is the landscape.
                val square = layout == R.layout.widget_now_playing_square
                val scene = layout == R.layout.widget_now_playing_orama || square
                setTextViewText(R.id.widget_title, title)
                if (!scene) {
                    if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_note)
                } else if (layout == R.layout.widget_now_playing_orama) {
                    if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_note)
                }
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.action_pause else R.string.action_play))
                setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
                // The play button in the theme's accent, its sign in the colour that reads on it.
                setInt(R.id.widget_play, "setColorFilter", onAccent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setColorStateList(R.id.widget_play, "setBackgroundTintList", ColorStateList.valueOf(accent))
                }
                if (layout == R.layout.widget_now_playing_mini) {
                    setInt(R.id.widget_bg, "setColorFilter", card)
                    setInt(R.id.widget_bg, "setImageAlpha", (card ushr 24).coerceAtLeast(0xC0))
                    return@apply
                }
                setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                setOnClickPendingIntent(R.id.widget_previous, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                // The heart, filled for a favorite.
                setImageViewResource(R.id.widget_favorite, if (favorite) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart)
                setContentDescription(R.id.widget_favorite, context.getString(R.string.action_favorite))
                setOnClickPendingIntent(R.id.widget_favorite, favoriteIntent(context))
                setInt(R.id.widget_favorite, "setColorFilter", if (favorite) accent else if (scene) 0xFFFFFFFF.toInt() else text)
                // Layouts without the time.
                val tall = layout == R.layout.widget_now_playing_tall || square
                // Over the landscape the texts stay white on their dark boxes.
                val main = if (scene) 0xFFFFFFFF.toInt() else text
                val second = if (scene) 0xD9FFFFFF.toInt() else text2
                setTextColor(R.id.widget_title, main)
                setTextColor(R.id.widget_subtitle, second)
                if (!tall) for (id in listOf(R.id.widget_elapsed, R.id.widget_length)) setTextColor(id, second)
                if (!scene) {
                    setInt(R.id.widget_bg, "setColorFilter", card)
                    setInt(R.id.widget_bg, "setImageAlpha", (card ushr 24).coerceAtLeast(0xC0))
                    for (id in listOf(R.id.widget_previous, R.id.widget_next)) setInt(id, "setColorFilter", text)
                }
                val large = layout == R.layout.widget_now_playing_large || layout == R.layout.widget_now_playing_tall_more
                setTextViewText(R.id.widget_subtitle, if (large) artist else listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" · "))
                if (large) {
                    setTextViewText(R.id.widget_album, album)
                    setViewVisibility(R.id.widget_album, if (album.isEmpty()) View.GONE else View.VISIBLE)
                    setTextColor(R.id.widget_album, second)
                    // The list's name only where there is height for it (two rows high have the time on its own line instead).
                    if (layout == R.layout.widget_now_playing_tall_more) {
                        setTextViewText(R.id.widget_list, list.orEmpty())
                        setViewVisibility(R.id.widget_list, if (list.isNullOrEmpty()) View.GONE else View.VISIBLE)
                        setTextColor(R.id.widget_list, second)
                    }
                }
                if (large || (scene && !square)) {
                    setTextViewText(R.id.widget_next_title, next?.let { context.getString(R.string.widget_next, it) }.orEmpty())
                    setViewVisibility(R.id.widget_next_title, if (next.isNullOrEmpty()) View.GONE else View.VISIBLE)
                    setTextColor(R.id.widget_next_title, second)
                }
                // The time counts by itself while playing (no updates needed for it).
                if (tall) {
                    // No room for it.
                } else if (duration > 0) {
                    setViewVisibility(R.id.widget_time_row, View.VISIBLE)
                    setChronometer(R.id.widget_elapsed, SystemClock.elapsedRealtime() - position, null, playing)
                    setTextViewText(R.id.widget_length, " / " + time(duration))
                } else {
                    setViewVisibility(R.id.widget_time_row, View.GONE)
                }
                if (scene) {
                    // The landscape, or the cover big when so chosen (or while there is no landscape yet).
                    val still = if (bigCover || square) null else sceneFile.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
                    when {
                        still != null -> setImageViewBitmap(R.id.widget_scene, still)
                        cover != null -> setImageViewBitmap(R.id.widget_scene, cover)
                        else -> setImageViewResource(R.id.widget_scene, R.drawable.ic_widget_note)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The launcher picks the largest layout that fits.
                val remote = RemoteViews(
                    mapOf(
                        SizeF(100f, 40f) to views(R.layout.widget_now_playing_mini),
                        SizeF(200f, 40f) to views(R.layout.widget_now_playing),
                        // Square-ish (2×2, 3×2…): the cover filling it.
                        SizeF(110f, 110f) to views(R.layout.widget_now_playing_square),
                        // Tall and narrow: the cover big on top, more below as it grows.
                        SizeF(110f, 200f) to views(R.layout.widget_now_playing_tall),
                        SizeF(110f, 330f) to views(R.layout.widget_now_playing_tall_more),
                        SizeF(250f, 110f) to views(R.layout.widget_now_playing_large),
                        SizeF(SCENE_MIN_WIDTH, SCENE_MIN_HEIGHT) to views(R.layout.widget_now_playing_orama),
                    ),
                )
                manager.updateAppWidget(ids, remote)
            } else {
                // Before Android 12, by each widget's size.
                for (id in ids) {
                    val o = manager.getAppWidgetOptions(id)
                    val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    val layout = when {
                        w >= SCENE_MIN_WIDTH && h >= SCENE_MIN_HEIGHT -> R.layout.widget_now_playing_orama
                        w < 250 && h >= 330 -> R.layout.widget_now_playing_tall_more
                        w < 250 && h >= 200 -> R.layout.widget_now_playing_tall
                        w >= 110 && h >= 110 && w < 250 -> R.layout.widget_now_playing_square
                        w >= 250 && h >= 110 -> R.layout.widget_now_playing_large
                        w in 1 until 200 -> R.layout.widget_now_playing_mini
                        else -> R.layout.widget_now_playing
                    }
                    manager.updateAppWidget(id, views(layout))
                }
            }
        }

        private fun favoriteIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, NowPlayingWidget::class.java).setAction(ACTION_FAVORITE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

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
