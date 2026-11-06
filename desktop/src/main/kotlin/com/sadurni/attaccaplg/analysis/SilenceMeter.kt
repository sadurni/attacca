package com.sadurni.attaccaplg.analysis

import com.sadurni.attacca.analysis.TrackAnalysis
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.audio.PcmFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

/**
 * Where the sound of a file starts and ends, as [TrackAnalyzer] (and Attacca) find it: the first
 * and last [TrackAnalysis.WINDOW_MS] window with a sample above [TrackAnalyzer.THRESHOLD_DB] in
 * any channel. Only that: no edges, features, loudness or waveform, so a track keeps what it has.
 * The Java decoders read from the start, so the whole file is decoded, but nothing else is done
 * with it.
 */
object SilenceMeter {

    class Bounds(val durationMs: Long, val audioStartMs: Long, val audioEndMs: Long)

    /** Null when the file cannot be decoded. Blocking. */
    fun measure(file: File, thresholdDb: Float = TrackAnalyzer.THRESHOLD_DB): Bounds? {
        val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
        var channels = 1
        var rate = 0
        var windowFrames = 1
        var frames = 0L // decoded
        var inWindow = 0
        var window = 0L // index of the window being filled
        var windowAudible = false
        var first = -1L
        var last = -1L

        fun closeWindow() {
            if (inWindow == 0) return
            if (windowAudible) {
                if (first < 0) first = window
                last = window
            }
            window++
            inWindow = 0
            windowAudible = false
        }

        val format = Decoder.decode(
            file,
            { f: PcmFormat ->
                // A retry with another decoder starts over.
                channels = f.channels
                rate = f.sampleRate
                windowFrames = maxOf(1, f.sampleRate * TrackAnalysis.WINDOW_MS / 1000)
                frames = 0
                inWindow = 0
                window = 0
                windowAudible = false
                first = -1
                last = -1
            },
        ) { samples, count ->
            var s = 0
            for (f in 0 until count) {
                for (c in 0 until channels) if (abs(samples[s++]) > threshold) windowAudible = true
                if (++inWindow >= windowFrames) closeWindow()
            }
            frames += count
        } ?: return null
        closeWindow()
        if (frames == 0L || rate <= 0) return null
        val durationMs = frames * 1_000_000L / format.sampleRate / 1000
        if (first < 0) return Bounds(durationMs, 0, durationMs)
        val endMs = minOf(durationMs, (last + 1) * TrackAnalysis.WINDOW_MS)
        return Bounds(durationMs, first * TrackAnalysis.WINDOW_MS, endMs)
    }
}
