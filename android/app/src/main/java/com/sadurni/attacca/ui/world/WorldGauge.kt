package com.sadurni.attacca.ui.world

import android.content.Context

/**
 * How Attacca World runs on this phone: the frames a second it reaches in each quality, measured
 * while it is shown (without logs), kept to guide the choice of quality (see [WorldQualityAdvice]).
 *
 * The renderer calls [frame] once a frame; after [WARM_UP_S] seconds in the same quality and frame
 * rate, [MEASURE_S] seconds of drawing give one [Measured] (pauses, such as the screen off or another
 * screen over World, are left out). Changing quality or frame rate starts again.
 */
class WorldGauge {
    data class Measured(val level: String, val cap: Int, val fps: Float)

    /** The last measurement, for the screen to take (it then sets it back to null). */
    @Volatile var result: Measured? = null

    /** Not measuring now (World split with O'rama): starts again when back. */
    @Volatile var off: Boolean = false

    private var level = ""
    private var cap = 0
    private var lastNs = 0L
    private var warm = 0.0
    private var spent = 0.0
    private var frames = 0
    private var done = false

    /** One frame drawn in [level] ("basic", "high", "max") with [cap] frames a second asked for. */
    fun frame(nowNs: Long, level: String, cap: Int) {
        if (off) {
            this.level = ""
            return
        }
        if (level != this.level || cap != this.cap) {
            this.level = level
            this.cap = cap
            warm = 0.0; spent = 0.0; frames = 0; done = false; lastNs = 0L
        }
        val last = lastNs
        lastNs = nowNs
        if (done || last == 0L || cap <= 0) return
        val dt = (nowNs - last) / 1e9
        // A pause (not a slow frame): not counted.
        if (dt > 0.25) return
        if (warm < WARM_UP_S) {
            warm += dt
            return
        }
        spent += dt
        frames++
        if (spent >= MEASURE_S) {
            result = Measured(level, cap, (frames / spent).toFloat())
            done = true
        }
    }

    companion object {
        const val WARM_UP_S = 4.0
        const val MEASURE_S = 10.0
    }
}

/**
 * What World's measurements suggest: from maximum to high or from high to basic when the frames
 * fall well short of what is asked, from high to maximum when 60 a second are kept with room to
 * spare. Each suggestion is made once on this phone; the measurements stay for Settings.
 */
object WorldQualityAdvice {
    data class Advice(val from: String, val to: String, val fps: Int, val cap: Int)

    private fun prefs(context: Context) = context.getSharedPreferences("world_gauge", Context.MODE_PRIVATE)

    /** Keeps [m] and says what to suggest, if anything (not again once suggested). */
    fun take(context: Context, m: WorldGauge.Measured): Advice? {
        val p = prefs(context)
        p.edit().putFloat("fps_${m.level}", m.fps).putInt("cap_${m.level}", m.cap).apply()
        val to = when {
            m.level == "max" && m.fps < m.cap * SLOW -> "high"
            m.level == "high" && m.fps < m.cap * SLOW -> "basic"
            m.level == "high" && m.cap >= 60 && m.fps >= m.cap * EASY -> "max"
            else -> return null
        }
        val key = "offered_${m.level}_$to"
        if (p.getBoolean(key, false)) return null
        p.edit().putBoolean(key, true).apply()
        return Advice(m.level, to, kotlin.math.round(m.fps).toInt(), m.cap)
    }

    /** The frames a second measured in each quality ("basic", "high", "max"), with the rate asked for. */
    fun measured(context: Context): List<Triple<String, Int, Int>> {
        val p = prefs(context)
        return listOf("basic", "high", "max").mapNotNull { l ->
            if (!p.contains("fps_$l")) null else Triple(l, kotlin.math.round(p.getFloat("fps_$l", 0f)).toInt(), p.getInt("cap_$l", 0))
        }
    }

    /** Below this share of the frames asked for, a lighter quality is suggested. */
    const val SLOW = 0.8f

    /** From this share of 60 frames on, maximum quality is suggested. */
    const val EASY = 0.97f
}
