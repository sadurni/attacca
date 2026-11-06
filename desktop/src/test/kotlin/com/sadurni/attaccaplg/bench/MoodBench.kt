package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.EdgeProfile
import com.sadurni.attacca.chain.Moods
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attaccaplg.audio.Decoder
import java.io.File
import java.util.concurrent.Executors

/**
 * Development: what the mood and family analysis says about real music, for comparing ways of
 * doing it (see the Python analysis next to the YAMNet extraction). For every song: the track
 * features as the app measures them (30 s from the middle) and on three 10 s excerpts (at 25, 50
 * and 75 %), the timbre coefficients of the middle (mean and spread), and the mood and family the
 * current classification gives (excerpts classified against the same collection).
 * Args: music folder, output CSV. Run with `gradlew moodBench -PbenchArgs="folder|out.csv"`.
 */
fun main(args: Array<String>) {
    val root = File(args[0])
    val out = File(args[1])
    val files = root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("mp3", "flac", "ogg", "wav") }
        .sortedBy { it.path }
        .toList()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val songs = files.map { f -> pool.submit<Measured?> { runCatching { measure(f, f.relativeTo(root).invariantSeparatorsPath) }.getOrNull() } }.mapNotNull { it.get() }
    pool.shutdown()
    val whole = Moods.classify(songs.map { it.middle })
    val excerpts = Moods.classify(songs.flatMap { it.excerpts })
    out.parentFile?.mkdirs()
    out.bufferedWriter().use { w ->
        val featureNames = listOf("loud", "dyn", "bright", "bass", "noise", "perc", "tempo", "pulse", "mode", "keyclar", "voice")
        w.write(
            (listOf("path", "mood", "family", "energy", "positivity") + featureNames.map { "m_$it" } +
                (0 until 3).flatMap { e -> listOf("mood$e") + featureNames.map { "e${e}_$it" } } +
                (1..13).map { "cep$it" } + (1..13).map { "cepsd$it" }).joinToString(",") + "\n",
        )
        for ((i, s) in songs.withIndex()) {
            val row = ArrayList<String>()
            row.add("\"" + s.path.replace("\"", "\"\"") + "\"")
            row.add(whole.moods[i].name)
            row.add(whole.families[i].toString())
            row.add(whole.energy[i].toString())
            row.add(whole.positivity[i].toString())
            row.addAll(s.middle.toArray().map { it.toString() })
            for (e in 0 until 3) {
                row.add(excerpts.moods[i * 3 + e].name)
                row.addAll(s.excerpts[e].toArray().map { it.toString() })
            }
            row.addAll(s.timbre.map { it.toString() })
            row.addAll(s.spread.map { it.toString() })
            w.write(row.joinToString(",") + "\n")
        }
    }
    println("${songs.size} of ${files.size} songs → $out")
}

private class Measured(
    val path: String,
    val middle: TrackFeatures,
    val excerpts: List<TrackFeatures>,
    val timbre: FloatArray,
    val spread: FloatArray,
)

private fun measure(file: File, path: String): Measured? {
    var rate = 0
    var channelCount = 1
    var mono = FloatArray(1 shl 22)
    var count = 0
    Decoder.decode(file, { format ->
        rate = format.sampleRate
        channelCount = format.channels
        count = 0
    }) { samples, frames ->
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
    // As the app: 30 s from the middle.
    val m = stretch(0.5, TrackFeatures.SPAN_SECONDS)
    val middle = TrackFeatures.measure(m, m.size, rate) ?: return null
    val excerpts = listOf(0.25, 0.5, 0.75).map { c -> stretch(c, 10).let { TrackFeatures.measure(it, it.size, rate) ?: return null } }
    val profile = EdgeProfile.measure(m, 0, m.size, rate, 0) ?: return null
    val (timbre, spread) = profile.timbre(profile.startMs, profile.endMs) ?: return null
    return Measured(path, middle, excerpts, timbre, spread)
}
