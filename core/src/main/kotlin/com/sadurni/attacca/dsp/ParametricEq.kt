package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A band of the parametric EQ. */
data class PeqBand(
    val type: Type = Type.PEAK,
    val freqHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 0.71f,
    val enabled: Boolean = true,
) {
    enum class Type(val code: String) { PEAK("PK"), LOW_SHELF("LSC"), HIGH_SHELF("HSC") }
}

/**
 * Parametric equalizer: up to [MAX_BANDS] peaking or shelving bands, each with its own frequency,
 * gain and Q (RBJ audio EQ cookbook biquads in series). Gains move towards their setting by at
 * most [STEP_DB] per buffer, so dragging a slider never clicks.
 */
class ParametricEq(private val sampleRate: Int, private val channels: Int) {
    private val filters = Array(MAX_BANDS) { Biquad(channels) }
    private var configured: List<PeqBand> = emptyList()
    private val gainsNow = FloatArray(MAX_BANDS)

    /** Whether any band still changes the sound (also while fading out after being switched off). */
    var isActive = false
        private set

    fun process(buffer: FloatArray, frames: Int, bands: List<PeqBand>, on: Boolean) {
        val wanted = bands.take(MAX_BANDS)
        var active = false
        for (i in 0 until MAX_BANDS) {
            val band = wanted.getOrNull(i)
            val target = if (on && band != null && band.enabled) band.gainDb else 0f
            val delta = target - gainsNow[i]
            gainsNow[i] = if (kotlin.math.abs(delta) <= STEP_DB) target else gainsNow[i] + kotlin.math.sign(delta) * STEP_DB
            if (gainsNow[i] != 0f) active = true
        }
        isActive = active
        if (!active) return
        for (i in 0 until MAX_BANDS) {
            val band = wanted.getOrNull(i) ?: configured.getOrNull(i) ?: continue
            val gain = gainsNow[i]
            if (gain == 0f) continue
            val shown = band.copy(gainDb = gain)
            if (configured.getOrNull(i) != shown) {
                set(filters[i], shown)
            }
            val f = filters[i]
            var k = 0
            for (frame in 0 until frames) {
                for (ch in 0 until channels) {
                    buffer[k] = f.process(buffer[k].toDouble(), ch).toFloat()
                    k++
                }
            }
        }
        configured = List(MAX_BANDS) { i -> (wanted.getOrNull(i) ?: configured.getOrNull(i) ?: PeqBand()).copy(gainDb = gainsNow[i]) }
    }

    private fun set(filter: Biquad, band: PeqBand) {
        val freq = band.freqHz.toDouble().coerceIn(10.0, sampleRate * 0.45)
        val q = band.q.toDouble().coerceIn(0.1, 20.0)
        when (band.type) {
            PeqBand.Type.PEAK -> filter.setPeaking(sampleRate, freq, q, band.gainDb.toDouble())
            PeqBand.Type.LOW_SHELF -> filter.setShelf(sampleRate, freq, q, band.gainDb.toDouble(), high = false)
            PeqBand.Type.HIGH_SHELF -> filter.setShelf(sampleRate, freq, q, band.gainDb.toDouble(), high = true)
        }
    }

    fun clear() {
        filters.forEach { it.clear() }
        configured = emptyList()
        gainsNow.fill(0f)
        isActive = false
    }

    companion object {
        const val MAX_BANDS = 10
        const val STEP_DB = 0.5f

        /** The highest level the bands raise any frequency to (dB), for the precut; a rough sum where bands overlap. */
        fun maxBoostDb(bands: List<PeqBand>, sampleRate: Int = 48000): Float {
            var worst = 0.0
            var f = 20.0
            while (f < 20000) {
                var sum = 0.0
                for (b in bands) if (b.enabled) sum += responseDb(b, f, sampleRate)
                if (sum > worst) worst = sum
                f *= 1.05
            }
            return worst.toFloat()
        }

        /** Magnitude response of one band at [f] (dB). */
        fun responseDb(band: PeqBand, f: Double, sampleRate: Int): Double {
            val bq = Biquad(1)
            val freq = band.freqHz.toDouble().coerceIn(10.0, sampleRate * 0.45)
            val q = band.q.toDouble().coerceIn(0.1, 20.0)
            when (band.type) {
                PeqBand.Type.PEAK -> bq.setPeaking(sampleRate, freq, q, band.gainDb.toDouble())
                PeqBand.Type.LOW_SHELF -> bq.setShelf(sampleRate, freq, q, band.gainDb.toDouble(), high = false)
                PeqBand.Type.HIGH_SHELF -> bq.setShelf(sampleRate, freq, q, band.gainDb.toDouble(), high = true)
            }
            return bq.magnitudeDb(f, sampleRate)
        }
    }
}

