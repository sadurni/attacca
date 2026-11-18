package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.library.SameSong
import com.sadurni.attaccaplg.library.LibraryDb
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Development (HOUR_DB=<a copy of library.db>): how long telling copies of one song takes with
 * the real tags (keys, groups, the copies left out). Written to build/copiesbench.txt.
 */
class CopiesBench {
    @Test
    fun times() {
        val file = System.getenv("HOUR_DB")?.let(::File)
        assumeTrue(file != null && file.isFile)
        val all = LibraryDb(file!!).use { it.all() }
        // As many tracks as a big phone library (the real tags, eight times over in other folders).
        val tracks = (0 until 8).flatMap { copy -> all.map { SameSong.Track("/c$copy" + it.path, it.tags.title, it.tags.artist, it.tags.album, it.tags.albumArtist, it.durationMs, false) } }
        val out = StringBuilder("${tracks.size} tracks\n")
        repeat(2) { round ->
            var t = System.nanoTime()
            tracks.forEach { SameSong.keyOf(it) }
            out.append("round $round: keyOf ${(System.nanoTime() - t) / 1_000_000} ms\n")
            t = System.nanoTime()
            val groups = SameSong.groups(tracks)
            out.append("round $round: groups ${(System.nanoTime() - t) / 1_000_000} ms (${groups.size} groups, ${groups.sumOf { it.size }} tracks)\n")
            t = System.nanoTime()
            val others = SameSong.others(tracks)
            out.append("round $round: others ${(System.nanoTime() - t) / 1_000_000} ms (${others.size} left out)\n")
        }
        println(out)
        File("build/copiesbench.txt").writeText(out.toString())
    }
}
