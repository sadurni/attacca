package com.sadurni.attacca.ui.art

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.sadurni.attacca.art.ArtMark
import com.sadurni.attacca.art.ArtPainter
import com.sadurni.attacca.art.ArtSong
import com.sadurni.attacca.diorama.LiveLevels
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/** A painting just finished (or asked for): the picture and the song it is of. */
class ArtPicture(val bitmap: Bitmap, val songId: String?)

/** What Art-tacca paints from, kept up to date by the screen, and what it hands back. */
class ArtFeed {
    @Volatile
    var song: ArtSong? = null

    @Volatile
    var positionMs = 0L

    @Volatile
    var positionAtNs = 0L

    /** The song [positionMs] belongs to. */
    @Volatile
    var positionFor: String? = null

    @Volatile
    var playing = false

    val levels = FloatArray(3)

    @Volatile
    var heard = false

    /** Each new song paints over the last painting (faded) instead of a new canvas. */
    @Volatile
    var paintOver = false

    @Volatile
    var cap = 30

    /** How to paint (a change starts a new painting at once). */
    @Volatile
    var style = com.sadurni.attacca.art.ArtStyle.WATERCOLOUR

    /** What is painted: freely, or the song's cover (a change starts a new painting at once), and how closely. */
    @Volatile
    var subject = com.sadurni.attacca.art.ArtSubject.FREE

    @Volatile
    var likeness = 1f

    /** The share of each pitch class heard now ([notesKnown]: whether it is known). */
    val notes = FloatArray(12)

    @Volatile
    var notesKnown = false

    /** The edges (left, top, right, bottom, pixels) where the painting fades out into what is under it. */
    val fadePx = FloatArray(4)

    /** For a strip: the size a painting is made at (the phone's screen), shown cropped to the strip. */
    @Volatile
    var canvasHintW = 0

    @Volatile
    var canvasHintH = 0

    /** Called (on the GL thread) with each painting finished as its song ends. */
    @Volatile
    var onFinished: ((ArtPicture) -> Unit)? = null

    /** Asked for: the painting as it is now, handed (on the GL thread) to this. */
    @Volatile
    var snapshot: ((ArtPicture) -> Unit)? = null

    /** With no song to paint: a painting to show meanwhile (the last one finished). */
    @Volatile
    var rest: Bitmap? = null
}

/** A painting under way: its painter (the marks) and the canvas it was painted over (painting over), or null. */
class ArtCanvasState {
    val painter = ArtPainter()

    @Volatile
    var base: Bitmap? = null
}

/** What stays while Art-tacca is closed: the app's painting under way, and what its screens share. */
object ArtShared {
    /** The app's painting (the whole screen and the strip); the wallpaper keeps its own. */
    val state = ArtCanvasState()

    /** Art-tacca's whole screen is open (the strip in Now playing rests meanwhile). */
    val open = androidx.compose.runtime.mutableStateOf(false)

    /** What shares Art-tacca's screen: "ORAMA", "WORLD", "BOTH" (all three), or null (alone). Kept while the app runs. */
    val partner = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** How many views listen to the notes now. */
    var listeners = 0
}

/**
 * Art-tacca's canvas on the GPU: the marks are pigments that multiply on white paper as
 * watercolour does; a mark spreads while wet (drawn each frame over the canvas) and, once dry, is
 * painted into the canvas for good. The paper's grain shows through and the pigment settles in it.
 */
