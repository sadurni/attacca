package com.sadurni.attacca.diorama

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Levels of the sound on its way out, every [BLOCK_MS] ms, with the player's time of each: the
 * whole, the lows (below about 150 Hz) and the highs (above about 3 kHz). The output is ahead of
 * what is heard, so the diorama asks for the levels at the moment heard ([at]).
 *
 * Written on the audio thread, read on any thread.
 */
class LevelMeter(private val capacity: Int = 256) {

    private val lock = Any()
    private val times = LongArray(capacity)
    private val level = FloatArray(capacity)
    private val low = FloatArray(capacity)
    private val high = FloatArray(capacity)
    private var count = 0
    private var pos = 0

    private var rate = 0
    private var lowCoef = 0f
    private var highCoef = 0f
    private var lowState = 0f
    private var highState = 0f
    private var blockFrames = 0
    private var inBlock = 0
    private var sumAll = 0.0
    private var sumLow = 0.0
    private var sumHigh = 0.0
    private var blockStartUs = Long.MIN_VALUE
    private var nextUs = Long.MIN_VALUE
    private var lastGivenUs = Long.MIN_VALUE

    /** Adds [frames] frames of interleaved [samples]; [timeUs]: the first frame's time, or [UNKNOWN] to carry on. */
    fun push(samples: FloatArray, frames: Int, channels: Int, sampleRate: Int, timeUs: Long) {
        if (frames <= 0 || channels <= 0 || sampleRate <= 0) return
        synchronized(lock) {
            if (sampleRate != rate) {
                rate = sampleRate
                lowCoef = (1.0 - exp(-2.0 * PI * 150.0 / sampleRate)).toFloat()
                highCoef = (1.0 - exp(-2.0 * PI * 3000.0 / sampleRate)).toFloat()
                blockFrames = sampleRate * BLOCK_MS / 1000
                inBlock = 0
            }
            // The same time given again (one decoded buffer in several pieces) carries on.
            if (timeUs != UNKNOWN && timeUs != lastGivenUs) {
                nextUs = timeUs
                lastGivenUs = timeUs
            }
            if (nextUs == Long.MIN_VALUE) return
            var t = nextUs
            for (f in 0 until frames) {
                if (inBlock == 0) {
                    blockStartUs = t
                    sumAll = 0.0
                    sumLow = 0.0
                    sumHigh = 0.0
                }
                var x = 0f
                val base = f * channels
                for (c in 0 until channels) x += samples[base + c]
                x /= channels
                lowState += lowCoef * (x - lowState)
                highState += highCoef * (x - highState)
                val hi = x - highState
                sumAll += x * x
                sumLow += lowState * lowState
                sumHigh += hi * hi
                if (++inBlock >= blockFrames) {
                    times[pos] = blockStartUs
                    level[pos] = sqrt(sumAll / inBlock).toFloat()
                    low[pos] = sqrt(sumLow / inBlock).toFloat()
                    high[pos] = sqrt(sumHigh / inBlock).toFloat()
                    pos = (pos + 1) % capacity
                    if (count < capacity) count++
                    inBlock = 0
                }
                t += 1_000_000L / sampleRate
            }
            nextUs = nextUs + frames * 1_000_000L / sampleRate
        }
    }

    fun clear() = synchronized(lock) {
        count = 0
        inBlock = 0
        nextUs = Long.MIN_VALUE
        lastGivenUs = Long.MIN_VALUE
    }

    /** The levels at the player's time [timeUs] (the newest block at or before it), into [out]; false when none is kept. */
    fun at(timeUs: Long, out: FloatArray): Boolean = synchronized(lock) {
        for (i in 0 until count) {
            val k = (pos - 1 - i + capacity * 2) % capacity
            if (times[k] <= timeUs) {
                if (timeUs - times[k] > STALE_US) return@synchronized false
                out[0] = level[k]
                out[1] = low[k]
                out[2] = high[k]
                return@synchronized true
            }
        }
        false
    }

    companion object {
        const val BLOCK_MS = 20
        const val UNKNOWN = Long.MIN_VALUE
        private const val STALE_US = 500_000L
    }
}

/**
 * The meter's levels as the diorama uses them: each scaled to the loudest heard lately (so quiet
 * and loud recordings both move), smoothed, with a beat ("onset") when the lows jump.
 */
class LiveLevels {
    /** 0 … 1. */
    var level = 0f
        private set
    var low = 0f
        private set
    var high = 0f
        private set

    /** When the last strong low beat came (ms, the caller's clock); [NEVER] before any. */
    var onsetAtMs = NEVER
        private set

    private val peaks = floatArrayOf(0.05f, 0.03f, 0.01f)
    private var lowAverage = 0f
    private var lastMs = Long.MIN_VALUE

    /** Takes the raw levels [raw] (level, low, high; null: silence or paused) at [nowMs]. */
    fun update(raw: FloatArray?, nowMs: Long) {
        val dt = if (lastMs == Long.MIN_VALUE) 16f else (nowMs - lastMs).coerceIn(1, 200).toFloat()
        lastMs = nowMs
        val decay = exp(-dt / 6000f)
        fun follow(i: Int, current: Float): Float {
            val x = raw?.get(i) ?: 0f
            peaks[i] = max(peaks[i] * decay, max(x, MIN_PEAKS[i]))
            val target = (x / peaks[i]).coerceIn(0f, 1f)
            // Quick up, slower down.
            val k = if (target > current) 1f - exp(-dt / 35f) else 1f - exp(-dt / 180f)
            return current + (target - current) * k
        }
        level = follow(0, level)
        val previousLow = low
        low = follow(1, low)
        high = follow(2, high)
        lowAverage += (low - lowAverage) * (1f - exp(-dt / 400f))
        if (raw != null && low - lowAverage > 0.18f && low > previousLow && nowMs - onsetAtMs > 280) onsetAtMs = nowMs
    }

    companion object {
        /** Long before any clock's moment, yet safe to subtract from. */
        const val NEVER = Long.MIN_VALUE / 4
        private val MIN_PEAKS = floatArrayOf(0.02f, 0.01f, 0.004f)
    }
}
