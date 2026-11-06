package com.sadurni.attacca.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sadurni.attacca.Background
import com.sadurni.attacca.analysis.AheadDecoder
import com.sadurni.attacca.analysis.TailDecoder
import com.sadurni.attacca.dsp.CrossfadeShape
import com.sadurni.attacca.dsp.CrossfadeTail
import com.sadurni.attacca.dsp.DspEngine
import com.sadurni.attacca.dsp.SkipTail
import com.sadurni.attacca.dsp.dbToLinear
import com.sadurni.attacca.library.MediaItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Crossfade on a skip by hand: the next seconds of the playing track, from where it is heard, are
 * left in [DspEngine.skipTail] right before the skip, so the crossfade stage blends them into the
 * track skipped to. While a track plays they are kept decoded a little ahead ([AheadDecoder]), so a
 * skip finds them at once; decoding them only when asked took a second or more on some files and
 * phones, longer than a skip can wait. That stays as the fallback.
 */
@OptIn(UnstableApi::class)
class SkipCrossfade(
    private val player: ExoPlayer,
    private val engine: DspEngine,
    private val scope: CoroutineScope,
    /** Length of the blend; 0 = off. */
    val lengthMs: () -> Int,
) {
    private var ahead: AheadDecoder? = null
    private var aheadPeriod: Any? = null

    init {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching { keepAhead() }.onFailure { Log.w(TAG, "decoding ahead failed", it) }
            }
        }
    }

    private class OwnTail(val period: Any?, val lengthMs: Int, val gain: Float, val decoder: AheadDecoder?)

    /** A skip blend to play on its own track when the skip happens. Main thread. */
    private var ownTail: OwnTail? = null

    /** How long a new output track takes to sound, as measured on the last ones (µs). */
    private var ownLatencyUs = OWN_TRACK_LATENCY_US

    /**
     * Plays on, on a track of its own, what this track still had waiting in the output (effects
     * and all), continued from what was decoded ahead when the blend is longer, fading out along
     * the blend; it starts with a [crossMs] fade-in, lined up with the music the player will be
     * playing when it sounds. Returns in how many milliseconds it sounds (when the player should
     * fade over to it), or null when there is no such blend. Main thread.
     */
    fun startOwnTail(crossMs: Int): Long? {
        val own = ownTail ?: return null
        ownTail = null
        val heard = engine.heard?.takeIf { it.period == own.period } ?: return null
        val now = System.nanoTime()
        val latencyUs = ownLatencyUs
        // The music the player will be playing when the new track sounds.
        val startUs = heard.rendererUsAt(now) + latencyUs
        val piece = engine.tap.since(startUs) ?: return null.also { Log.d(TAG, "skip blend: nothing kept from the output") }
        val ch = piece.channels
        val rate = piece.sampleRate
        val wanted = (own.lengthMs.toLong() * rate / 1000).toInt()
        val out = FloatArray(wanted * ch)
        var total = minOf(piece.frames, wanted)
        System.arraycopy(piece.samples, 0, out, 0, total * ch)
        val processed = total
        if (total < wanted && own.decoder != null) {
            // The rest from the decoded file, with its gain (the effects only in the part above).
            val join = minOf(JOIN_FRAMES, total)
            val fileUs = heard.fileUsAt(now) + latencyUs + (total - join) * 1_000_000L / rate
            val raw = own.decoder.tail(fileUs, (wanted - total + join) * 1_000_000L / rate)
            if (raw != null && raw.channels == ch && raw.sampleRate == rate) {
                val from = raw.clipEndFrame
                val n = minOf(raw.frames - from, wanted - (total - join))
                val base = total - join
                for (f in 0 until n) {
                    val w = if (f < join) f.toFloat() / join else 1f
                    for (c in 0 until ch) {
                        val i = (base + f) * ch + c
                        val r = raw.samples[(from + f) * ch + c] * own.gain
                        out[i] = if (f < join) out[i] * (1 - w) + r * w else r
                    }
                }
                total = base + n
            }
        }
        if (total <= rate / 10) return null
        // The blend's fade-out, after the crossover in and down to silence at the very end.
        val rampIn = rate * crossMs / 1000
        val rampOut = maxOf(1, rate / 100)
        for (f in 0 until total) {
            var g = CrossfadeShape.SKIP.outGain(f.toDouble() / wanted).toFloat()
            if (f < rampIn) g *= f.toFloat() / rampIn
            val left = total - f
            if (left < rampOut) g *= left.toFloat() / rampOut
            for (c in 0 until ch) out[f * ch + c] *= g
        }
        val played = TailTrack.play(out, total, ch, rate) { measured ->
            // Kept close to what this output really takes (it differs between phones and outputs).
            if (measured in 0..MAX_LATENCY_US) ownLatencyUs = (ownLatencyUs * 2 + measured) / 3
            Log.d(TAG, "skip blend: its track sounded ${measured / 1000} ms after starting (expected ${latencyUs / 1000} ms)")
        }
        Log.d(TAG, "skip blend on its own track: ${total * 1000L / rate} ms (${processed * 1000L / rate} ms as it was going out), ${if (played) "playing" else "failed"}")
        // The player's fade starts a little earlier: a volume change takes the output's own latency to be heard.
        return if (played) maxOf(0L, (latencyUs - OUTPUT_LATENCY_US) / 1000) else null
    }

    /** The file instant heard now, µs; the player's own position is only updated now and then. */
    private fun heardFileUs(period: Any?, clipStartMs: Long): Long {
        val heard = engine.heard
        return if (heard != null && heard.period == period) heard.fileUsAt(System.nanoTime())
        else (clipStartMs + player.currentPosition) * 1000
    }

    /** Keeps the next seconds of the playing track decoded, from where it is heard. Main thread. */
    private suspend fun keepAhead() {
        val item = player.currentMediaItem
        val length = lengthMs()
        if (item == null || length <= 0 || item.mediaId.contains("://")) {
            closeAhead()
            return
        }
        if (!player.isPlaying) return
        val period = currentPeriod()
        val heardUs = heardFileUs(period, MediaItems.clipStartMs(item) ?: 0L)
        var decoder = ahead
        if (decoder == null || aheadPeriod != period || decoder.file.path != item.mediaId || !decoder.covers(heardUs, RESTART_SLACK_US)) {
            closeAhead()
            val fresh = AheadDecoder(File(item.mediaId))
            ahead = fresh
            aheadPeriod = period
            if (!withContext(Background.ahead) { fresh.start(heardUs) }) {
                // Not decodable here: leave it (the fallback decodes on demand); try again on the next track.
                return
            }
            decoder = fresh
        }
        val until = heardUs + (length + MARGIN_MS + AHEAD_MS) * 1000L
        withContext(Background.ahead) {
            decoder.decodeUntil(until) { ahead !== decoder }
            decoder.dropBefore(heardUs - KEEP_BEHIND_US)
        }
    }

    private fun closeAhead() {
        val old = ahead ?: return
        ahead = null
        aheadPeriod = null
        // On its own thread, after any decoding it is doing (which stops, as it is no longer the one).
        scope.launch(Background.ahead) { old.release() }
    }
    /**
     * Prepares the blend for a skip that happens [dipMs] after [onReady] is called. [onReady]
     * gets whether there is a blend; it is always called, on the main thread, unless [cancelled].
     */
    fun prepare(dipMs: Int, cancelled: () -> Boolean, onReady: (Boolean) -> Unit) {
        val item = player.currentMediaItem
        val length = lengthMs()
        if (item == null || length <= 0 || item.mediaId.contains("://")) {
            onReady(false)
            return
        }
        val file = File(item.mediaId)
        val clipStart = MediaItems.clipStartMs(item) ?: 0L
        val period = currentPeriod()
        // The file instant heard now; the player's own position is only updated now and then.
        fun heardFileMs(): Long {
            val heard = engine.heard
            return if (heard != null && heard.period == period) heard.fileUsAt(System.nanoTime()) / 1000
            else clipStart + player.currentPosition
        }
        val fileMsAtRequest = heardFileMs()
        Log.d(TAG, "skip asked at ${fileMsAtRequest} ms of the file (player says ${clipStart + player.currentPosition} ms)")
        val index = player.currentMediaItemIndex
        val gain = dbToLinear(engine.currentGainDb())
        val started = System.nanoTime()
        // What is still waiting in the output, as it would sound, plays on on its own track (see
        // startOwnTail); the track skipped to only fades in. The player restarts its output on a
        // skip, so blending inside it left a pause in the middle of the blend.
        val heard = engine.heard
        val tapRate = engine.tap.sampleRate
        if (heard != null && heard.period == period && heard.speed == 1f && tapRate > 0) {
            val frames = (length.toLong() * tapRate / 1000).toInt()
            val silent = CrossfadeTail(FloatArray(frames * engine.tap.channels), frames, engine.tap.channels, tapRate, 0)
            ownTail = OwnTail(period, length, gain, ahead?.takeIf { aheadPeriod == period })
            engine.skipTail = SkipTail(silent, 0, 1f, CrossfadeShape.SKIP, frames = frames)
            Log.d(TAG, "skip blend on its own track prepared")
            onReady(true)
            return
        }
        // Already decoded ahead: the blend is ready now.
        val decoder = ahead
        if (decoder != null && aheadPeriod == period) {
            val tail = decoder.tail((fileMsAtRequest + dipMs) * 1000, length * 1000L)
            if (tail != null) {
                val wanted = (length.toLong() * tail.sampleRate / 1000).toInt()
                val frames = minOf(tail.frames - tail.clipEndFrame, wanted)
                if (frames >= wanted * 9 / 10 || (decoder.finished && frames > tail.sampleRate / 10)) {
                    engine.skipTail = SkipTail(tail, tail.clipEndFrame, gain, CrossfadeShape.SKIP, frames = frames)
                    Log.d(TAG, "skip tail decoded ahead, ${frames * 1000L / tail.sampleRate} ms")
                    onReady(true)
                    return
                }
            }
            Log.d(TAG, "skip tail not decoded ahead yet (decoded up to ${decoder.endUs / 1000} ms)")
        }
        scope.launch {
            val tail = withTimeoutOrNull(MAX_WAIT_MS) {
                withContext(Background.urgent) {
                    val t0 = System.nanoTime()
                    TailDecoder.decode(file, fileMsAtRequest * 1000, (length + MARGIN_MS) * 1000L, leadUs = SKIP_LEAD_US).also {
                        Log.d(TAG, "skip tail decoded in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                }
            }
            if (cancelled()) return@launch
            if (tail == null || player.currentMediaItemIndex != index || !player.isPlaying) {
                Log.d(TAG, "no skip blend: tail ${if (tail == null) "missing" else "ok"} after ${(System.nanoTime() - started) / 1_000_000} ms, same track ${player.currentMediaItemIndex == index}, playing ${player.isPlaying}")
                onReady(false)
                return@launch
            }
            // Heard meanwhile, plus the dip before the skip: the blend starts there.
            val elapsedMs = heardFileMs() - fileMsAtRequest + dipMs
            val startFrame = tail.clipEndFrame + (elapsedMs * tail.sampleRate / 1000).toInt()
            val frames = minOf(tail.frames - startFrame, (length.toLong() * tail.sampleRate / 1000).toInt())
            if (frames <= tail.sampleRate / 10) {
                Log.d(TAG, "no skip blend: $frames frames left (tail ${tail.frames}, from $startFrame)")
                onReady(false)
                return@launch
            }
            engine.skipTail = SkipTail(tail, startFrame, gain, CrossfadeShape.SKIP, frames = frames)
            Log.d(TAG, "skip tail ready in ${(System.nanoTime() - started) / 1_000_000} ms, ${frames * 1000L / tail.sampleRate} ms from $elapsedMs ms on")
            onReady(true)
        }
    }

    private fun currentPeriod(): Any? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        return timeline.getUidOfPeriod(window.firstPeriodIndex)
    }

    private companion object {
        const val TAG = "Crossfade"
        const val MAX_WAIT_MS = 700L
        const val TICK_MS = 500L

        /** How long a new output track takes to sound, before it is measured. */
        const val OWN_TRACK_LATENCY_US = 60_000L

        /** A measure beyond this is not believed. */
        const val MAX_LATENCY_US = 400_000L

        /** About how long a volume change of the player takes to be heard. */
        const val OUTPUT_LATENCY_US = 20_000L

        /** Where the output's audio and the decoded file's are joined: a short crossfade. */
        const val JOIN_FRAMES = 882


        /** Decoded beyond the blend's length, so the next ticks find it already there. */
        const val AHEAD_MS = 2_000

        /** Kept before the heard instant (a skip asked a moment ago). */
        const val KEEP_BEHIND_US = 500_000L

        /** How far the heard instant may be past the decoded part before decoding starts again there (a seek). */
        const val RESTART_SLACK_US = 1_500_000L
        const val MARGIN_MS = 1_200

        /** Decoded before the skip point only to let the decoder settle (nothing is matched there). */
        const val SKIP_LEAD_US = 300_000L
    }
}
