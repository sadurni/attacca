package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.SoundNet
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

fun main() {
    for (rate in listOf(44100, 48000)) {
        val x = FloatArray(rate * 2) { (0.5 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        val y = SoundNet.resample(x, x.size, rate)
        val expected = FloatArray(y.size) { (0.5 * sin(2 * PI * 1000.0 * it / 16000)).toFloat() }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in 2000 until y.size - 2000) { dot += y[i] * expected[i]; na += y[i] * y[i]; nb += expected[i] * expected[i] }
        println("$rate → 16000: ${y.size} samples, correlation with the ideal %.5f, rms ratio %.3f".format(dot / sqrt(na * nb), sqrt(na / nb)))
    }
}
