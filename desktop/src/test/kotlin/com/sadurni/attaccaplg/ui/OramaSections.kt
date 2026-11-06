package com.sadurni.attaccaplg.ui

import com.sadurni.attacca.diorama.Sections
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attaccaplg.library.LibraryDb
import java.io.File

/**
 * Development: which tracks of a library O'rama would split into stretches of other worlds, and
 * where. Args: library.db, then words to pick tracks by (all tracks when none).
 */
fun main(args: Array<String>) {
    val db = LibraryDb(File(args[0]))
    val words = args.drop(1).map { it.lowercase() }
    val tracks = db.all().filter { it.waveform != null && it.durationMs > 0 }
    var split = 0
    for (t in tracks) {
        val wave = SongWave(t.waveform!!, t.durationMs, t.audioStartMs, t.audioEndMs.takeIf { it > t.audioStartMs } ?: t.durationMs)
        val s = Sections.of(wave)
        if (s != null) split++
        if (words.isNotEmpty() && words.none { t.path.lowercase().contains(it) }) continue
        if (words.isEmpty() && s == null) continue
        val line = StringBuilder()
        if (s != null) {
            var last = 99
            var at = 0.0
            while (at < wave.lengthMs) {
                val m = s.modeAt(at)
                if (m != last) {
                    line.append(" ${(at / 1000).toInt() / 60}:${"%02d".format((at / 1000).toInt() % 60)}=${if (m < 0) "quiet" else if (m > 0) "loud" else "as is"}")
                    last = m
                }
                at += 1000.0
            }
        }
        println("${t.path}: ${if (s == null) "one world" else line}")
        if (words.isNotEmpty()) {
            // Its level every 10 s (12 s window), raw and as the land shows it.
            val raw = (0 until (wave.lengthMs / 10_000).toInt()).joinToString(" ") { "%.2f".format(wave.mean(wave.startMs + it * 10_000 + 5_000.0, 12_000.0)) }
            println("   $raw")
        }
    }
    println("split: $split of ${tracks.size}")
    System.exit(0)
}
