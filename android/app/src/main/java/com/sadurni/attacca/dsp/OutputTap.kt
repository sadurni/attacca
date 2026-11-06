package com.sadurni.attacca.dsp

/**
 * The last seconds of processed audio, as it went to the audio output, with the player's time of
 * each piece: what is still waiting in the output when the user skips, effects and all, can be
 * taken from here and played on, on its own track, while the player moves to the next one.
 *
 * Written on the audio thread, read on any thread.
 */
class OutputTap(private val seconds: Int = 6) {

    /** Processed audio from a moment on: interleaved floats in the output's format. */
    class Piece(val samples: FloatArray, val frames: Int, val channels: Int, val sampleRate: Int)

    private val lock = Any()
    private var ring = FloatArray(0)
    private var capacity = 0
    @Volatile
    var channels = 0
        private set

    @Volatile
    var sampleRate = 0
        private set

    /** Frames written since the last reset. */
    private var written = 0L

    // Where pieces of the player's time start: frame written then, and that time (µs).
    private val anchorFrame = LongArray(ANCHORS)
    private val anchorUs = LongArray(ANCHORS)
    private var anchors = 0
    private var anchorPos = 0

    fun configure(sampleRate: Int, channels: Int) = synchronized(lock) {
        if (sampleRate != this.sampleRate || channels != this.channels) {
            this.sampleRate = sampleRate
            this.channels = channels
            capacity = sampleRate * seconds
            ring = FloatArray(capacity * channels)
        }
        clearLocked()
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        written = 0
        anchors = 0
        anchorPos = 0
    }

    /** Adds [frames] of [buffer], whose first frame the player times at [timeUs] (unset: carry on). */
    fun push(buffer: FloatArray, frames: Int, timeUs: Long) = synchronized(lock) {
        if (capacity == 0 || frames <= 0) return@synchronized
        if (timeUs != androidx.media3.common.C.TIME_UNSET && timeUs != lastAnchorUs()) {
            anchorFrame[anchorPos] = written
            anchorUs[anchorPos] = timeUs
            anchorPos = (anchorPos + 1) % ANCHORS
            if (anchors < ANCHORS) anchors++
        }
        var from = 0
        var at = (written % capacity).toInt()
        var left = frames
        while (left > 0) {
            val n = minOf(left, capacity - at)
            System.arraycopy(buffer, from * channels, ring, at * channels, n * channels)
            from += n
            left -= n
            at = 0
        }
        written += frames
    }

    private fun lastAnchorUs(): Long = if (anchors == 0) Long.MIN_VALUE else anchorUs[(anchorPos - 1 + ANCHORS) % ANCHORS]

    /**
     * What was written from the player's time [timeUs] up to the newest frame; null when that
     * moment is not kept (too old, not written yet, or no times known).
     */
    fun since(timeUs: Long): Piece? = synchronized(lock) {
        if (anchors == 0 || sampleRate == 0) return@synchronized null
        // The latest anchor at or before the time wanted.
        var frame = -1L
        for (i in 0 until anchors) {
            val k = (anchorPos - 1 - i + ANCHORS * 2) % ANCHORS
            if (anchorUs[k] <= timeUs) {
                frame = anchorFrame[k] + (timeUs - anchorUs[k]) * sampleRate / 1_000_000
                break
            }
        }
        if (frame < 0 || frame >= written || frame < written - capacity) return@synchronized null
        val frames = (written - frame).toInt()
        val out = FloatArray(frames * channels)
        var at = (frame % capacity).toInt()
        var to = 0
        var left = frames
        while (left > 0) {
            val n = minOf(left, capacity - at)
            System.arraycopy(ring, at * channels, out, to * channels, n * channels)
            to += n
            left -= n
            at = 0
        }
        Piece(out, frames, channels, sampleRate)
    }

    private companion object {
        const val ANCHORS = 1024
    }
}
