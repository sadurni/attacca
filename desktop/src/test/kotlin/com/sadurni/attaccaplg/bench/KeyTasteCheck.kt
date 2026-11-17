package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.ChainEngine
import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.ChainTrack
import com.sadurni.attacca.chain.EdgeFeatures
import com.sadurni.attacca.chain.Keys
import com.sadurni.attacca.chain.TasteGroup
import com.sadurni.attaccaplg.library.LibraryDb
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * How the "key and notes" share of the chaining taste changes the links of real lists (the
 * desktop library, KEY_DB=<library.db>): random sets of 40 analysed tracks ordered with each
 * share; per share, how often a link's keys go together (compatibility 0.8 or more, both keys
 * clear), and the mean of the other parts (beat, timbre). Written to build/keytaste.txt.
 */
class KeyTasteCheck {
    @Test
    fun keyShare() {
        val file = System.getenv("KEY_DB")?.let(::File)
        assumeTrue(file != null && file.isFile)
        val tracks = LibraryDb(file!!).use { db -> db.all() }.filter { it.head != null && it.tail != null }
        val out = StringBuilder("tracks ${tracks.size}\n")
        val sets = 40
        for (share in listOf(-1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)) {
            val taste = if (share < 0f) ChainTaste.DEFAULT else ChainTaste.DEFAULT.withShare(TasteGroup.KEY, share)
            EdgeFeatures.taste = taste
            val r = Random(7)
            var links = 0
            var clear = 0
            var good = 0
            var compat = 0.0
            val partSums = DoubleArray(6)
            repeat(sets) {
                val pick = tracks.shuffled(r).take(40)
                val chain = pick.map { ChainTrack(it.head, it.tail, it.tags.artist) }
                val order = ChainEngine.order(chain, 0, 3)!!
                for (i in 0 until order.size - 1) {
                    val end = pick[order[i]].tail!!
                    val start = pick[order[i + 1]].head!!
                    links++
                    EdgeFeatures.parts(end, start)?.forEachIndexed { k, v -> partSums[k] += v.toDouble() }
                    if (end.key.second >= 0.6f && start.key.second >= 0.6f) {
                        clear++
                        val c = Keys.compatibility(end.key.first, start.key.first)
                        compat += c
                        if (c >= 0.8f) good++
                    }
                }
            }
            val label = if (share < 0f) "default(${"%.2f".format(ChainTaste.DEFAULT.share(TasteGroup.KEY))})" else "key %.2f".format(share)
            out.append(
                "%-14s clear links %d/%d  keys go together %.1f %%  mean compat %.3f  | tempo %.3f timbre %.3f bands %.3f activity %.3f key %.3f notes %.3f\n".format(
                    label, clear, links, 100.0 * good / clear, compat / clear,
                    partSums[0] / links, partSums[1] / links, partSums[2] / links, partSums[3] / links, partSums[4] / links, partSums[5] / links,
                ),
            )
        }
        EdgeFeatures.taste = ChainTaste.DEFAULT
        File("build/keytaste.txt").writeText(out.toString())
        println(out)
    }
}
