package com.sadurni.attacca.ui.world

import android.content.Context
import android.opengl.GLES30

/**
 * Development (only while logs are kept): how long Attacca World's frames take on this phone,
 * to know where the time goes. Every [WINDOW_MS] a line goes to the log with the frames per
 * second, the time between frames, the CPU's time, the time with the GPU (waiting for it every
 * [FINISH_EVERY]th frame), the draws and vertices per frame, and, where the GPU has timers, the
 * GPU's own time for each part of the frame ([Section]). With [PHASES], it goes through ways of
 * drawing every [PHASE_MS] (see [Phase]).
 */
class WorldBench(private val context: Context) {

    /** The ways of drawing gone through: as now, at 70 % of the size (scaled up), with 4x antialiasing. */
    enum class Phase(val label: String, val msaa: Boolean, val scale: Float, val shadowPass: Boolean = false) {
        NORMAL("as now", false, 1f),
        LOW("70 % size", false, 0.7f),
        MSAA("4x antialiasing", true, 1f),
    }

    /** The parts of a frame timed on the GPU, in the order they are drawn. */
    enum class Section(val label: String) { CLEAR("clear"), LAND("land"), SKY("sky"), FIGURES("figures"), REST("rest"), COPY("copy") }

    val on: Boolean get() = com.sadurni.attacca.Logs.on

    /** The way of drawing now. */
    var phase = Phase.NORMAL
        private set

    // Counted by the renderer while a frame is drawn.
    var draws = 0
    var vertices = 0L

    private var startedAt = 0L
    private var windowStart = 0L
    private var phaseStart = 0L
    private var lastBegin = 0L
    private var frameStart = 0L
    private var frames = 0
    private val intervals = ArrayList<Float>()
    private val cpu = ArrayList<Float>()
    private val full = ArrayList<Float>()
    private var drawsSum = 0L
    private var verticesSum = 0L
    private var told = false
    private var width = 0
    private var height = 0

    // GPU timers: a set of queries per frame, read some frames later.
    private var timers = false
    private var queries: IntArray? = null
    private val used = IntArray(RING)
    private var slot = 0
    private var open = -1
    private val gpuSums = DoubleArray(Section.entries.size)
    private var gpuFrames = 0

    fun begin(w: Int, h: Int) {
        if (!on) return
        val now = System.nanoTime()
        width = w
        height = h
        if (startedAt == 0L) {
            startedAt = now
            windowStart = now
            phaseStart = now
        }
        if (!told) {
            told = true
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            timers = "GL_EXT_disjoint_timer_query" in ext
            note(
                "World bench: ${GLES30.glGetString(GLES30.GL_RENDERER)} · ${GLES30.glGetString(GLES30.GL_VERSION)} · " +
                    "timer queries ${if (timers) "yes" else "no"} · max samples ${intOf(GLES30.GL_MAX_SAMPLES)}",
            )
        }
        if (lastBegin != 0L) intervals += (now - lastBegin) / 1e6f
        lastBegin = now
        frameStart = now
        draws = 0
        vertices = 0
        if (PHASES && now - phaseStart >= PHASE_MS * 1_000_000L) {
            flush(now)
            phase = Phase.entries[(phase.ordinal + 1) % Phase.entries.size]
            phaseStart = now
        }
        used[slot] = 0
        open = -1
        section(Section.CLEAR)
    }

    /** From here on the GPU's time counts for [s] (until the next section or the frame's end). */
    fun section(s: Section) {
        if (!on || !timers) return
        val q = queries ?: IntArray(RING * Section.entries.size).also { GLES30.glGenQueries(it.size, it, 0); queries = it }
        if (open >= 0) GLES30.glEndQuery(TIME_ELAPSED)
        GLES30.glBeginQuery(TIME_ELAPSED, q[slot * Section.entries.size + s.ordinal])
        used[slot] = used[slot] or (1 shl s.ordinal)
        open = s.ordinal
    }

    fun end() {
        if (!on || frameStart == 0L) return
        if (open >= 0) GLES30.glEndQuery(TIME_ELAPSED)
        open = -1
        cpu += (System.nanoTime() - frameStart) / 1e6f
        frames++
        drawsSum += draws
        verticesSum += vertices
        if (timers) {
            slot = (slot + 1) % RING
            collect(slot)
        }
        if (frames % FINISH_EVERY == 0) {
            GLES30.glFinish()
            full += (System.nanoTime() - frameStart) / 1e6f
        }
        val now = System.nanoTime()
        if (now - windowStart >= WINDOW_MS * 1_000_000L) flush(now)
    }