/**
 * Reads the usual text of a parametric EQ correction (as headphone measurement sites publish
 * them):
 *
 * ```
 * Preamp: -6.4 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
 * Filter 2: ON PK Fc 180 Hz Gain -2.1 dB Q 1.10
 * Filter 3: ON HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
 * ```
 *
 * Other lines are ignored; filters that are OFF are kept switched off.
 */
object PeqText {
    class Parsed(val preampDb: Float?, val bands: List<PeqBand>)

    private val number = "([-+]?[0-9]*[.,]?[0-9]+)"
    private val filter = Regex("""filter\s*\d*\s*:\s*(on|off)\s+(pk|peq|peak|lsc|ls|lowshelf|hsc|hs|highshelf)\s+fc\s+$number\s*hz(?:\s+gain\s+$number\s*db)?(?:\s+q\s+$number)?""", RegexOption.IGNORE_CASE)
    private val preamp = Regex("""preamp\s*:\s*$number\s*db""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Parsed {
        var pre: Float? = null
        val bands = ArrayList<PeqBand>()
        for (line in text.lineSequence()) {
            preamp.find(line)?.let { pre = it.groupValues[1].replace(',', '.').toFloatOrNull() }
            val m = filter.find(line) ?: continue
            val type = when (m.groupValues[2].lowercase()) {
                "lsc", "ls", "lowshelf" -> PeqBand.Type.LOW_SHELF
                "hsc", "hs", "highshelf" -> PeqBand.Type.HIGH_SHELF
                else -> PeqBand.Type.PEAK
            }
            val freq = m.groupValues[3].replace(',', '.').toFloatOrNull() ?: continue
            val gain = m.groupValues[4].replace(',', '.').toFloatOrNull() ?: 0f
            val q = m.groupValues[5].replace(',', '.').toFloatOrNull() ?: 0.71f
            bands.add(PeqBand(type, freq, gain, q, enabled = m.groupValues[1].equals("on", ignoreCase = true)))
            if (bands.size == ParametricEq.MAX_BANDS) break
        }
        return Parsed(pre, bands)
    }

    fun format(preampDb: Float, bands: List<PeqBand>): String = buildString {
        append("Preamp: ").append("%.1f".format(java.util.Locale.ROOT, preampDb)).append(" dB\n")
        bands.forEachIndexed { i, b ->
            append("Filter ${i + 1}: ${if (b.enabled) "ON" else "OFF"} ${b.type.code} Fc ")
            append("%.0f".format(java.util.Locale.ROOT, b.freqHz)).append(" Hz Gain ")
            append("%.1f".format(java.util.Locale.ROOT, b.gainDb)).append(" dB Q ")
            append("%.2f".format(java.util.Locale.ROOT, b.q)).append('\n')
        }
    }
}

/** Shelf with its own Q (RBJ), low or [high]. */
internal fun Biquad.setShelf(sampleRate: Int, freq: Double, q: Double, gainDb: Double, high: Boolean) {
    val a = 10.0.pow(gainDb / 40)
    val w = 2 * PI * freq / sampleRate
    val c = cos(w)
    val alpha = sin(w) / (2 * q)
    val sq = 2 * sqrt(a) * alpha
    if (!high) {
        setCoefficients(
            a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq),
            (a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq,
        )
    } else {
        setCoefficients(
            a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq),
            (a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq,
        )
    }
}
