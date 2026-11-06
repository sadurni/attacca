package com.sadurni.attaccaplg.player

import com.sadurni.attacca.dsp.CrossfadePlan
import com.sadurni.attacca.dsp.CrossfadePlanner
import com.sadurni.attacca.dsp.CrossfadeShape
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attacca.dsp.Resampler
import com.sadurni.attaccaplg.audio.AudioOut
import com.sadurni.attaccaplg.audio.PcmSource
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.log10
import kotlin.math.pow

/** One track of the queue, with what the library knows of it (null: not measured). */
data class PlayItem(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Where its sound starts and ends (silences measured), or null. */
    val soundStartMs: Long? = null,
    val soundEndMs: Long? = null,
    val lufs: Float? = null,
    /** Sample peak, 0 … 1 (0: unknown). */
    val peak: Float = 0f,
    val waveform: FloatArray? = null,
    val track: Int? = null,
    val disc: Int? = null,
) {
    val file: File get() = File(path)
    val folder: String get() = File(path).parent.orEmpty()

    override fun equals(other: Any?) = other is PlayItem && other.path == path
    override fun hashCode() = path.hashCode()
}

/** How tracks are joined and leveled (the player's settings). */
data class PlaybackSettings(
    /** Blend length; 0: no crossfade. */
    val crossfadeMs: Int = 10_000,
    val crossfadeStyle: CrossfadeStyle = CrossfadeStyle.AUTOMATIC,
    /** Leave out the silence before and after each track (not between tracks of one album played in order). */
    val trimSilences: Boolean = true,
    /** Bring every track to the same loudness (from the measured LUFS). */
    val levelLoudness: Boolean = true,
    val volume: Float = 1f,
)

/** What the player is doing, for the window. */
data class PlayerSnapshot(
    val index: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    /** Changes whenever the playing track changes by itself (end, blend). */
    val trackChanges: Int = 0,
    /** Where the playing track's sound is taken from and to (its silences left out), ms. */
    val cutStartMs: Long = 0,
    val cutEndMs: Long = 0,
    /** How long the blend into the next track lasts (before [cutEndMs]); 0: none. */
    val blendMs: Int = 0,
)

/**
 * Plays a queue through [AudioOut] on its own thread, as Attacca does on the phone: each track
 * from where its sound starts to where it ends (its silences left out), at the same loudness,
 * blended into the next as the crossfade style says (the automatic one judges each join from
 * how the two tracks end and start), and without a pause between tracks of one album played in
 * order. Commands come from any thread and are carried out between blocks of sound.
 */
