package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.SoundNet
import com.sadurni.attaccaplg.audio.Decoder
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Development: the song of build/yamnet/ref.bin decoded and resampled here, against the reference wave and embeddings. */
fun main(args: Array<String>) {
    val ref = DataInputStream(File("build/yamnet/ref.bin").inputStream().buffered())
    fun block(): Array<FloatArray> {
        val head = ByteBuffer.wrap(ByteArray(8).also(ref::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        val rows = head.int; val cols = head.int
        val data = ByteBuffer.wrap(ByteArray(rows * cols * 4).also(ref::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        return Array(rows) { FloatArray(cols) { data.float } }
    }
    val refWave = block().flatMap { it.asIterable() }.toFloatArray()
    block()
    val refEmb = block()
    var rate = 0
    var channelCount = 1
    var mono = FloatArray(1 shl 24)
    var count = 0
    Decoder.decode(File(args[0]), { rate = it.sampleRate; channelCount = it.channels }) { s, frames ->
        val ch = channelCount
        for (f in 0 until frames) { var sum = 0f; for (c in 0 until ch) sum += s[f * ch + c]; mono[count + f] = sum / ch }
        count += frames
    }
    println("decoded $count frames at $rate Hz = ${count.toDouble() / rate} s")
    val mid = count / 2
    val x = mono.copyOfRange(mid - 5 * rate, mid + 5 * rate)
    java.io.DataOutputStream(File("build/yamnet/kotlin-mid.f32").outputStream().buffered()).use { o -> val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN); x.forEach { bb.putFloat(it) }; o.write(bb.array()) }
    val t0 = System.nanoTime()
    val w = SoundNet.resample(x, x.size, rate)
    println("resample: ${(System.nanoTime() - t0) / 1_000_000} ms, ${w.size} vs reference ${refWave.size} samples")
    // Best alignment within ±200 samples, then the error.
    var bestLag = 0; var best = -1.0
    for (lag in -8000..8000 step 4) {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in 10000 until 140000) { val a = w[i]; val b = refWave[i + lag]; dot += a * b; na += a * a; nb += b * b }
        val c = dot / sqrt(na * nb); if (c > best) { best = c; bestLag = lag }
    }
    println("wave correlation %.5f at lag %d".format(best, bestLag))
    val net = File("build/yamnet/yamnet-f16.bin").inputStream().use { SoundNet(it) }
    val r = net.run(w)!!
    var cos = 0.0
    for (p in refEmb.indices) {
        val a = refEmb[p]; val b = r.embeddings[p]
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        cos += dot / sqrt(na * nb)
    }
    println("embedding cosine vs reference (decoded and resampled here): %.4f".format(cos / refEmb.size))
}