class ArtRenderer(
    private val feed: ArtFeed,
    private val strip: Boolean = false,
    private val state: ArtCanvasState = ArtShared.state,
    /** Drawn on a see-through surface: its edges can melt into the view beside it. */
    private val translucent: Boolean = false,
) : GLSurfaceView.Renderer {
    private val painter get() = state.painter
    private val live = LiveLevels()

    private var markProgram = 0
    private var paperProgram = 0
    private var fadeProgram = 0
    private var canvasFbo = 0
    private var canvasTex = 0
    private var canvasW = 0
    private var canvasH = 0
    private var fadeFbo = 0
    private var fadeTex = 0
    private var fadeStartMs = Long.MIN_VALUE
    private var surfaceW = 1
    private var surfaceH = 1
    private var quadBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 6).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var markBuf: FloatBuffer = alloc(256)
    private var vao = 0
    private var vbo = 0
    private var quadVbo = 0
    private val pending = ArrayList<ArtMark>()
    private var needReplay = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        markProgram = program(MARK_VS, MARK_FS)
        paperProgram = program(PAPER_VS, PAPER_FS)
        fadeProgram = program(PAPER_VS, FADE_FS)
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        quadVbo = ids[1]
        canvasFbo = 0
        canvasTex = 0
        restTex = 0
        restOf = null
        fadeFbo = 0
        fadeTex = 0
        canvasW = 0
        canvasH = 0
        fadeStartMs = Long.MIN_VALUE
        needReplay = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width)
        surfaceH = max(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = android.os.SystemClock.uptimeMillis()
        val song = feed.song
        // A new song: the painting before is finished (handed on), and a new one starts.
        painter.likeness = feed.likeness
        if (song != null && (song.id != painter.song?.id || feed.style != painter.chosen || feed.subject != painter.subject)) {
            newPainting(song, now)
        } else if (painter.song == null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(WALL[0], WALL[1], WALL[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            feed.rest?.takeIf { !it.isRecycled }?.let { drawRest(it) }
            return
        }
        if (canvasFbo == 0 || needReplay) replay()
        // The painting takes the screen's shape (turned on its side, or shown split): laid out again.
        if (!strip && painter.song != null) {
            val (w, h) = canvasSizeFor(surfaceW, surfaceH)
            val rc = canvasW / canvasH.toFloat()
            val rs = w / h.toFloat()
            if (max(rc / rs, rs / rc) > RESHAPE) {
                painter.resize(w.toFloat(), h.toFloat())
                replay()
            }
        }

        // The song's moment and what is heard now.
        live.update(if (feed.heard) feed.levels else null, now)
        // (Only the painting's own song's place: the player may give the next one's a moment early.)
        val current = painter.song
        val songMs = if (song != null && song.id == current?.id && (feed.positionFor == null || feed.positionFor == current.id)) {
            feed.positionMs + if (feed.playing) (System.nanoTime() - feed.positionAtNs) / 1_000_000.0 else 0.0
        } else -1.0
        painter.frame(now, max(0.0, songMs), songMs >= 0 && feed.playing, live.level, live.low, live.high, live.onsetAtMs, if (feed.notesKnown) feed.notes else null)

        // Dried marks into the canvas.
        pending.clear()
        painter.takeDried(pending)
        if (pending.isNotEmpty()) {
            bindCanvas()
            drawMarks(pending, now, toCanvas = true)
        }

        // On screen: the paper with the canvas, the wet marks over it, the painting before fading out.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glClearColor(WALL[0], WALL[1], WALL[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawPaper(onScreen = true, tex = canvasTex, mode = 0, alpha = 1f)
        if (painter.wet.isNotEmpty()) drawMarks(painter.wet, now, toCanvas = false)
        if (fadeStartMs != Long.MIN_VALUE) {
            val t = (now - fadeStartMs) / FADE_MS.toFloat()
            if (t >= 1f) fadeStartMs = Long.MIN_VALUE
            else drawPaper(onScreen = true, tex = fadeTex, mode = 1, alpha = 1f - t * t * (3 - 2 * t))
        }

        if (translucent) melt()

        feed.snapshot?.let { give ->
            feed.snapshot = null
            give(ArtPicture(readPainting(now, withWet = true), painter.song?.id))
        }
    }

    /**
     * On a see-through surface: everything opaque, then the edges asked for fading out (the colour
     * fading with it, as the system composes it), so the painting melts into the view beside it.
     */
    private fun melt() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glColorMask(false, false, false, true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glColorMask(true, true, true, true)
        val f = feed.fadePx
        if (f[0] <= 0f && f[1] <= 0f && f[2] <= 0f && f[3] <= 0f) return
        quadBuf.clear()
        quadBuf.put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, -1f, -1f, 0f, 0f, 1f, 1f, 1f, 1f, -1f, 1f, 0f, 1f))
        quadBuf.flip()
        GLES30.glUseProgram(fadeProgram)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 24, quadBuf, GLES30.GL_STREAM_DRAW)
        for (i in 0 until 8) GLES30.glDisableVertexAttribArray(i)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(fadeProgram, "uSize"), surfaceW.toFloat(), surfaceH.toFloat())
        GLES30.glUniform4f(GLES30.glGetUniformLocation(fadeProgram, "uFade"), f[0], f[1], f[2], f[3])
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ZERO, GLES30.GL_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** The painting before is handed on; the canvas is cleared (or veiled, painting over) for [song]. */
    private fun newPainting(song: ArtSong, now: Long) {
        val old = painter.song
        if (old != null && canvasFbo != 0 && !needReplay) {
            painter.dryAll()
            pending.clear()
            painter.takeDried(pending)
            if (pending.isNotEmpty()) {
                bindCanvas()
                drawMarks(pending, now, toCanvas = true)
            }
            if (painter.history.size > MIN_MARKS) feed.onFinished?.invoke(ArtPicture(readPainting(now, withWet = false), old.id))
        }
        val over = feed.paintOver && old != null && canvasFbo != 0 && !needReplay
        // The canvas to paint over, as it is (its size may change with the screen's).
        val base = if (over) readCanvas() else null
        state.base?.takeIf { it !== base }?.recycle()
        state.base = base
        // The painting before stays a moment, fading into the new paper.
        if (!over && old != null && canvasFbo != 0 && !needReplay) keepForFade(now)
        val (w, h) = if (strip && feed.canvasHintW > 0) canvasSizeFor(feed.canvasHintW, feed.canvasHintH) else canvasSizeFor(surfaceW, surfaceH)
        painter.start(song, w.toFloat(), h.toFloat(), now, veil = over, style = feed.style, subject = feed.subject, likeness = feed.likeness)
        makeCanvas(w, h)
        clearCanvas()
        drawBase()
        pending.clear()
        painter.takeDried(pending)
        bindCanvas()
        drawMarks(pending, now, toCanvas = true)
        needReplay = false
    }

    private var restTex = 0
    private var restOf: Bitmap? = null

    /** A finished painting shown as it is (nothing playing yet). */
    private fun drawRest(b: Bitmap) {
        if (restOf !== b || restTex == 0) {
            if (restTex != 0) GLES30.glDeleteTextures(1, intArrayOf(restTex), 0)
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            restTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, restTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
            restOf = b
        }
        canvasW = b.width
        canvasH = b.height
        drawPaper(onScreen = true, tex = restTex, mode = 2, alpha = 1f)
    }

    /** After the GL surface was made again (or first opened): the canvas painted again from its marks. */
    private fun replay() {
        val w = painter.width.toInt().coerceAtLeast(1)
        val h = painter.height.toInt().coerceAtLeast(1)
        makeCanvas(w, h)
        clearCanvas()
        drawBase()
        pending.clear()
        painter.takeDried(pending)
        bindCanvas()
        drawMarks(painter.history, android.os.SystemClock.uptimeMillis(), toCanvas = true)
        needReplay = false
    }

    private fun canvasSizeFor(sw: Int, sh: Int): Pair<Int, Int> {
        val scale = min(1f, MAX_SIDE / max(sw, sh).toFloat())
        return max(1, (sw * scale).toInt()) to max(1, (sh * scale).toInt())
    }

    private fun makeCanvas(w: Int, h: Int) {
        if (canvasFbo != 0 && w == canvasW && h == canvasH) return
        deleteTarget(canvasFbo, canvasTex)
        val (f, t) = target(w, h)
        canvasFbo = f
        canvasTex = t
        canvasW = w
        canvasH = h
    }

    private fun clearCanvas() {
        bindCanvas()
        val g = painter.ground
        GLES30.glClearColor(g[0], g[1], g[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun bindCanvas() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, canvasFbo)
        GLES30.glViewport(0, 0, canvasW, canvasH)
    }

    /** The canvas painted over: drawn back first, stretched to the canvas. */
    private fun drawBase() {
        val b = state.base ?: return
        if (b.isRecycled) return
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        bindCanvas()
        drawPaper(onScreen = false, tex = ids[0], mode = 2, alpha = 1f)
        GLES30.glDeleteTextures(1, ids, 0)
    }

    /** The painting as it is now, kept for a moment to fade out over the new paper. */
    private fun keepForFade(now: Long) {
        if (fadeFbo == 0 || fadeW != surfaceW || fadeH != surfaceH) {
            deleteTarget(fadeFbo, fadeTex)
            val (f, t) = target(surfaceW, surfaceH)
            fadeFbo = f
            fadeTex = t
            fadeW = surfaceW
            fadeH = surfaceH
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fadeFbo)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glClearColor(WALL[0], WALL[1], WALL[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawPaper(onScreen = true, tex = canvasTex, mode = 0, alpha = 1f, framebufferH = surfaceH)
        fadeStartMs = now
    }

    private var fadeW = 0
    private var fadeH = 0

    /** The canvas's pigment alone (no paper), top row first. */
    private fun readCanvas(): Bitmap {
        bindCanvas()
        return read(canvasW, canvasH)
    }

    /** The painting on its paper, at the canvas's size, top row first. */
    private fun readPainting(now: Long, withWet: Boolean): Bitmap {
        val (f, t) = target(canvasW, canvasH)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
        GLES30.glViewport(0, 0, canvasW, canvasH)
        drawPaper(onScreen = false, tex = canvasTex, mode = 0, alpha = 1f)
        if (withWet && painter.wet.isNotEmpty()) drawMarks(painter.wet, now, toCanvas = true)
        val b = read(canvasW, canvasH)
        deleteTarget(f, t)
        return b
    }

    private fun read(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        raw.copyPixelsFromBuffer(buf)
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val out = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
        if (out !== raw) raw.recycle()
        return out
    }

    // ---------- Drawing ----------

    /**
     * The paper with [tex] on it. Mode 0: the canvas on paper; 1: a finished picture (fading,
     * [alpha]); 2: a picture (top row first) copied as it is.
     */
    private fun drawPaper(onScreen: Boolean, tex: Int, mode: Int, alpha: Float, framebufferH: Int = surfaceH) {
        val (x0, y0, x1, y1) = if (onScreen && mode != 1) paintingRect() else floatArrayOf(-1f, -1f, 1f, 1f).let { Quad(it[0], it[1], it[2], it[3]) }
        quadBuf.clear()
        quadBuf.put(floatArrayOf(
            x0, y0, 0f, 0f, x1, y0, 1f, 0f, x1, y1, 1f, 1f,
            x0, y0, 0f, 0f, x1, y1, 1f, 1f, x0, y1, 0f, 1f,
        ))
        quadBuf.flip()
        GLES30.glUseProgram(paperProgram)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 24, quadBuf, GLES30.GL_STREAM_DRAW)
        for (i in 0 until 8) GLES30.glDisableVertexAttribArray(i)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(paperProgram, "uTex"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(paperProgram, "uMode"), mode)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(paperProgram, "uAlpha"), alpha)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(paperProgram, "uSize"), canvasW.toFloat(), canvasH.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(paperProgram, "uGrain"), GRAIN / max(canvasW, canvasH).toFloat())
        val sheet = when (painter.style) {
            com.sadurni.attacca.art.ArtStyle.INK -> INK_PAPER
            com.sadurni.attacca.art.ArtStyle.PEN -> NOTEBOOK
            else -> painter.song?.palette?.paper ?: DEFAULT_PAPER
        }
        GLES30.glUniform3f(GLES30.glGetUniformLocation(paperProgram, "uPaper"), sheet[0], sheet[1], sheet[2])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(paperProgram, "uStyle"), painter.style.ordinal)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(paperProgram, "uSquared"), if (painter.squared) 1f else 0f)
        if (mode == 1) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private data class Quad(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** Where the painting sits on the screen (all of it in view), in clip space. */
    private fun paintingRect(): Quad {
        // All of it in view; in a strip, filling it (cropped).
        val s = if (strip) max(surfaceW / canvasW.toFloat(), surfaceH / canvasH.toFloat()) else min(surfaceW / canvasW.toFloat(), surfaceH / canvasH.toFloat())
        val w = canvasW * s / surfaceW
        val h = canvasH * s / surfaceH
        return Quad(-w, -h, w, h)
    }

    /** [marks] at [now]: into the canvas, or onto the screen over it. */
    private fun drawMarks(marks: List<ArtMark>, now: Long, toCanvas: Boolean) {
        if (marks.isEmpty()) return
        GLES30.glUseProgram(markProgram)
        val xf = if (toCanvas) floatArrayOf(2f / canvasW, -2f / canvasH, -1f, 1f) else {
            val q = paintingRect()
            floatArrayOf((q.x1 - q.x0) / canvasW, -(q.y1 - q.y0) / canvasH, q.x0, q.y1)
        }
        GLES30.glUniform4f(GLES30.glGetUniformLocation(markProgram, "uXf"), xf[0], xf[1], xf[2], xf[3])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(markProgram, "uGrain"), GRAIN / max(canvasW, canvasH).toFloat())
        GLES30.glEnable(GLES30.GL_BLEND)
        var i = 0
        while (i < marks.size) {
            // Runs of marks drawn alike: pigments multiply; a veil lays white over; lines keep the
            // darker of what is there and themselves (no beads where their pieces overlap).
            val way = blendOf(marks[i].kind)
            var j = i
            while (j < marks.size && blendOf(marks[j].kind) == way && j - i < BATCH) j++
            when (way) {
                1, 3 -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                2 -> GLES30.glBlendEquation(GLES30.GL_MIN)
                else -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
            }
            drawRun(marks, i, j, now)
            if (way == 2) GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            i = j
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun blendOf(kind: Int) = when (kind) {
        ArtMark.VEIL -> 1
        ArtMark.INK, ArtMark.LINE, ArtMark.PEN -> 2
        ArtMark.OIL, ArtMark.DOT -> 3
        else -> 0
    }

    private fun drawRun(marks: List<ArtMark>, from: Int, to: Int, now: Long) {
        val count = to - from
        if (markBuf.capacity() < count * 6 * STRIDE) markBuf = alloc(count * 2)
        markBuf.clear()
        for (k in from until to) {
            val m = marks[k]
            val g = m.growth(now)
            for (c in CORNERS) {
                markBuf.put(c[0]).put(c[1])
                markBuf.put(m.x).put(m.y).put(m.rx).put(m.ry).put(m.angle)
                markBuf.put(m.r).put(m.g).put(m.b).put(m.alpha)
                markBuf.put(m.kind.toFloat()).put(m.seed).put(g)
            }
        }
        markBuf.flip()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 6 * STRIDE * 4, markBuf, GLES30.GL_STREAM_DRAW)
        val bytes = STRIDE * 4
        for (a in 0 until 8) GLES30.glDisableVertexAttribArray(a)
        attrib(0, 2, bytes, 0) // corner
        attrib(1, 2, bytes, 2) // centre
        attrib(2, 2, bytes, 4) // radii
        attrib(3, 1, bytes, 6) // angle
        attrib(4, 4, bytes, 7) // colour, strength
        attrib(5, 3, bytes, 11) // kind, seed, growth
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count * 6)
    }

    private fun attrib(index: Int, size: Int, stride: Int, offsetFloats: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offsetFloats * 4)
    }

    // ---------- GL helpers ----------

    private fun target(w: Int, h: Int): Pair<Int, Int> {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        return fbo to tex
    }

    private fun deleteTarget(fbo: Int, tex: Int) {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Art-tacca shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aCorner")
        GLES30.glBindAttribLocation(p, 1, "aCentre")
        GLES30.glBindAttribLocation(p, 2, "aRadii")
        GLES30.glBindAttribLocation(p, 3, "aAngle")
        GLES30.glBindAttribLocation(p, 4, "aColour")
        GLES30.glBindAttribLocation(p, 5, "aParams")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Art-tacca program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private companion object {
        /** The canvas's longest side, pixels. */
        const val MAX_SIDE = 1600f

        /** Paper grains across the canvas's longest side. */
        const val GRAIN = 700f

        const val FADE_MS = 1600

        /** How far the screen's shape may be from the painting's before the painting takes it. */
        const val RESHAPE = 1.25f
        const val STRIDE = 14
        const val BATCH = 2000

        /** Fewer marks than this is no painting to hand on (a song skipped at once). */
        const val MIN_MARKS = 12

        /** Around the painting: a dim wall. */
        val WALL = floatArrayOf(0.11f, 0.105f, 0.1f)

        val DEFAULT_PAPER = floatArrayOf(0.972f, 0.958f, 0.925f)
        val INK_PAPER = floatArrayOf(0.985f, 0.98f, 0.965f)
        val NOTEBOOK = floatArrayOf(0.99f, 0.988f, 0.978f)

        val CORNERS = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))

        fun alloc(marks: Int): FloatBuffer = ByteBuffer.allocateDirect(max(64, marks) * 6 * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val NOISE = """
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) { float s = 0.0; float a = 0.5; for (int i = 0; i < 4; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; } return s; }
float paper(vec2 c) { vec2 q = c * uGrain; return fbm(q * 0.35) * 0.6 + vnoise(q * 1.7) * 0.4; }
"""

        const val MARK_VS = """#version 300 es
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec2 aCentre;
layout(location = 2) in vec2 aRadii;
layout(location = 3) in float aAngle;
layout(location = 4) in vec4 aColour;
layout(location = 5) in vec3 aParams;
uniform vec4 uXf;
out vec2 vLocal;
out vec2 vCanvas;
out vec4 vColour;
out vec3 vParams;
out vec2 vRadii;
float tremble(float n) { return fract(sin(n * 12.9898) * 43758.5453) - 0.5; }
void main() {
    int kind = int(aParams.x + 0.5);
    vec2 ext = vec2(1.0);
    if (kind == 8) ext = vec2(1.0, 1.3);
    else if (kind == 9) ext = vec2(1.2);
    else if (kind == 10) ext = vec2(1.0, 2.2);
    else if (kind == 11) ext = vec2(1.25);
    if (kind == 0) ext = vec2(1.3);
    else if (kind == 4) ext = vec2(1.15);
    else if (kind == 3) ext = vec2(1.25);
    else if (kind == 2 || kind == 7) ext = vec2(1.0, 1.8);
    else if (kind == 1) ext = vec2(1.0, 1.3 + 0.3 * aRadii.x / max(aRadii.y, 0.001));
    if (kind == 8) ext = vec2(1.0, 1.3 + 0.3 * aRadii.x / max(aRadii.y, 0.001));
    if (kind == 0) ext = vec2(1.45);
    vec2 local = aCorner * ext;
    float c = cos(aAngle); float s = sin(aAngle);
    vec2 off = local * aRadii;
    vec2 pos = aCentre + vec2(off.x * c - off.y * s, off.x * s + off.y * c);
    // A ballpoint line trembles while it is being drawn, as frames drawn by hand.
    if (kind == 10 && aParams.z < 1.0) {
        float f = floor(aParams.z * 7.0) + aParams.y * 50.0;
        pos += vec2(tremble(f), tremble(f + 3.7)) * aRadii.y * 2.5;
    }
    vLocal = local;
    vRadii = aRadii;
    vCanvas = pos;
    vColour = aColour;
    vParams = aParams;
    gl_Position = vec4(pos * uXf.xy + uXf.zw, 0.0, 1.0);
}
"""

        const val MARK_FS = """#version 300 es
precision highp float;
in vec2 vLocal;
in vec2 vCanvas;
in vec4 vColour;
in vec3 vParams;
in vec2 vRadii;
uniform float uGrain;
out vec4 outColour;
$NOISE
void main() {
    int kind = int(vParams.x + 0.5);
    float seed = vParams.y * 97.0;
    float g = vParams.z;
    vec2 p = vLocal;
    float a = 0.0;
    float r1 = fract(vParams.y * 7.13);
    float r2 = fract(vParams.y * 13.71);
    float r3 = fract(vParams.y * 31.17);
    // Strokes bend one way or the other, some hardly, some much (in units of their own width).
    float bend = (r1 - 0.5) * 1.3 * vRadii.x / max(vRadii.y, 0.001) * 0.25;
    if (kind == 6) {
        outColour = vec4(vColour.rgb, vColour.a);
        return;
    }
    if (kind == 8 || kind == 9) {
        // Oil: thick paint, its ridges catching a light from the top left.
        float h;
        float edge;
        if (kind == 8) {
            float x = p.x;
            if (x > -1.0 + 2.2 * g) discard;
            float yb = p.y - bend * (x * x - 0.33);
            float w = pow(max(0.0, 1.0 - pow(abs(x), 2.0 + 4.0 * r2)), 0.5) * (0.82 + 0.18 * vnoise(vec2(x * 4.0, seed)));
            w *= mix(1.0, 0.55 + 0.45 * (1.0 - x) * 0.5 + 0.2, r3);
            float rough = (vnoise(vec2(x * (6.0 + 10.0 * r1) + seed, seed * 2.0)) - 0.5) * 0.28;
            float across = abs(yb) / max(w + rough, 0.01);
            if (across > 1.0) discard;
            float ridges = vnoise(vec2(x * 1.2 + seed, yb * (6.0 + 6.0 * r2) + seed * 3.0));
            if (ridges < smoothstep(0.45, 1.0, x) * 0.65) discard;
            h = ridges * 0.75 + (1.0 - across * across) * 0.45;
            edge = across;
        } else {
            float ang = atan(p.y, p.x);
            float d = length(p) / (1.0 + 0.16 * (vnoise(vec2(ang * 1.6, seed)) - 0.5));
            if (d > 1.0) discard;
            h = (1.0 - d * d) * 0.8 + vnoise(p * 3.0 + seed) * 0.3;
            edge = d;
        }
        vec2 n = vec2(dFdx(h), dFdy(h));
        float light = clamp(0.5 + (-n.x + n.y) * 5.0, 0.0, 1.0);
        vec3 col = vColour.rgb * (0.8 + 0.4 * light) + (h - 0.5) * 0.05;
        outColour = vec4(clamp(col, 0.0, 1.0), vColour.a * (1.0 - smoothstep(0.86, 1.0, edge)));
        return;
    }
    if (kind == 10) {
        // A ballpoint: an even line drawn from one end, pressing harder and lighter, skipping a little.
        float across = abs(p.y);
        if (abs(p.x) > 1.0 || across > 1.0) discard;
        if (p.x > -1.0 + 2.2 * max(g, 0.0001)) discard;
        float press = 0.72 + 0.28 * vnoise(vec2(p.x * 3.0 + seed * 7.0, seed));
        float skip = step(0.1, vnoise(vec2(p.x * 22.0 + seed * 13.0, 0.3)));
        a = (1.0 - smoothstep(0.4, 1.0, across)) * press * mix(0.55, 1.0, skip);
    } else if (kind == 11) {
        // Coloured pencil: close hatching over an area, its grain showing, its edge loose.
        if (p.x > -1.2 + 2.6 * g) discard;
        float d = length(p) + (fbm(p * 1.4 + seed) - 0.5) * 0.4;
        if (d > 1.0) discard;
        vec2 px = p * vRadii;
        float ang = r1 * 3.14159;
        float u = px.x * cos(ang) + px.y * sin(ang);
        float v = abs(fract(u / 4.0) - 0.5) * 2.0;
        float lines = smoothstep(0.35, 0.75, v);
        a = lines * (0.45 + 0.75 * paper(vCanvas)) * (1.0 - smoothstep(0.7, 1.0, d));
    }
    if (kind == 10 || kind == 11) {
        a = clamp(a * vColour.a, 0.0, 1.0);
        outColour = vec4(mix(vec3(1.0), vColour.rgb, a), 1.0);
        return;
    }
    if (kind == 0 || kind == 4) {
        // A blot: spreads as it is wet, its rim uneven; pigment pools at the edge as it dries.
        float grow = kind == 0 ? 0.35 + 0.65 * (1.0 - (1.0 - g) * (1.0 - g)) : 0.5 + 0.5 * g;
        float ang = atan(p.y, p.x);
        float amp = kind == 0 ? 0.14 + 0.32 * r1 : 0.08 + 0.12 * r1;
        float rim = 1.0 + amp * (fbm(vec2(cos(ang), sin(ang)) * (1.0 + 2.4 * r2) + seed) - 0.5) * 2.0;
        float d = length(p) / (grow * rim);
        if (d > 1.0) discard;
        float body = 0.45 + 0.55 * fbm(p * (1.4 + 2.0 * r3) + seed);
        float edge = smoothstep(0.55 + 0.1 * r2, 0.98, d) * (kind == 0 ? 0.95 : 0.6) * g;
        if (kind == 0 && r3 > 0.62) {
            // A backrun: wet came back into the drying blot, leaving a ragged ring inside.
            float ring = 0.4 + 0.25 * r2 + (fbm(p * 3.0 + seed * 1.7) - 0.5) * 0.25;
            edge += 0.7 * g * (1.0 - smoothstep(0.0, 0.06, abs(d - ring)));
        }
        float soft = 1.0 - smoothstep(0.9, 1.0, d);
        a = (body * 0.65 + edge) * soft * (0.7 + 0.6 * paper(vCanvas));
    } else if (kind == 1) {
        // A brush stroke, laid from one end: tapered, its bristles in streaks, dry at the tail.
        float x = p.x; float y = p.y - bend * (x * x - 0.33);
        if (x > -1.0 + 2.2 * g) discard;
        float taper = pow(max(0.0, 1.0 - x * x), 0.3 + 0.6 * r2) * (0.75 + 0.25 * vnoise(vec2(x * 3.0, seed)));
        taper *= mix(1.0, 1.25 - 0.45 * (x * 0.5 + 0.5), r3);
        float rough = (vnoise(vec2(x * (5.0 + 9.0 * r1) + seed, seed)) - 0.5) * 0.36;
        float across = abs(y) / max(taper + rough, 0.01);
        if (across > 1.0) discard;
        float bristle = vnoise(vec2(x * 1.3 + seed, y * 7.0 + seed * 3.0));
        float dry = smoothstep(0.35, 1.0, x);
        if (bristle < dry * 0.75) discard;
        a = (0.5 + 0.5 * bristle) * (0.75 + 0.5 * paper(vCanvas)) * (1.0 + 0.35 * smoothstep(0.55, 1.0, across));
        a *= 1.0 - smoothstep(0.85, 1.0, across);
    } else if (kind == 2) {
        // A dry-brush line: broken where the brush skips over the paper's grain.
        float x = p.x;
        if (abs(x) > 1.0) discard;
        float w = 1.0 - 0.35 * abs(x);
        float across = abs(p.y) / w;
        if (across > 1.0) discard;
        float skip = vnoise(vec2(x * 7.0 + seed * 5.0, 0.5)) + paper(vCanvas) * 0.5 - 0.25;
        a = smoothstep(0.3, 0.5, skip) * (1.0 - smoothstep(0.5, 1.0, across));
    } else if (kind == 7) {
        // The pen: an even line with soft sides, ink a little uneven on the paper.
        float across = abs(p.y);
        if (abs(p.x) > 1.0 || across > 1.0) discard;
        a = (1.0 - smoothstep(0.55, 1.0, across)) * (0.8 + 0.35 * paper(vCanvas));
    } else if (kind == 3) {
        // A wash: broad, its edge lost in clouds of pigment.
        float d = length(p) / (0.55 + 0.45 * g);
        float n = fbm(p * 1.3 + seed);
        float shape = 1.0 - smoothstep(0.3, 1.0, d + (n - 0.5) * 0.55);
        a = shape * (0.55 + 0.55 * fbm(p * 3.0 + seed * 2.0)) * (0.8 + 0.4 * paper(vCanvas));
    } else if (kind == 5) {
        // The seal: a square stamp with its mark cut in, grainy as stamped ink.
        float m = max(abs(p.x), abs(p.y));
        if (m > 1.0) discard;
        vec2 q = p * 0.5 + 0.5;
        float border = step(0.8, m);
        vec2 cell = floor(q * vec2(3.0, 4.0));
        float on = step(0.45, hash(cell + seed));
        vec2 f = fract(q * vec2(3.0, 4.0));
        float inner = step(m, 0.66) * on * step(0.18, f.x) * step(f.x, 0.82) * step(0.15, f.y) * step(f.y, 0.85);
        a = max(border, inner) * (0.5 + 0.7 * vnoise(vCanvas * 0.8)) * (0.3 + 0.7 * g);
    }
    a = clamp(a * vColour.a, 0.0, 1.0);
    outColour = vec4(mix(vec3(1.0), vColour.rgb, a), 1.0);
}
"""

        const val PAPER_VS = """#version 300 es
layout(location = 0) in vec4 aCorner;
out vec2 vUv;
void main() {
    vUv = aCorner.zw;
    gl_Position = vec4(aCorner.xy, 0.0, 1.0);
}
"""

        const val FADE_FS = """#version 300 es
precision highp float;
uniform vec2 uSize;
uniform vec4 uFade;
out vec4 outColour;
void main() {
    float x = gl_FragCoord.x;
    float y = gl_FragCoord.y;
    float f = 1.0;
    if (uFade.x > 0.0) f *= smoothstep(0.0, uFade.x, x);
    if (uFade.y > 0.0) f *= smoothstep(0.0, uFade.y, uSize.y - y);
    if (uFade.z > 0.0) f *= smoothstep(0.0, uFade.z, uSize.x - x);
    if (uFade.w > 0.0) f *= smoothstep(0.0, uFade.w, y);
    outColour = vec4(0.0, 0.0, 0.0, f);
}
"""

        const val PAPER_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform int uMode;
uniform float uAlpha;
uniform vec2 uSize;
uniform float uGrain;
uniform vec3 uPaper;
uniform int uStyle;
uniform float uSquared;
out vec4 outColour;
$NOISE
void main() {
    if (uMode == 1) {
        outColour = vec4(texture(uTex, vUv).rgb, uAlpha);
        return;
    }
    if (uMode == 2) {
        outColour = vec4(texture(uTex, vec2(vUv.x, 1.0 - vUv.y)).rgb, 1.0);
        return;
    }
    vec2 c = vec2(vUv.x, 1.0 - vUv.y) * uSize;
    vec2 v = vUv - 0.5;
    float vignette = 1.0 - 0.1 * dot(v, v) * 2.0;
    if (uStyle == 2) {
        // Oil: the paint itself, on the weave of the canvas.
        float k = 1.7 * max(uSize.x, uSize.y) / 1600.0;
        float weave = 0.5 + 0.25 * sin(c.x * 1.9 / k) + 0.25 * sin(c.y * 1.9 / k + 1.3 * sin(c.x * 0.05));
        outColour = vec4(texture(uTex, vUv).rgb * (0.93 + 0.09 * weave) * vignette, 1.0);
        return;
    }
    vec3 sheet = uPaper * (0.955 + 0.06 * paper(c));
    if (uStyle == 3) {
        // Ink: long fibres in the paper.
        sheet *= 0.975 + 0.035 * vnoise(vec2(c.x * 0.015, c.y * 0.6));
    }
    if (uStyle == 4) {
        // Ballpoint: a notebook's page, lined with a margin, or squared.
        float sp = max(uSize.x, uSize.y) / 42.0;
        vec3 blue = vec3(0.6, 0.72, 0.9);
        float dy = abs(c.y - floor(c.y / sp + 0.5) * sp);
        sheet = mix(sheet, sheet * blue, (1.0 - smoothstep(0.5, 1.4, dy)) * 0.6);
        if (uSquared > 0.5) {
            float dx = abs(c.x - floor(c.x / sp + 0.5) * sp);
            sheet = mix(sheet, sheet * blue, (1.0 - smoothstep(0.5, 1.4, dx)) * 0.5);
        } else {
            float dm = abs(c.x - uSize.x * 0.11);
            sheet = mix(sheet, sheet * vec3(0.96, 0.55, 0.55), (1.0 - smoothstep(0.6, 1.6, dm)) * 0.75);
        }
    }
    outColour = vec4(sheet * texture(uTex, vUv).rgb * vignette, 1.0);
}
"""
    }
}
