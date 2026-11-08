package com.sadurni.attacca.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.DrawList
import com.sadurni.attacca.diorama.SongWave

/**
 * A still of O'rama's landscape for the playing track (for the home-screen widget): the same
 * scene the O'rama screen shows at that point of the track, run for a moment so it settles, drawn
 * into a bitmap.
 */
object OramaStill {

    /** The still of [mediaId] at [positionMs], [width] × [height] px; null when it cannot be made. Off the main thread. */
    fun render(
        context: Context,
        mediaId: String,
        title: String?,
        artist: String?,
        album: String?,
        nextTitle: String?,
        positionMs: Long,
        cover: Bitmap?,
        width: Int,
        height: Int,
    ): Bitmap? = runCatching {
        val app = context.applicationContext as com.sadurni.attacca.AttaccaApp
        val look = com.sadurni.attacca.ui.orama.lookOf(context, mediaId, cover)
        val analysis = app.analysisResults.value[mediaId]
        val wave = analysis?.let { a -> SongWave(a.waveform, a.durationMs, a.audioStartMs, a.audioEndMs.takeIf { it > a.audioStartMs } ?: a.durationMs) }
        val diorama = Diorama()
        // The festive look of these days, as on the O'rama screen.
        val festive = com.sadurni.attacca.festive.FestiveState
        val period = festive.period.value
        diorama.festive(if (festive.on.value) period?.festivity else null, period?.kings == true)
        val list = DrawList()
        val song = Diorama.Song(mediaId, look, wave, album?.takeIf { it.isNotBlank() } ?: title, artist)
        val start = 1_000_000L
        diorama.show(song, null, start, nextTitle)
        // A few seconds of made-up sound so the scene's live parts settle.
        val raw = FloatArray(3)
        for (k in 0..90) {
            val t = start + k * 33L
            val beat = (t % 500) < 80
            raw[0] = 0.2f + if (beat) 0.2f else 0f
            raw[1] = if (beat) 0.25f else 0.05f
            raw[2] = 0.03f
            diorama.draw(list, width.toFloat(), height.toFloat(), positionMs + k * 33L, true, raw, t)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val small = cover?.let { runCatching { Bitmap.createScaledBitmap(it, 160, 160, true) }.getOrNull() }
        paint(canvas, list, small)
        bitmap
    }.getOrNull()

    /** Paints a frame of the diorama through its camera. */
    fun paint(canvas: Canvas, list: DrawList, cover: Bitmap?) {
        canvas.save()
        canvas.translate(list.shiftX, list.shiftY)
        canvas.scale(list.zoom, list.zoom, list.pivotX, list.pivotY)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val path = Path()
        val c = list.coords
        for (i in 0 until list.size) {
            val s = list.starts[i]
            val n = list.counts[i]
            val color = list.colors[i]
            when (list.kinds[i]) {
                DrawList.Kind.RECT -> canvas.drawRect(c[s], c[s + 1], c[s + 2], c[s + 3], fill.also { it.color = color; it.shader = null })
                DrawList.Kind.CIRCLE -> canvas.drawCircle(c[s], c[s + 1], c[s + 2], fill.also { it.color = color; it.shader = null })
                DrawList.Kind.RING -> canvas.drawCircle(c[s], c[s + 1], c[s + 2], stroke.also { it.color = color; it.strokeWidth = list.widths[i] })
                DrawList.Kind.GRADIENT -> {
                    fill.shader = LinearGradient(0f, c[s + 1], 0f, c[s + 3], color, list.colors2[i], Shader.TileMode.CLAMP)
                    canvas.drawRect(c[s], c[s + 1], c[s + 2], c[s + 3], fill)
                    fill.shader = null
                }
                DrawList.Kind.POLYGON -> if (n >= 6) {
                    path.reset()
                    path.moveTo(c[s], c[s + 1])
                    var k = 2
                    while (k < n) {
                        path.lineTo(c[s + k], c[s + k + 1])
                        k += 2
                    }
                    path.close()
                    canvas.drawPath(path, fill.also { it.color = color; it.shader = null })
                }
                DrawList.Kind.LINE -> if (n >= 4) {
                    path.reset()
                    path.moveTo(c[s], c[s + 1])
                    var k = 2
                    while (k < n) {
                        path.lineTo(c[s + k], c[s + k + 1])
                        k += 2
                    }
                    canvas.drawPath(path, stroke.also { it.color = color; it.strokeWidth = list.widths[i] })
                }
                DrawList.Kind.TEXT -> list.texts[i]?.let { value ->
                    text.color = color
                    text.textSize = c[s + 2]
                    canvas.save()
                    canvas.rotate(c[s + 3] * (180f / Math.PI.toFloat()), c[s], c[s + 1])
                    canvas.drawText(value, c[s], c[s + 1] + text.textSize * 0.35f, text)
                    canvas.restore()
                }
                DrawList.Kind.COVER -> {
                    val half = c[s + 2] / 2f
                    canvas.save()
                    canvas.rotate(c[s + 3] * (180f / Math.PI.toFloat()), c[s], c[s + 1])
                    val box = RectF(c[s] - half, c[s + 1] - half, c[s] + half, c[s + 1] + half)
                    if (cover != null) {
                        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = color ushr 24 }
                        canvas.drawBitmap(cover, Rect(0, 0, cover.width, cover.height), box, p)
                    } else {
                        canvas.drawRect(box, fill.also { it.color = color; it.shader = null })
                        canvas.drawCircle(c[s], c[s + 1], c[s + 2] * 0.18f, fill.also { it.color = 0x66000000 })
                    }
                    canvas.restore()
                }
            }
        }
        canvas.restore()
    }
}
