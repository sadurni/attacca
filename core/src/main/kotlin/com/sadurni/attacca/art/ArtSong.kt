package com.sadurni.attacca.art

import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.Sections
import com.sadurni.attacca.diorama.SongWave
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What Art-tacca knows of a song before it paints it: its key (the palette), how bright, rough and
 * sung it sounds, its pace and its whole shape (where it is quiet or loud, and its peak).
 */
class ArtSong(
    /** The song's own identity: the same song always starts from the same seed. */
    val id: String,
    /** 0–11 major on C, C#…; 12–23 minor; -1 unknown. */
    val key: Int,
    /** How clearly it sits in that key (0 … 1). */
    val keyClarity: Float,
    /** 0 dull … 1 bright. */
    val brightness: Float,
    /** 0 clean … 1 rough (distortion, cymbals). */
    val roughness: Float,
    /** 0 no voice … 1 sung all through. */
    val voice: Float,
    /** Its energy in the collection, 0 … 1. */
    val energy: Float,
    val bpm: Float,
    /** How clearly the beat repeats, 0 … 1. */
    val pulse: Float,
    /** Its loudness over time (null: unknown, the live levels alone lead). */
    val wave: SongWave?,
    /** Its cover's colours ([coverColours]: the average first, then the strongest), or null. */
    val cover: IntArray? = null,
    /** Its cover itself, small (for painting the cover), or null. */
    val image: ArtCover? = null,
    /**
     * The painting's signature: the song's title written small (about 30 pixels a line; the pixels'
     * alpha is the ink), "Art-tacca" smaller under it, or null.
     */
    val titleImage: ArtCover? = null,
    /** How long it plays (ms), for when its shape ([wave]) is not known yet; 0 unknown. */
    val duration: Long = 0L,
) {
    val seed: Long = id.hashCode().toLong() * 2654435761L

    val lengthMs: Long get() = wave?.lengthMs ?: duration

    /** Its loudest stretch's start (ms from the song's start), or -1. */
    val peakMs: Double by lazy { wave?.let { Diorama.Song(id, Diorama.NEUTRAL, it).peakMs } ?: -1.0 }

    /** Its quiet (-1) and loud (+1) stretches. */
    val sections: Sections? by lazy { wave?.let { Sections.of(it) } }

    /** How loud the song is around [ms] (from its start), spread over its own range: 0 … 1. */
    fun shapeAt(ms: Double): Float = wave?.let { it.shape(it.startMs + ms, 6000.0) } ?: 0.5f

    val palette: ArtPalette by lazy { ArtPalette.of(this) }

    companion object {
        /** The key from the notes heard at a song's start and end (chroma shares), and how clear it is. */
        fun keyOf(head: FloatArray?, tail: FloatArray?): Pair<Int, Float> {
            val parts = listOfNotNull(head, tail)
            if (parts.isEmpty()) return -1 to 0f
            val sum = FloatArray(12) { i -> parts.sumOf { it[i].toDouble() }.toFloat() }
            return com.sadurni.attacca.chain.Keys.estimate(sum)
        }

        /**
         * A cover's colours from its [pixels] (ARGB, a small picture is enough): their average
         * first, then up to three strong ones, each the mean of a band of hues that holds much
         * of the picture's colour.
         */
        fun coverColours(pixels: IntArray): IntArray {
            if (pixels.isEmpty()) return IntArray(0)
            var r = 0L
            var g = 0L
            var b = 0L
            val bands = 12
            val weight = FloatArray(bands)
            val sums = Array(bands) { FloatArray(3) }
            for (p in pixels) {
                val pr = (p shr 16) and 0xff
                val pg = (p shr 8) and 0xff
                val pb = p and 0xff
                r += pr
                g += pg
                b += pb
                val hi = maxOf(pr, pg, pb)
                val lo = minOf(pr, pg, pb)
                val chroma = (hi - lo) / 255f
                if (chroma < 0.12f || hi < 40) continue
                val h = hueOf(pr, pg, pb)
                val band = ((h / 360f) * bands).toInt().coerceIn(0, bands - 1)
                val w = chroma * chroma
                weight[band] += w
                sums[band][0] += pr * w
                sums[band][1] += pg * w
                sums[band][2] += pb * w
            }
            val n = pixels.size
            val out = ArrayList<Int>()
            out.add(argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
            val total = weight.sum()
            val order = (0 until bands).sortedByDescending { weight[it] }
            for (i in order) {
                if (out.size >= 4 || total <= 0f || weight[i] < total * 0.12f) break
                out.add(argb((sums[i][0] / weight[i]).toInt(), (sums[i][1] / weight[i]).toInt(), (sums[i][2] / weight[i]).toInt()))
            }
            return out.toIntArray()
        }

        private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        internal fun hueOf(r: Int, g: Int, b: Int): Float {
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            if (hi == lo) return 0f
            val d = (hi - lo).toFloat()
            val h = when (hi) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            return (h + 360f) % 360f
        }

        internal fun satOf(r: Int, g: Int, b: Int): Float {
            val hi = maxOf(r, g, b) / 255f
            val lo = minOf(r, g, b) / 255f
            val l = (hi + lo) / 2f
            return if (hi == lo) 0f else (hi - lo) / (1f - abs(2f * l - 1f)).coerceAtLeast(0.01f)
        }
    }
}

/**
 * The song's colours as pigments (what each lets through, 0 … 1 per channel: they multiply on the
 * paper as watercolour does). The key gives the hue round the circle of fifths; major warms and
 * lightens it, minor cools and deepens it; an unclear key greys it.
 */
class ArtPalette(
    /** The song's own colour and two beside it. */
    val main: FloatArray,
    val second: FloatArray,
    val third: FloatArray,
    /** The colour across from them, kept for the strong moments. */
    val accent: FloatArray,
    /** Pale washes: the sky and the ground. */
    val sky: FloatArray,
    val ground: FloatArray,
    /** The dark of the lines. */
    val ink: FloatArray,
    /** The seal at the end. */
    val seal: FloatArray,
    /** The paper's own tint (white leaning a little towards the cover). */
    val paper: FloatArray = floatArrayOf(0.972f, 0.958f, 0.925f),
) {
    /** The same colours turned [degrees] round the colour wheel (the chord heard), the ink and the seal as they are. */
    fun turned(degrees: Float): ArtPalette {
        if (degrees == 0f) return this
        fun t(c: FloatArray): FloatArray {
            val (h, s, l) = toHsl(c)
            return hsl(h + degrees, s, l)
        }
        return ArtPalette(t(main), t(second), t(third), t(accent), t(sky), t(ground), ink, seal, paper)
    }

    /**
     * For ink: the song's colours become inks — a dark one with a breath of the song's hue, its
     * diluted grey and a sepia — and its own colour stays only as the accent, kept for few moments.
     */
    fun inked(): ArtPalette {
        val (h, s, _) = toHsl(main)
        val dark = hsl(h, 0.18f, 0.13f)
        val grey = hsl(h, 0.08f, 0.42f)
        val sepia = hsl(28f, 0.35f, 0.32f)
        val colour = hsl(h, (s + 0.25f).coerceAtMost(0.9f), 0.45f)
        return ArtPalette(dark, grey, sepia, colour, hsl(h, 0.05f, 0.84f), hsl(h + 30f, 0.07f, 0.78f), floatArrayOf(0.07f, 0.07f, 0.08f), seal,
            floatArrayOf(0.985f, 0.98f, 0.965f))
    }

    companion object {
        /** r, g, b to hue (degrees), saturation and lightness. */
        fun toHsl(c: FloatArray): Triple<Float, Float, Float> {
            val r = (c[0] * 255).toInt().coerceIn(0, 255)
            val g = (c[1] * 255).toInt().coerceIn(0, 255)
            val b = (c[2] * 255).toInt().coerceIn(0, 255)
            val hi = maxOf(c[0], c[1], c[2])
            val lo = minOf(c[0], c[1], c[2])
            return Triple(ArtSong.hueOf(r, g, b), ArtSong.satOf(r, g, b), (hi + lo) / 2f)
        }

        fun of(song: ArtSong): ArtPalette {
            val rnd = java.util.Random(song.seed)
            val minor = song.key >= 12
            val tonic = if (song.key < 0) rnd.nextInt(12) else song.key % 12
            // Round the circle of fifths: neighbouring keys get neighbouring hues.
            val fifths = (tonic * 7) % 12
            var hue = fifths / 12f * 360f + 20f
            if (minor) hue += 200f
            hue = ((hue % 360f) + 360f) % 360f
            val clear = if (song.key < 0) 0.75f else (0.45f + song.keyClarity * 0.8f).coerceIn(0.45f, 1f)
            val sat = (if (minor) 0.55f else 0.7f) * clear
            val light = (if (minor) 0.42f else 0.55f) + 0.12f * song.brightness
            val spread = 22f + 18f * song.roughness
            // The cover's strong colours pull the key's hues towards them (the key still leads).
            val strong = song.cover?.drop(1).orEmpty()
            fun lean(h: Float, k: Int, by: Float): Float {
                val c = strong.getOrNull(k) ?: return h
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val pull = by * ArtSong.satOf(r, g, b).coerceIn(0f, 1f)
                return h + angle(h, ArtSong.hueOf(r, g, b)) * pull
            }
            val coverSat = if (strong.isEmpty()) 0f else 0.12f
            val main = hsl(lean(hue, 0, 0.45f), sat + coverSat, light)
            val second = hsl(lean(hue + spread, 1, 0.55f), sat * 0.9f + coverSat, light + 0.05f)
            val third = hsl(lean(hue - spread, 2, 0.55f), sat * 0.85f + coverSat, light - 0.04f)
            val accent = hsl(hue + 180f, min(1f, sat * 1.25f + 0.1f), 0.5f)
            val sky = hsl(hue + (if (minor) 10f else -150f), 0.45f * clear + 0.15f, 0.8f)
            val ground = hsl(hue + (if (minor) -40f else 40f), 0.42f * clear + 0.15f, 0.68f)
            val ink = hsl(hue + 200f, 0.35f, if (minor) 0.16f else 0.22f)
            val seal = hsl(6f, 0.75f, 0.42f)
            val paper = song.cover?.firstOrNull()?.let { c ->
                val base = floatArrayOf(0.972f, 0.958f, 0.925f)
                val tint = floatArrayOf(((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f, (c and 0xff) / 255f)
                val m = tint.average().toFloat().coerceAtLeast(0.05f)
                // Only the cover's hue, softly: the paper stays light.
                FloatArray(3) { (base[it] * (1f - 0.07f) + base[it] * (tint[it] / m) * 0.07f).coerceIn(0.85f, 1f) }
            } ?: floatArrayOf(0.972f, 0.958f, 0.925f)
            return ArtPalette(main, second, third, accent, sky, ground, ink, seal, paper)
        }

        private fun angle(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** Hue in degrees, saturation and lightness 0 … 1, to r, g, b. */
        fun hsl(h: Float, s: Float, l: Float): FloatArray {
            val hh = (((h % 360f) + 360f) % 360f) / 60f
            val ll = l.coerceIn(0f, 1f)
            val ss = s.coerceIn(0f, 1f)
            val c = (1f - abs(2f * ll - 1f)) * ss
            val x = c * (1f - abs(hh % 2f - 1f))
            val m = ll - c / 2f
            val (r, g, b) = when (hh.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return floatArrayOf(max(0f, r + m), max(0f, g + m), max(0f, b + m))
        }
    }
}
