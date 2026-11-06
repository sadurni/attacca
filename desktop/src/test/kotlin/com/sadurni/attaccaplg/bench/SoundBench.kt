package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.Cepstrum
import com.sadurni.attacca.chain.Classification
import com.sadurni.attacca.chain.EdgeProfile
import com.sadurni.attacca.chain.Moods
import com.sadurni.attacca.chain.SoundNet
import com.sadurni.attacca.chain.SoundProfile
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attaccaplg.audio.Decoder
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ln

/**
 * Development: the sound profiles as Attacca makes them (three stretches, a few patches of the
 * sound network each, timbre), and families and likeness from them against the traits alone:
 * of each song's 5 nearest, how many share its album / artist, and how families follow artists
 * (normalised mutual information). Also the time per song.
 * Args: music folder, network weights. Run with `gradlew soundBench -PbenchArgs="folder|weights"`.
 */
fun main(args: Array<String>) {
    val root = File(args[0])
    val net = File(args[1]).inputStream().use { SoundNet(it) }
    val files = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("mp3", "flac", "ogg", "wav") }.sortedBy { it.path }.toList()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val started = System.currentTimeMillis()
    val songs = files.map { f -> pool.submit<SoundSong?> { runCatching { song(f, f.relativeTo(root).invariantSeparatorsPath, net) }.getOrNull() } }.mapNotNull { it.get() }
    pool.shutdown()
    val secs = (System.currentTimeMillis() - started) / 1000.0
    println("${songs.size} of ${files.size} songs in %.0f s · network %.0f ms per song (one thread)".format(secs, songs.map { it.netMs }.average()))
    val artist = songs.map { s -> s.path.split("/").let { if (it[0] == "_CLASSICAL") it[0] + "/" + it[1] else it[0] } }
    val album = songs.map { it.path.substringBeforeLast("/") }
    println("network ms per song: windows %.0f � whole stretches %.0f".format(songs.map { it.netMs }.average(), songs.map { it.oldMs }.average()))
    val cos = songs.map { s -> cosine(s.sound.embedding, s.old.embedding) }
    val timbreGap = songs.map { s -> distance(s.sound.timbre, s.old.timbre) }
    val otherGap = songs.indices.map { i -> distance(songs[i].old.timbre, songs[(i + 37) % songs.size].old.timbre) }
    println("same song, windows vs stretches: embedding cosine %.4f (min %.4f) � timbre distance %.3f (between songs %.3f)".format(
        cos.average(), cos.min(), timbreGap.average(), otherGap.average()))
    for ((name, k) in listOf(
        "traits only" to Moods.classify(songs.map { it.features }),
        "sound profiles (stretches)" to Moods.classify(songs.map { it.features }, sounds = songs.map { it.old }),
        "sound profiles (windows)" to Moods.classify(songs.map { it.features }, sounds = songs.map { it.sound }),
        "sound profiles (half and half)" to Moods.classify(songs.map { it.features }, sounds = songs.mapIndexed { i, s -> if (i % 2 == 0) s.old else s.sound }),
    )) {
        println("$name: ${k.familyNames.size} families · 5 nearest same album %.3f · artist %.3f · families × artist NMI %.3f · album NMI %.3f".format(
            nearest(k, album), nearest(k, artist), nmi(k.families.toList(), artist), nmi(k.families.toList(), album),
        ))
    }
}

private class SoundSong(val path: String, val features: TrackFeatures, val sound: SoundProfile, val netMs: Long, val old: SoundProfile, val oldMs: Long)

private fun cosine(a: FloatArray, b: FloatArray): Double {
    var ab = 0.0; var aa = 0.0; var bb = 0.0
    for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
    return ab / kotlin.math.sqrt(aa * bb + 1e-30)
}

private fun distance(a: FloatArray, b: FloatArray): Double = kotlin.math.sqrt(a.indices.sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() })

