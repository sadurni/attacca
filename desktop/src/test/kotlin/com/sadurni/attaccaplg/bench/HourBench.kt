package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.ChainFilters
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.LibraryDb
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Development (HOUR_DB=<a copy of library.db>): how long the lists of "what you play at this
 * hour" (the tracks closest to several heard ones) take against a quick list (closest to one),
 * with the real library. Written to build/hourbench.txt.
 */
class HourBench {
    @Test
    fun times() {
        val file = System.getenv("HOUR_DB")?.let(::File)
        assumeTrue(file != null && file.isFile)
        val out = StringBuilder()
        fun note(s: String) { out.append(s).append('\n'); println(s) }
        var t = System.nanoTime()
        val g = Library(LibraryDb(file!!)).generator
        note("library ${g.candidates.size} tracks: ${(System.nanoTime() - t) / 1_000_000} ms")
        val r = Random(3)
        for (count in listOf(50, 150)) {
            for (seeds in listOf(1, 5, 20, 50)) {
                val picked = g.candidates.indices.shuffled(r).take(seeds)
                t = System.nanoTime()
                val ids = g.fromSeeds(picked, ChainFilters(), false, false, count, 3)
                note("count $count, $seeds heard: ${ids?.size} tracks in ${(System.nanoTime() - t) / 1_000_000} ms")
            }
            val seed = r.nextInt(g.candidates.size)
            t = System.nanoTime()
            val fit = g.fitToSeed(seed, ChainFilters(durationMinS = 60, durationMaxS = 1200), true, true, count)
            val quick = g.fromSeed(seed, fit.filters, true, true, count, 3)
            note("count $count, quick list: ${quick?.size} tracks in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        File("build/hourbench.txt").writeText(out.toString())
    }
}