class PlaybackEngine(
    private val settings: () -> PlaybackSettings,
    /** Attacca's sound processing for the output in use (equalizer, bass, crossfeed, limiter…). */
    private val dsp: () -> com.sadurni.attacca.settings.DspSettings = { com.sadurni.attacca.settings.DspSettings() },
    /** Opens the output for a device name (the sound card; a capture in tests). */
    private val openSink: (String?) -> com.sadurni.attaccaplg.audio.SoundSink = { AudioOut(it) },
) {

    @Volatile var snapshot = PlayerSnapshot()
        private set

    /** Called on the engine's thread when it changes track by itself, or the queue ends. */
    @Volatile var onChange: (() -> Unit)? = null

    /** 0: no repeat, 1: the whole queue, 2: the playing track. */
    @Volatile var repeatMode = 0

    /** Stop when this track of the queue ends (sleep timer), or -1. */
    @Volatile var stopAfter = -1

    @Volatile private var device: String? = null
    private val commands = LinkedBlockingQueue<() -> Unit>()

    private var queue: List<PlayItem> = emptyList()
    private var index = -1
    private var playing = false
    private var changes = 0
    private var out: com.sadurni.attaccaplg.audio.SoundSink? = null
    private var cur: TrackReader? = null
    private var ahead: TrackReader? = null
    private var aheadIndex = -1
    private var tail: TrackReader? = null
    private var fadeFrames = 0
    private var fadePos = 0
    private var fadeShape = CrossfadeShape.CROSSFADE
    private var plan: CrossfadePlan? = null
    private var alive = true
    private val effects = com.sadurni.attacca.dsp.EffectsChain(AudioOut.RATE)

    /** Levels of the sound going out, for Attacca O'rama. */
    private val meter = com.sadurni.attacca.diorama.LevelMeter()

    /** Frames handed to the output since the engine started. */
    @Volatile private var outFrames = 0L

    /** The levels (level, low, high) of what is heard now, into [into]; false when none are known. */
    fun heardLevels(into: FloatArray): Boolean {
        val o = out ?: return false
        if (!playing) return false
        val heard = outFrames - o.pending
        return meter.at(heard * 1_000_000L / AudioOut.RATE, into)
    }

    // Started last: the loop needs every field above.
    private val thread = Thread(::loop, "Attacca playback").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    fun play(items: List<PlayItem>, start: Int, positionMs: Long = 0, autoplay: Boolean = true) = command {
        queue = items
        dropAll()
        out?.flush()
        index = start.coerceIn(-1, items.lastIndex)
        if (index >= 0) open(index, positionMs)
        playing = autoplay && cur != null
        if (!playing) out?.pause() else out?.resume()
        publish()
    }

    /** The queue changed around the playing track (added, removed, reordered): [newIndex] is where it is now. */
    fun setQueue(items: List<PlayItem>, newIndex: Int) = command {
        queue = items
        index = newIndex
        ahead?.close()
        ahead = null
        aheadIndex = -1
        plan = null
        publish()
    }

    fun toggle() = command {
        if (cur == null && queue.isNotEmpty()) {
            open(index.coerceAtLeast(0), 0)
            index = index.coerceAtLeast(0)
        }
        playing = !playing && cur != null
        if (playing) out?.resume() else out?.pause()
        publish()
    }

    fun pause() = command {
        playing = false
        out?.pause()
        publish()
    }

    fun seek(ms: Long) = command {
        if (index < 0) return@command
        dropAll()
        out?.flush()
        open(index, ms)
        publish()
    }

    fun next() = command { jump(index + 1) }

    fun previous() = command {
        if (cur != null && positionMs() > 3000) {
            dropAll()
            out?.flush()
            open(index, 0)
            publish()
        } else {
            jump(index - 1)
        }
    }

    fun jumpTo(i: Int) = command { jump(i) }

    fun setDevice(name: String?) = command {
        device = name
        out?.close()
        out = null
    }

    fun close() {
        command { alive = false }
        thread.join(2000)
    }

    private fun jump(i: Int) {
        if (i !in queue.indices) return
        dropAll()
        out?.flush()
        index = i
        open(i, 0)
        playing = true
        out?.resume()
        publish()
    }

    private fun command(action: () -> Unit) {
        commands.put(action)
    }

    // ---------------- the engine's thread ----------------

    private fun loop() {
        val a = FloatArray(CHUNK * 2)
        val b = FloatArray(CHUNK * 2)
        while (alive) {
            val first = if (playing) commands.poll() else commands.take()
            if (first != null) {
                runCatching { first() }
                while (true) runCatching { (commands.poll() ?: break)() }
            }
            if (!alive) break
            if (!playing || cur == null) continue
            if (out == null) out = runCatching { openSink(device) }.getOrNull()
            val output = out
            if (output == null) {
                // No sound card to play on.
                playing = false
                publish()
                continue
            }
            runCatching { step(output, a, b) }.onFailure {
                playing = false
                publish()
            }
        }
        dropAll()
        out?.close()
    }

    /** One block of sound: the playing track, the one fading out, the joins. */
    private fun step(output: com.sadurni.attaccaplg.audio.SoundSink, a: FloatArray, b: FloatArray) {
        val current = cur ?: return
        prepareAhead(current)
        // The blend into the next track starts when what is left of this one is the blend.
        val p = plan
        if (tail == null && p != null && ahead != null && current.remainingFrames in 0..msToFrames(p.lengthMs.toLong())) {
            startBlend(p)
        }
        var filled = 0
        while (filled < CHUNK) {
            val c = cur ?: break
            val n = c.read(a, filled, CHUNK - filled)
            filled += n
            if (n == 0) {
                // The end of the track: straight into the next one (gapless), or the end of the queue.
                if (!advance()) break
            }
        }
        val t = tail
        if (t != null) {
            val n = t.read(b, 0, filled)
            for (f in 0 until filled) {
                val x = (fadePos + f).toDouble() / fadeFrames
                val gIn = fadeShape.inGain(x).toFloat()
                val gOut = if (f < n) fadeShape.outGain(x).toFloat() else 0f
                a[2 * f] = a[2 * f] * gIn + (if (f < n) b[2 * f] * gOut else 0f)
                a[2 * f + 1] = a[2 * f + 1] * gIn + (if (f < n) b[2 * f + 1] * gOut else 0f)
            }
            fadePos += filled
            if (n < filled || fadePos >= fadeFrames) {
                t.close()
                tail = null
            }
        }
        if (filled > 0) {
            effects.headroomDb = cur?.headroomDb ?: 0f
            effects.process(a, filled, dsp())
            meter.push(a, filled, 2, AudioOut.RATE, outFrames * 1_000_000L / AudioOut.RATE)
            outFrames += filled
            output.write(a, filled, settings().volume)
        }
        if (cur == null && tail == null) {
            // The end of the queue: let it play out.
            output.drain()
            playing = false
            // Stopped by the sleep timer: ready at the track after it; else at the end of the queue.
            index = if (stopAfter >= 0) (stopAfter + 1).coerceAtMost(queue.lastIndex) else queue.lastIndex.coerceAtLeast(-1)
            stopAfter = -1
            changes++
            publish()
            onChange?.invoke()
            return
        }
        publish()
    }

    /** The track after the playing one, as the repeat mode says; -1 at the end of the queue. */
    private fun nextIndex(): Int = when {
        queue.isEmpty() || index < 0 -> -1
        index == stopAfter -> -1
        repeatMode == 2 -> index
        index + 1 in queue.indices -> index + 1
        repeatMode == 1 -> 0
        else -> -1
    }

    /** Moves on to the next track at the end of this one; false at the end of the queue. */
    private fun advance(): Boolean {
        val next = nextIndex()
        cur?.close()
        cur = null
        if (next !in queue.indices) return false
        val reader = if (aheadIndex == next) ahead else null
        ahead = null
        aheadIndex = -1
        cur = reader ?: openReader(next, null) ?: return false
        index = next
        plan = null
        changes++
        onChange?.invoke()
        return true
    }

    private fun startBlend(p: CrossfadePlan) {
        val next = ahead ?: return
        tail = cur
        cur = next
        index = aheadIndex
        ahead = null
        aheadIndex = -1
        fadeFrames = maxOf(1, msToFrames(p.lengthMs.toLong()).toInt())
        fadePos = 0
        fadeShape = p.shape
        plan = null
        changes++
        onChange?.invoke()
    }

    /** Opens the next track ahead of time (its decoder ready, nothing read) and plans the join. */
    private fun prepareAhead(current: TrackReader) {
        val nextIndex = nextIndex()
        if (ahead != null || nextIndex < 0) return
        val left = current.remainingFrames
        if (left > msToFrames(AHEAD_MS)) return
        ahead = openReader(nextIndex, null)
        aheadIndex = if (ahead != null) nextIndex else -1
        plan = ahead?.let { planJoin(queue[index], queue[nextIndex]) }
    }

    private fun open(i: Int, positionMs: Long) {
        cur = openReader(i, positionMs)
        changes++
    }

    /** A reader for track [i] from [positionMs] (null: from the start of its sound). */
    private fun openReader(i: Int, positionMs: Long?): TrackReader? {
        val item = queue.getOrNull(i) ?: return null
        val s = settings()
        val prev = queue.getOrNull(i - 1)
        val next = queue.getOrNull(i + 1)
        val start = positionMs ?: if (s.trimSilences && (prev == null || !followsOnAlbum(prev, item))) trimStart(item) else 0L
        val end = if (s.trimSilences && (next == null || !followsOnAlbum(item, next))) trimEnd(item) else null
        val gain = if (s.levelLoudness) gainDb(item) else 0f
        return runCatching { TrackReader(item, start, end, gain) }.getOrNull()
    }

    private fun planJoin(a: PlayItem, b: PlayItem): CrossfadePlan? {
        val s = settings()
        if (s.crossfadeMs <= 0 || followsOnAlbum(a, b)) return null
        if (b.durationMs in 1 until s.crossfadeMs + 2000L) return null
        return when (s.crossfadeStyle) {
            CrossfadeStyle.MIX -> CrossfadePlan(s.crossfadeMs, CrossfadeShape.MIX)
            CrossfadeStyle.AUTOMATIC -> CrossfadePlanner.plan(
                s.crossfadeMs,
                a.waveform ?: FloatArray(0), a.durationMs, a.soundEndMs ?: a.durationMs,
                b.waveform ?: FloatArray(0), b.durationMs, b.soundStartMs ?: 0L,
            )
            else -> CrossfadePlan(s.crossfadeMs, CrossfadeShape.CROSSFADE)
        }
    }

    private fun dropAll() {
        effects.clear()
        cur?.close(); cur = null
        ahead?.close(); ahead = null; aheadIndex = -1
        tail?.close(); tail = null
        plan = null
    }

    private fun positionMs(): Long {
        val c = cur ?: return 0
        val heard = c.positionMs - (out?.pending ?: 0) * 1000 / AudioOut.RATE
        return heard.coerceAtLeast(0)
    }

    private fun publish() {
        val item = queue.getOrNull(index)
        val (from, to, blend) = cutOf(index)
        snapshot = PlayerSnapshot(index, if (cur != null) positionMs() else 0, item?.durationMs ?: 0, playing, changes, from, to, blend)
    }

    private var cutKey: List<Any?>? = null
    private var cut = Triple(0L, 0L, 0)

    /** Where track [i] plays from and to, and its blend into the next (worked out once per change). */
    private fun cutOf(i: Int): Triple<Long, Long, Int> {
        val item = queue.getOrNull(i) ?: return Triple(0L, 0L, 0)
        val s = settings()
        val next = nextIndex()
        val key = listOf(i, System.identityHashCode(queue), s, next, aheadIndex, plan)
        if (key == cutKey) return cut
        val prev = queue.getOrNull(i - 1)
        val following = queue.getOrNull(i + 1)
        val start = if (s.trimSilences && (prev == null || !followsOnAlbum(prev, item))) trimStart(item) else 0L
        val end = (if (s.trimSilences && (following == null || !followsOnAlbum(item, following))) trimEnd(item) else null)
            ?: item.durationMs
        val blend = when {
            next < 0 -> 0
            aheadIndex == next -> plan?.lengthMs ?: 0
            else -> planJoin(item, queue[next])?.lengthMs ?: 0
        }
        cut = Triple(start, end, blend)
        cutKey = key
        return cut
    }

    companion object {
        /** Frames per block (about 23 ms). */
        private const val CHUNK = 1024

        /** The next track is opened this long before the end of the playing one. */
        private const val AHEAD_MS = 20_000L

        /** Silence kept before and after the sound (as Attacca's trims). */
        private const val TRIM_MARGIN_MS = 100L

        /** Loudness every track is brought to (LUFS). */
        private const val TARGET_LUFS = -18f
        private const val MAX_GAIN_DB = 12f

        fun msToFrames(ms: Long): Long = ms * AudioOut.RATE / 1000

        fun trimStart(item: PlayItem): Long = ((item.soundStartMs ?: 0) - TRIM_MARGIN_MS).coerceAtLeast(0)

        fun trimEnd(item: PlayItem): Long? = item.soundEndMs?.let { (it + TRIM_MARGIN_MS).coerceAtMost(item.durationMs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE) }

        /** The gain that brings [item] to the target loudness, never past its peak. */
        fun gainDb(item: PlayItem): Float {
            val lufs = item.lufs ?: return 0f
            var gain = (TARGET_LUFS - lufs).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
            if (item.peak > 0f) gain = minOf(gain, -20f * log10(item.peak) - 0.3f)
            return gain
        }

        /** [b] is the track after [a] on their album (same folder and album, next number or name): played without a blend. */
        fun followsOnAlbum(a: PlayItem, b: PlayItem): Boolean {
            if (a.folder != b.folder || a.album.isBlank() || a.album != b.album) return false
            val ta = a.track
            val tb = b.track
            if (ta != null && tb != null) return (a.disc ?: 1) == (b.disc ?: 1) && tb == ta + 1 || (b.disc ?: 1) == (a.disc ?: 1) + 1 && tb == 1
            return File(b.path).name > File(a.path).name
        }
    }
}

