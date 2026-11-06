package com.sadurni.attacca.chain

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * The few sound classes of the network that speak of a track's mood (happy, sad, tender, exciting,
 * angry and scary music), scored from a sound profile's embedding ([SoundProfile.embedding]): the
 * network's last layer is a plain weighted sum, so the score of the mean embedding stands for the
 * windows of the whole track. Read from the network's weights file, keeping only those rows (the
 * rest is skipped), so the profiles already measured need no new analysis.
 */
class MoodHead private constructor(private val weights: Array<FloatArray>, private val bias: FloatArray) {

    /** Log-odds of each of [CLASSES] for [embedding], in that order. */
    fun logits(embedding: FloatArray): FloatArray = FloatArray(weights.size) { c ->
        var s = bias[c]
        val row = weights[c]
        for (i in row.indices) s += row[i] * embedding[i]
        s
    }

    companion object {
        /** Happy, sad, tender, exciting, angry and scary music, by their index among the network's classes. */
        val CLASSES = intArrayOf(271, 272, 273, 274, 275, 276)
        const val HAPPY = 0
        const val SAD = 1
        const val TENDER = 2
        const val EXCITING = 3
        const val ANGRY = 4
        const val SCARY = 5

        /** Opens the network's weights file when the head is first needed: set by the app; by default the file among the classes (the desktop). */
        @Volatile
        var source: (() -> InputStream?)? = null

        private var loaded: MoodHead? = null
        private var tried = false

        /** The head from [source], read once; null when there is none (moods then go without it). */
        @Synchronized
        fun shared(): MoodHead? {
            if (!tried) {
                tried = true
                val open = source ?: { MoodHead::class.java.getResourceAsStream("/soundnet.bin") }
                loaded = runCatching { open()?.use(::read) }.getOrNull()
            }
            return loaded
        }

        /** Reads the last layer's rows of [CLASSES] from a weights file in the network's format. */
        fun read(input: InputStream): MoodHead {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4).also(data::readFully)
            require(String(magic) == "YAMN") { "not a sound network file" }
            val header = le(data, 8)
            header.int
            val count = header.int
            var weights: Array<FloatArray>? = null
            var bias: FloatArray? = null
            repeat(count) {
                val name = String(ByteArray(le(data, 2).short.toInt() and 0xffff).also(data::readFully))
                val dims = IntArray(data.readUnsignedByte())
                val dimBytes = le(data, dims.size * 4)
                for (d in dims.indices) dims[d] = dimBytes.int
                val half = data.readUnsignedByte() == 1
                val size = dims.fold(1) { a, b -> a * b }
                val width = if (half) 2 else 4
                when (name) {
                    "dense_w" -> {
                        // [classes, embedding]
                        val inputs = dims[1]
                        val raw = le(data, size * width)
                        val all = FloatArray(size) { if (half) SoundNet.halfToFloat(raw.short) else raw.float }
                        weights = Array(CLASSES.size) { c -> all.copyOfRange(CLASSES[c] * inputs, (CLASSES[c] + 1) * inputs) }
                    }
                    "dense_b" -> {
                        val raw = le(data, size * width)
                        val all = FloatArray(size) { if (half) SoundNet.halfToFloat(raw.short) else raw.float }
                        bias = FloatArray(CLASSES.size) { all[CLASSES[it]] }
                    }
                    else -> skip(data, size.toLong() * width)
                }
            }
            return MoodHead(requireNotNull(weights), requireNotNull(bias))
        }

        /** Probability from log-odds. */
        fun probability(logit: Float): Float = (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()

        private fun le(data: DataInputStream, bytes: Int): ByteBuffer =
            ByteBuffer.wrap(ByteArray(bytes).also(data::readFully)).order(ByteOrder.LITTLE_ENDIAN)

        private fun skip(data: DataInputStream, bytes: Long) {
            var left = bytes
            while (left > 0) {
                val skipped = data.skip(left)
                if (skipped <= 0) {
                    data.readByte()
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}