    /** Reads the times of the frame drawn [RING] frames ago in [s] (the slot about to be used again). */
    private fun collect(s: Int) {
        val q = queries ?: return
        val mask = used[s]
        if (mask == 0) return
        used[s] = 0
        val out = IntArray(1)
        val times = DoubleArray(Section.entries.size)
        for (i in Section.entries.indices) {
            if (mask and (1 shl i) == 0) continue
            val id = q[s * Section.entries.size + i]
            GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT_AVAILABLE, out, 0)
            if (out[0] == 0) return
            GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT, out, 0)
            times[i] = (out[0].toLong() and 0xFFFFFFFFL) / 1e6
        }
        // A frequency change or the like in between makes the times worthless.
        val disjoint = IntArray(1).also { GLES30.glGetIntegerv(GPU_DISJOINT, it, 0) }[0]
        if (disjoint != 0) return
        for (i in times.indices) gpuSums[i] += times[i]
        gpuFrames++
    }

    private fun flush(now: Long) {
        if (frames > 0) {
            val seconds = (now - windowStart) / 1e9f
            val gpu = if (gpuFrames > 0) {
                val parts = Section.entries.joinToString(" · ") { "%s %.1f".format(java.util.Locale.ROOT, it.label, gpuSums[it.ordinal] / gpuFrames) }
                " · gpu %.1f ms (%s)".format(java.util.Locale.ROOT, gpuSums.sum() / gpuFrames, parts)
            } else ""
            note(
                "World frames (%s, %dx%d): %.1f fps · between %s · cpu %s · cpu+gpu %s · %d draws · %dk vertices%s".format(
                    java.util.Locale.ROOT, phase.label, width, height, frames / seconds,
                    stats(intervals), stats(cpu), stats(full), drawsSum / frames, verticesSum / frames / 1000, gpu,
                ),
            )
        }
        windowStart = now
        frames = 0
        intervals.clear(); cpu.clear(); full.clear()
        drawsSum = 0; verticesSum = 0
        gpuSums.fill(0.0); gpuFrames = 0
    }

    private fun stats(values: List<Float>): String {
        if (values.isEmpty()) return "-"
        val sorted = values.sorted()
        return "%.1f/%.1f ms".format(java.util.Locale.ROOT, values.average(), sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)])
    }

    private fun intOf(name: Int): Int = IntArray(1).also { GLES30.glGetIntegerv(name, it, 0) }[0]

    private fun note(text: String) = com.sadurni.attacca.ExitLog.note(context, text)

    // The picture the frame is drawn into (antialiased, or smaller), then copied to the screen.
    private var fbo = 0
    private var colour = 0
    private var depth = 0
    private var fboW = 0
    private var fboH = 0
    private var fboSamples = -1

    /** Whether frames are drawn into a picture of their own now (and copied to the screen). */
    val offscreen: Boolean get() = on && (phase.msaa || phase.scale < 1f)

    /** Draws into the picture for this phase, made for a screen of [w] x [h] when needed. */
    fun bindOffscreen(w: Int, h: Int) {
        val pw = (w * phase.scale).toInt().coerceAtLeast(1)
        val ph = (h * phase.scale).toInt().coerceAtLeast(1)
        val samples = if (phase.msaa) 4 else 0
        if (fbo == 0 || fboW != pw || fboH != ph || fboSamples != samples) {
            release()
            val ids = IntArray(2)
            GLES30.glGenRenderbuffers(2, ids, 0)
            colour = ids[0]
            depth = ids[1]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, colour)
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, pw, ph)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, pw, ph)
            val fb = IntArray(1)
            GLES30.glGenFramebuffers(1, fb, 0)
            fbo = fb[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, colour)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
            fboW = pw
            fboH = ph
            fboSamples = samples
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, pw, ph)
    }

    /** Copies the picture to the screen of [w] x [h] (scaled up when smaller). */
    fun resolve(w: Int, h: Int) {
        section(Section.COPY)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        // (A multisampled picture is copied at its own size; a smaller one is scaled up, smoothly.)
        GLES30.glBlitFramebuffer(0, 0, fboW, fboH, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, if (fboSamples > 0) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
    }

    private fun release() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (colour != 0) GLES30.glDeleteRenderbuffers(2, intArrayOf(colour, depth), 0)
        fbo = 0; colour = 0; depth = 0
    }

    // Kept for a shadow pass to measure (not among the phases now).
    fun bindShadow() = Unit

    fun unbindShadow(target: Int, w: Int, h: Int) = Unit

    /** The framebuffer frames are drawn into now (0: the screen). */
    fun target(): Int = if (offscreen) fbo else 0

    /** A new surface: what was made on the GPU is gone. */
    fun surfaceLost() {
        fbo = 0; colour = 0; depth = 0; fboW = 0; fboH = 0; fboSamples = -1
        queries = null
        used.fill(0)
        open = -1
        told = false
    }

    companion object {
        /** Whether it goes through the ways of drawing (a test build for measuring). */
        const val PHASES = false
        const val PHASE_MS = 15_000L
        const val WINDOW_MS = 10_000L
        const val FINISH_EVERY = 4

        /** Frames kept waiting for their GPU times. */
        const val RING = 4

        // GL_EXT_disjoint_timer_query.
        private const val TIME_ELAPSED = 0x88BF
        private const val GPU_DISJOINT = 0x8FBB
    }
}