/**
 * One track as the output wants it: from [startMs] in the file, stereo at the output rate, with
 * its gain, up to [endMs] (null: the end of the file).
 */
class TrackReader(val item: PlayItem, private val startMs: Long, private val endMs: Long?, gainDb: Float) : AutoCloseable {

    private val source = PcmSource.open(item.file, startMs) ?: throw IllegalStateException("cannot decode ${item.path}")
    private val inChannels = source.format.channels
    private val resampler = Resampler(source.format.sampleRate, AudioOut.RATE, 2)
    private val gain = 10f.pow(gainDb / 20f)

    /** Room this track leaves under full scale with its gain (dB); full scale when its peak is unknown. */
    val headroomDb: Float = -(gainDb + if (item.peak > 0f) 20f * kotlin.math.log10(item.peak) else 0f)
    private val decoded = FloatArray(4096 * maxOf(2, inChannels))
    private val stereo = FloatArray(4096 * 2)
    private var fifo = FloatArray(16384 * 2)
    private var fifoStart = 0
    private var fifoFrames = 0
    private var ended = false

    /** Output frames handed out so far. */
    private var produced = 0L
    private val limit: Long? = endMs?.let { PlaybackEngine.msToFrames((it - startMs).coerceAtLeast(0)) }

    /** Where in the file it is (what was handed out). */
    val positionMs: Long get() = startMs + produced * 1000 / AudioOut.RATE

