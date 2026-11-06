package com.sadurni.attacca.chain

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sound-recognition network (YAMNet: MobileNet v1 on log-mel patches, Apache 2.0) run by
 * Attacca itself, from its weights only: what a stretch of music sounds like as 1024 numbers (its
 * embedding, close for music that sounds alike) and the scores of 521 sound classes. Pure Kotlin,
 * so the phone and the PC give the same values.
 *
 * Input: mono audio at [SAMPLE_RATE] (see [resample]). Frames of 25 ms every 10 ms, 64 mel bands,
 * patches of 0.96 s every 0.48 s; one embedding per patch.
 */
class SoundNet(weights: InputStream) {

    private val tensors = HashMap<String, Pair<IntArray, FloatArray>>()

    init {
        val data = DataInputStream(weights.buffered())
        val magic = ByteArray(4)
        data.readFully(magic)
        require(String(magic) == "YAMN") { "not a sound network file" }
        val header = ByteBuffer.wrap(ByteArray(8).also(data::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        header.int // version
        val count = header.int
        repeat(count) {
            val nameLength = le(data, 2).short.toInt() and 0xffff
            val name = String(ByteArray(nameLength).also(data::readFully))
            val dims = IntArray(data.readUnsignedByte())
            val dimBytes = le(data, dims.size * 4)
            for (d in dims.indices) dims[d] = dimBytes.int
            val half = data.readUnsignedByte() == 1
            val size = dims.fold(1) { a, b -> a * b }
            val raw = le(data, size * if (half) 2 else 4)
            val values = FloatArray(size) { if (half) halfToFloat(raw.short) else raw.float }
            tensors[name] = dims to values
        }
    }

    private val window = tensor("window")
    private val mel = tensor("mel") // [64, 257]
    private val melBias = tensor("mel_bias")
    private val fft = Fft(FFT_SIZE)

    /** The layers in order: kind (conv/dw), stride, weights, bias, output channels. */
    private class Layer(val depthwise: Boolean, val stride: Int, val weights: FloatArray, val bias: FloatArray, val inChannels: Int, val outChannels: Int)

    private val layers: List<Layer> = run {
        val strides = intArrayOf(2, 1, 1, 2, 1, 1, 1, 2, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 1, 1, 1)
        var channels = 1
        (1..27).map { n ->
            val depthwise = tensors.containsKey("%02d_dw_w".format(n))
            val prefix = "%02d_%s".format(n, if (depthwise) "dw" else "conv")
            val (dims, w) = tensors.getValue("${prefix}_w")
            val out = if (depthwise) dims[3] else dims[0]
            Layer(depthwise, strides[n - 1], w, tensor("${prefix}_b"), channels, out).also { channels = out }
        }
    }
    private val dense = tensor("dense_w") // [521, 1024]
    private val denseBias = tensor("dense_b")

    private fun tensor(name: String): FloatArray = tensors.getValue(name).second

    /** What a stretch sounds like: an embedding and class scores for each patch. */
    class Result(val embeddings: Array<FloatArray>, val scores: Array<FloatArray>) {
        /** The mean embedding over the patches (what the stretch sounds like as a whole). */
        fun meanEmbedding(): FloatArray = mean(embeddings)

        fun meanScores(): FloatArray = mean(scores)

        private fun mean(rows: Array<FloatArray>): FloatArray {
            val m = FloatArray(rows.firstOrNull()?.size ?: 0)
            for (r in rows) for (i in m.indices) m[i] += r[i]
            for (i in m.indices) m[i] /= rows.size
            return m
        }
    }

    /**
     * Runs [wave] (mono, [SAMPLE_RATE]): every patch, or [maxPatches] spread evenly over it. Null
     * when it is shorter than one patch or cancelled.
     */
    fun run(wave: FloatArray, maxPatches: Int = Int.MAX_VALUE, isCancelled: () -> Boolean = { false }): Result? {
        val logMel = logMel(wave) ?: return null
        val frames = logMel.size / MEL_BANDS
        val patches = 1 + (frames - PATCH_FRAMES) / PATCH_HOP
        val chosen = if (patches <= maxPatches) (0 until patches).toList()
        else if (maxPatches == 1) listOf(patches / 2)
        else (0 until maxPatches).map { Math.round(it * (patches - 1).toDouble() / (maxPatches - 1)).toInt() }
        val embeddings = ArrayList<FloatArray>(chosen.size)
        val scores = ArrayList<FloatArray>(chosen.size)
        for (p in chosen) {
            if (isCancelled()) return null
            val input = logMel.copyOfRange(p * PATCH_HOP * MEL_BANDS, (p * PATCH_HOP + PATCH_FRAMES) * MEL_BANDS)
            val embedding = network(input)
            embeddings.add(embedding)
            scores.add(classify(embedding))
        }
        return Result(embeddings.toTypedArray(), scores.toTypedArray())
    }

    /** Log-mel frames ([frames] × 64, row by row), the audio padded at its end to whole patches. */
    fun logMel(wave: FloatArray): FloatArray? {
        if (wave.size < FRAME) return null
        val rawFrames = 1 + (wave.size - FRAME) / HOP
        val patches = 1 + ceil((rawFrames - PATCH_FRAMES).coerceAtLeast(0) / PATCH_HOP.toDouble()).toInt()
        val frames = PATCH_FRAMES + (patches - 1) * PATCH_HOP
        val out = FloatArray(frames * MEL_BANDS)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val magnitude = FloatArray(BINS)
        for (f in 0 until frames) {
            val start = f * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = if (i < FRAME && start + i < wave.size) wave[start + i] * window[i] else 0f
                im[i] = 0f
            }
            fft.transform(re, im)
            for (k in 0 until BINS) magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            for (b in 0 until MEL_BANDS) {
                var s = melBias[b]
                val row = b * BINS
                for (k in 0 until BINS) s += mel[row + k] * magnitude[k]
                out[f * MEL_BANDS + b] = ln(s)
            }
        }
        return out
    }

    /** One patch (96 × 64 log-mel) through the network: its 1024-number embedding. */
    private fun network(patch: FloatArray): FloatArray {
        var h = PATCH_FRAMES
        var w = MEL_BANDS
        var x = patch // [h, w, channels]
        for (layer in layers) {
            val (y, oh, ow) = if (layer.depthwise) depthwise(x, h, w, layer) else convolution(x, h, w, layer)
            x = y
            h = oh
            w = ow
        }
        // Global average over the remaining grid.
        val channels = layers.last().outChannels
        val pooled = FloatArray(channels)
        val cells = h * w
        for (c in 0 until cells) for (k in 0 until channels) pooled[k] += x[c * channels + k]
        for (k in 0 until channels) pooled[k] /= cells
        return pooled
    }

    /** The scores (0 … 1) of the 521 sound classes for an [embedding] (one patch's, or a mean of several). */
    fun classify(embedding: FloatArray): FloatArray {
        val classes = denseBias.size
        val n = embedding.size
        return FloatArray(classes) { c ->
            var s = denseBias[c]
            val row = c * n
            for (i in 0 until n) s += dense[row + i] * embedding[i]
            (1.0 / (1.0 + kotlin.math.exp(-s.toDouble()))).toFloat()
        }
    }

    /** Output size and the padding before, as TensorFlow's "same": the extra row/column goes after. */
    private fun same(size: Int, stride: Int): Pair<Int, Int> {
        val out = (size + stride - 1) / stride
        val total = maxOf((out - 1) * stride + 3 - size, 0)
        return out to total / 2
    }

    private fun convolution(x: FloatArray, h: Int, w: Int, layer: Layer): Triple<FloatArray, Int, Int> {
        val cin = layer.inChannels
        val cout = layer.outChannels
        val wt = layer.weights
        val b = layer.bias
        if (wt.size == cout * cin) {
            // 1×1: a matrix product. Four pixels against four outputs at a time, so each value
            // read from memory is used four times.
            val pixels = h * w
            val y = FloatArray(pixels * cout)
            var p = 0
            while (p < pixels) {
                val pn = minOf(4, pixels - p)
                val i0 = p * cin
                val i1 = (p + minOf(1, pn - 1)) * cin
                val i2 = (p + minOf(2, pn - 1)) * cin
                val i3 = (p + minOf(3, pn - 1)) * cin
                var o = 0
                while (o < cout) {
                    val on = minOf(4, cout - o)
                    val w0 = o * cin
                    val w1 = (o + minOf(1, on - 1)) * cin
                    val w2 = (o + minOf(2, on - 1)) * cin
                    val w3 = (o + minOf(3, on - 1)) * cin
                    var s00 = 0f; var s01 = 0f; var s02 = 0f; var s03 = 0f
                    var s10 = 0f; var s11 = 0f; var s12 = 0f; var s13 = 0f
                    var s20 = 0f; var s21 = 0f; var s22 = 0f; var s23 = 0f
                    var s30 = 0f; var s31 = 0f; var s32 = 0f; var s33 = 0f
                    for (i in 0 until cin) {
                        val a0 = x[i0 + i]; val a1 = x[i1 + i]; val a2 = x[i2 + i]; val a3 = x[i3 + i]
                        val b0 = wt[w0 + i]; val b1 = wt[w1 + i]; val b2 = wt[w2 + i]; val b3 = wt[w3 + i]
                        s00 += a0 * b0; s01 += a0 * b1; s02 += a0 * b2; s03 += a0 * b3
                        s10 += a1 * b0; s11 += a1 * b1; s12 += a1 * b2; s13 += a1 * b3
                        s20 += a2 * b0; s21 += a2 * b1; s22 += a2 * b2; s23 += a2 * b3
                        s30 += a3 * b0; s31 += a3 * b1; s32 += a3 * b2; s33 += a3 * b3
                    }
                    // Stored without an array per block (this runs millions of times per track).
                    put(y, p * cout + o, on, b, o, s00, s01, s02, s03)
                    if (pn > 1) put(y, (p + 1) * cout + o, on, b, o, s10, s11, s12, s13)
                    if (pn > 2) put(y, (p + 2) * cout + o, on, b, o, s20, s21, s22, s23)
                    if (pn > 3) put(y, (p + 3) * cout + o, on, b, o, s30, s31, s32, s33)
                    o += 4
                }
                p += 4
            }
            return Triple(y, h, w)
        }
        // 3×3 (the first layer).
        val (oh, padH) = same(h, layer.stride)
        val (ow, padW) = same(w, layer.stride)
        val y = FloatArray(oh * ow * cout)
        for (r in 0 until oh) for (c in 0 until ow) for (o in 0 until cout) {
            var s = b[o]
            for (kh in 0 until 3) {
                val ir = r * layer.stride + kh - padH
                if (ir < 0 || ir >= h) continue
                for (kw in 0 until 3) {
                    val ic = c * layer.stride + kw - padW
                    if (ic < 0 || ic >= w) continue
                    for (i in 0 until cin) s += x[(ir * w + ic) * cin + i] * wt[((o * 3 + kh) * 3 + kw) * cin + i]
                }
            }
            y[(r * ow + c) * cout + o] = if (s > 0f) s else 0f
        }
        return Triple(y, oh, ow)
    }

    /** Up to four outputs of one pixel, with their biases, through ReLU. */
    private fun put(y: FloatArray, at: Int, count: Int, b: FloatArray, o: Int, s0: Float, s1: Float, s2: Float, s3: Float) {
        y[at] = relu(s0 + b[o])
        if (count > 1) y[at + 1] = relu(s1 + b[o + 1])
        if (count > 2) y[at + 2] = relu(s2 + b[o + 2])
        if (count > 3) y[at + 3] = relu(s3 + b[o + 3])
    }

    private fun relu(s: Float): Float = if (s > 0f) s else 0f

    private fun depthwise(x: FloatArray, h: Int, w: Int, layer: Layer): Triple<FloatArray, Int, Int> {
        val ch = layer.outChannels
        val wt = layer.weights // [1, 3, 3, ch]
        val b = layer.bias
        val (oh, padH) = same(h, layer.stride)
        val (ow, padW) = same(w, layer.stride)
        val y = FloatArray(oh * ow * ch)
        for (r in 0 until oh) for (c in 0 until ow) {
            val outBase = (r * ow + c) * ch
            for (k in 0 until ch) y[outBase + k] = b[k]
            for (kh in 0 until 3) {
                val ir = r * layer.stride + kh - padH
                if (ir < 0 || ir >= h) continue
                for (kw in 0 until 3) {
                    val ic = c * layer.stride + kw - padW
                    if (ic < 0 || ic >= w) continue
                    val inBase = (ir * w + ic) * ch
                    val wBase = (kh * 3 + kw) * ch
                    for (k in 0 until ch) y[outBase + k] += x[inBase + k] * wt[wBase + k]
                }
            }
            for (k in 0 until ch) if (y[outBase + k] < 0f) y[outBase + k] = 0f
        }
        return Triple(y, oh, ow)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val EMBEDDING_SIZE = 1024

        /** Seconds of sound one patch of the network sees. */
        const val PATCH_SECONDS = ((96 - 1) * 160 + 400) / 16_000.0
        const val CLASSES = 521
        private const val FRAME = 400
        private const val HOP = 160
        private const val FFT_SIZE = 512
        private const val BINS = FFT_SIZE / 2 + 1
        private const val MEL_BANDS = 64
        private const val PATCH_FRAMES = 96
        private const val PATCH_HOP = 48

        private fun le(data: DataInputStream, bytes: Int): ByteBuffer =
            ByteBuffer.wrap(ByteArray(bytes).also(data::readFully)).order(ByteOrder.LITTLE_ENDIAN)

        fun halfToFloat(bits: Short): Float {
            val h = bits.toInt() and 0xffff
            val sign = (h ushr 15) shl 31
            val exp = (h ushr 10) and 0x1f
            val mant = h and 0x3ff
            return when (exp) {
                0 -> if (mant == 0) Float.fromBits(sign) else Float.fromBits(sign or ((mant.toFloat() / 1024f * Math.pow(2.0, -14.0).toFloat()).toRawBits() and 0x7fffffff))
                31 -> Float.fromBits(sign or 0x7f800000 or (mant shl 13))
                else -> Float.fromBits(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
            }
        }

        /**
         * Mono [samples] at [rate] brought to [SAMPLE_RATE] (windowed-sinc low-pass at 0.9 × the
         * new Nyquist frequency, 16 zero crossings each side; the kernel read from a table).
         */
        fun resample(samples: FloatArray, count: Int, rate: Int): FloatArray {
            if (rate == SAMPLE_RATE) return samples.copyOf(count)
            val ratio = SAMPLE_RATE.toDouble() / rate
            val kernel = kernelFor(rate)
            val half = kernel.half
            val steps = KERNEL_STEPS.toDouble()
            val out = FloatArray((count * ratio).toInt())
            for (n in out.indices) {
                val t = n / ratio
                val first = kotlin.math.ceil(t - half).toInt().coerceAtLeast(0)
                val last = kotlin.math.floor(t + half).toInt().coerceAtMost(count - 1)
                var s = 0f
                // Position in the table of the first tap, then one input sample (KERNEL_STEPS) per tap.
                var pos = (first - t + half) * steps
                for (k in first..last) {
                    val i = pos.toInt()
                    val f = (pos - i).toFloat()
                    val table = kernel.values
                    val v = if (i + 1 < table.size) table[i] + (table[i + 1] - table[i]) * f else table[table.size - 1]
                    s += samples[k] * v
                    pos += steps
                }
                out[n] = s
            }
            return out
        }

        private const val ZERO_CROSSINGS = 16
        private const val KERNEL_STEPS = 128

        private class Kernel(val half: Double, val values: FloatArray)

        private val kernels = java.util.concurrent.ConcurrentHashMap<Int, Kernel>()

        /** The low-pass kernel for [rate], over ±half input samples in [KERNEL_STEPS] per sample. */
        private fun kernelFor(rate: Int): Kernel = kernels.getOrPut(rate) {
            val cutoff = 0.9 * minOf(1.0, SAMPLE_RATE.toDouble() / rate)
            val half = ZERO_CROSSINGS / cutoff
            val size = (2 * half * KERNEL_STEPS).toInt() + 2
            Kernel(half, FloatArray(size) { j ->
                val d = j.toDouble() / KERNEL_STEPS - half
                val x = PI * d * cutoff
                val sinc = if (kotlin.math.abs(x) < 1e-9) 1.0 else sin(x) / x
                val win = if (kotlin.math.abs(d) >= half) 0.0 else 0.5 + 0.5 * cos(PI * d / half)
                (cutoff * sinc * win).toFloat()
            })
        }
    }
}