/** The profile as before the windows: each whole stretch through the network (4 patches) and its timbre. */
private fun stretchProfile(excerpts: List<FloatArray>, rate: Int, net: SoundNet): SoundProfile? {
    val embeddings = ArrayList<FloatArray>()
    var frames = 0f
    val sum = FloatArray(Cepstrum.COEFFICIENTS)
    val squares = FloatArray(Cepstrum.COEFFICIENTS)
    for (excerpt in excerpts) {
        val wave = SoundNet.resample(excerpt, excerpt.size, rate)
        net.run(wave, SoundProfile.PATCHES_PER_EXCERPT)?.let { embeddings.addAll(it.embeddings) }
        val profile = EdgeProfile.measure(excerpt, 0, excerpt.size, rate, 0) ?: continue
        profile.timbreSums()?.let { (n, s, q) ->
            frames += n
            for (i in sum.indices) { sum[i] += s[i]; squares[i] += q[i] }
        }
    }
    if (embeddings.isEmpty() || frames <= 0f) return null
    val embedding = FloatArray(SoundNet.EMBEDDING_SIZE)
    for (e in embeddings) for (i in embedding.indices) embedding[i] += e[i] / embeddings.size
    val mean = FloatArray(sum.size) { sum[it] / frames }
    val spread = FloatArray(sum.size) { kotlin.math.sqrt(kotlin.math.max(squares[it] / frames - mean[it] * mean[it], 0f)) }
    return SoundProfile(embedding, mean + spread)
}

private fun song(file: File, path: String, net: SoundNet): SoundSong? {
    var rate = 0
    var channelCount = 1
    var mono = FloatArray(1 shl 22)
    var count = 0
    Decoder.decode(file, { format -> rate = format.sampleRate
        channelCount = format.channels; count = 0 }) { samples, frames ->
        val channels = channelCount
        if (mono.size < count + frames) mono = mono.copyOf(maxOf(count + frames, mono.size * 2))
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            mono[count + f] = sum / channels
        }
        count += frames
    } ?: return null
    if (count < 40 * rate) return null
    fun stretch(centre: Double, seconds: Int): FloatArray {
        val half = seconds * rate / 2
        val mid = (count * centre).toInt()
        return mono.copyOfRange((mid - half).coerceAtLeast(0), (mid + half).coerceAtMost(count))
    }
    val middle = stretch(0.5, TrackFeatures.SPAN_SECONDS)
    val features = TrackFeatures.measure(middle, middle.size, rate) ?: return null
    var started = System.nanoTime()
    val old = stretchProfile(SoundProfile.EXCERPTS.map { stretch(it, SoundProfile.EXCERPT_SECONDS) }, rate, net) ?: return null
    val oldMs = (System.nanoTime() - started) / 1_000_000
    started = System.nanoTime()
    val size = SoundProfile.windowSamples(rate)
    val windows = SoundProfile.windowStarts(count.toDouble() / rate).map { at ->
        val from = (at * rate).toInt().coerceIn(0, count)
        mono.copyOfRange(from, minOf(from + size, count))
    }
    val sound = SoundProfile.measure(windows, rate, net) ?: return null
    return SoundSong(path, features, sound, (System.nanoTime() - started) / 1_000_000, old, oldMs)
}

private fun nearest(k: Classification, labels: List<String>): Double {
    val n = labels.size
    var same = 0
    for (i in 0 until n) {
        val near = (0 until n).filter { it != i }.sortedBy { k.distance(i, it) }.take(5)
        same += near.count { labels[it] == labels[i] }
    }
    return same / (5.0 * n)
}

private fun nmi(a: List<Int>, b: List<String>): Double {
    val n = a.size.toDouble()
    val joint = HashMap<Pair<Int, String>, Int>()
    val ca = HashMap<Int, Int>()
    val cb = HashMap<String, Int>()
    for (i in a.indices) {
        joint.merge(a[i] to b[i], 1, Int::plus)
        ca.merge(a[i], 1, Int::plus)
        cb.merge(b[i], 1, Int::plus)
    }
    fun h(counts: Collection<Int>) = -counts.sumOf { (it / n) * ln(it / n) }
    val mi = joint.entries.sumOf { (key, c) -> (c / n) * ln((c / n) / ((ca[key.first]!! / n) * (cb[key.second]!! / n))) }
    return mi / ((h(ca.values) + h(cb.values)) / 2)
}