    /** Frames left to its end point; Long.MAX_VALUE when not known. */
    val remainingFrames: Long
        get() = limit?.let { it - produced }
            ?: item.durationMs.takeIf { it > 0 }?.let { PlaybackEngine.msToFrames(it - startMs) - produced }
            ?: Long.MAX_VALUE

    /** Puts up to [frames] stereo frames into [dst] from frame [at]; fewer only at its end. */
    fun read(dst: FloatArray, at: Int, frames: Int): Int {
        var want = frames
        limit?.let { want = minOf(want.toLong(), (it - produced).coerceAtLeast(0)).toInt() }
        var done = 0
        while (done < want) {
            if (fifoFrames == 0 && !refill()) break
            val n = minOf(want - done, fifoFrames)
            val from = fifoStart * 2
            val to = (at + done) * 2
            for (i in 0 until n * 2) dst[to + i] = fifo[from + i] * gain
            fifoStart += n
            fifoFrames -= n
            done += n
        }
        produced += done
        return done
    }

    private fun refill(): Boolean {
        if (ended) return false
        fifoStart = 0
        while (fifoFrames == 0 && !ended) {
            val n = source.read(decoded, 4096)
            if (n == 0) {
                ended = true
                resampler.finish(::append)
                break
            }
            for (f in 0 until n) {
                val l = decoded[f * inChannels]
                val r = if (inChannels > 1) decoded[f * inChannels + 1] else l
                stereo[2 * f] = l
                stereo[2 * f + 1] = r
            }
            resampler.process(stereo, n, ::append)
        }
        return fifoFrames > 0
    }

    private fun append(samples: FloatArray, frames: Int) {
        val end = fifoStart + fifoFrames
        if (fifo.size < (end + frames) * 2) fifo = fifo.copyOf(maxOf((end + frames) * 2, fifo.size * 2))
        System.arraycopy(samples, 0, fifo, end * 2, frames * 2)
        fifoFrames += frames
    }

    override fun close() = source.close()
}
