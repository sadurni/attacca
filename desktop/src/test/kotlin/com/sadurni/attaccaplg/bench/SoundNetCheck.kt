package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.SoundNet
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Development: Attacca's own sound network against the original model's outputs for the same
 * audio (build/yamnet/ref.bin from the extraction script), with the 32-bit and the 16-bit weights,
 * and how long it takes. Run with `gradlew soundNetCheck`.
 */
fun main() {
    val dir = File("build/yamnet")
    val ref = DataInputStream(File(dir, "ref.bin").inputStream().buffered())
    fun block(): Array<FloatArray> {
        val head = ByteBuffer.wrap(ByteArray(8).also(ref::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        val rows = head.int
        val cols = head.int
        val data = ByteBuffer.wrap(ByteArray(rows * cols * 4).also(ref::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        return Array(rows) { FloatArray(cols) { data.float } }
    }
    val wave = block().flatMap { it.asIterable() }.toFloatArray()
    val logMel = block()
    val embeddings = block()
    val scores = block()
    for (name in listOf("yamnet-f32.bin", "yamnet-f16.bin")) {
        val net = File(dir, name).inputStream().use { SoundNet(it) }
        val mel = net.logMel(wave)!!
        var melErr = 0f
        for (f in logMel.indices) for (b in 0 until 64) melErr = maxOf(melErr, abs(mel[f * 64 + b] - logMel[f][b]))
        repeat(3) { net.run(wave) } // warm up the compiler
        val started = System.nanoTime()
        val result = net.run(wave)!!
        val ms = (System.nanoTime() - started) / 1_000_000
        var cos = 0.0
        var embErr = 0f
        for (p in embeddings.indices) {
            val a = embeddings[p]
            val b = result.embeddings[p]
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; embErr = maxOf(embErr, abs(a[i] - b[i])) }
            cos += dot / sqrt(na * nb)
        }
        var scoreErr = 0f
        for (p in scores.indices) for (c in scores[p].indices) scoreErr = maxOf(scoreErr, abs(scores[p][c] - result.scores[p][c]))
        println("$name: ${result.embeddings.size} patches (reference ${embeddings.size}) · log-mel max error %.5f · embedding cosine %.6f, max error %.5f · scores max error %.5f · %d ms for 10 s"
            .format(melErr, cos / embeddings.size, embErr, scoreErr, ms))
    }
}
