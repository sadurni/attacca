package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.MoodHead
import com.sadurni.attacca.chain.Moods
import com.sadurni.attacca.chain.SoundNet
import com.sadurni.attaccaplg.library.LibraryDb
import java.io.File

/**
 * Development: everything a library holds that a mood could come from, as CSV, for the mood
 * analysis in Python: tags, loudness, the track features, the timbre, the scores of the sound
 * network's music classes (genres and moods) from the stored sound profile, the mood head's
 * log-odds and the moods the classification gives.
 * Args: library file, network weights, output CSV. Run with `gradlew moodData -PbenchArgs="db|weights|out.csv"`.
 */
fun main(args: Array<String>) {
    val db = LibraryDb(File(args[0]))
    val net = File(args[1]).inputStream().use { SoundNet(it) }
    val head = File(args[1]).inputStream().use { MoodHead.read(it) }
    MoodHead.source = { File(args[1]).inputStream() }
    val classes = 132..276
    val tracks = db.all().filter { it.features != null && it.sound != null }
    val k = Moods.classify(tracks.map { it.features!! }, sounds = tracks.map { it.sound })
    File(args[2]).bufferedWriter().use { w ->
        val featureNames = listOf("loud", "dyn", "bright", "bass", "noise", "perc", "tempo", "pulse", "mode", "keyclar", "voice")
        w.write((listOf("path", "artist", "album", "title", "genre", "year", "duration", "lufs") + featureNames +
            (1..26).map { "tim$it" } + classes.map { "c$it" } + (0 until MoodHead.CLASSES.size).map { "h$it" } + listOf("kmood", "kmood2")).joinToString(",") + "\n")
        for ((i, t) in tracks.withIndex()) {
            val f = t.features!!
            val sound = t.sound!!
            val scores = net.classify(sound.embedding)
            fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
            val row = listOf(q(t.path), q(t.tags.artist), q(t.tags.album), q(t.tags.title), q(t.tags.genre), (t.tags.year ?: 0).toString(), t.durationMs.toString(), (t.lufs ?: Float.NaN).toString()) +
                f.toArray().map { it.toString() } + sound.timbre.map { it.toString() } + classes.map { scores[it].toString() } +
                head.logits(sound.embedding).map { it.toString() } + listOf(k.moods[i].name, k.secondMoods[i]?.name ?: "")
            w.write(row.joinToString(",") + "\n")
        }
    }
}
