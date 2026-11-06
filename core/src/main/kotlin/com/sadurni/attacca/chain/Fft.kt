package com.sadurni.attacca.chain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 FFT of a fixed power-of-two size, with its twiddle factors precomputed. */
class Fft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    private val cosTable = FloatArray(size / 2) { cos(-2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(-2.0 * PI * it / size).toFloat() }
    private val bitReversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Transforms ([re], [im]) in place; both must hold [size] values. */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (i in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val j = i + half
                    val tr = wr * re[j] - wi * im[j]
                    val ti = wr * im[j] + wi * re[j]
                    re[j] = re[i] - tr
                    im[j] = im[i] - ti
                    re[i] += tr
                    im[i] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
