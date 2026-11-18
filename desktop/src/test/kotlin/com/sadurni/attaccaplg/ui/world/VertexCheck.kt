package com.sadurni.attaccaplg.ui.world

import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.world.FloatList
import com.sadurni.attacca.world.LandMesher
import com.sadurni.attacca.world.Mesh
import com.sadurni.attacca.world.Places
import com.sadurni.attacca.world.Props
import com.sadurni.attacca.world.WorldLand
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

/**
 * Development (VERTEX_CHECK=1): where Attacca World's vertices come from, per world: the land's
 * surface, what stands on it, the places and the water, for the chunks in sight from one spot
 * (as the phone draws them: 40 behind to 150 ahead). Written to build/vertices.txt.
 */
class VertexCheck {
    private val bins = FloatArray(600) { i -> (0.45f + 0.3f * sin(i * 0.05f) + 0.15f * sin(i * 0.31f)).coerceIn(0.05f, 1f) }

    @Test
    fun vertices() {
        assumeTrue(System.getenv("VERTEX_CHECK") != null)
        val out = StringBuilder()
        for (world in World.entries) {
            val look = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.5f, positivity = 0.6f, voice = 0.4f, seed = world.name.hashCode())), world)
            val land = WorldLand(SongWave(bins, 300_000L, 0L, 300_000L), look, world.name.hashCode(), false, null, null, 0.0, false, null)
            var total = 0L; var props = 0L; var places = 0L
            val tz = 400f
            val first = kotlin.math.floor((tz - 40) / LandMesher.CHUNK).toInt()
            val last = kotlin.math.floor((tz + 150f) / LandMesher.CHUNK).toInt()
            var lod = 0L
            var middle = 0L
            for (k in first..last) {
                val full = LandMesher.chunk(land, k)
                total += full.vertices
                val farChunk = k * LandMesher.CHUNK > tz + 56f
                val m = if (farChunk) LandMesher.chunk(land, k, 2f, 1.3f, 0.5f) else full
                lod += m.vertices
                // Of those, the ones whose triangle lies within 36 m of the middle (a column of four).
                val d = m.data
                var t = 0
                while (t < m.vertices) {
                    val cx = (d[t * Mesh.STRIDE] + d[(t + 1) * Mesh.STRIDE] + d[(t + 2) * Mesh.STRIDE]) / 3
                    if (kotlin.math.abs(cx) < 36f) middle += 3
                    t += 3
                }
                val p = FloatList(); Props.place(land, k * LandMesher.CHUNK, (k + 1) * LandMesher.CHUNK, p); props += p.mesh().vertices
                if (land.places.isNotEmpty()) { val q = FloatList(); Places.bake(land, land.places, k * LandMesher.CHUNK, (k + 1) * LandMesher.CHUNK, q); places += q.mesh().vertices }
            }
            val chunks = last - first + 1
            val surface = chunks * 144L * 16 * 6
            val water = total - surface - props - places
            out.append("%-10s %2d chunks: %7dk in all · surface %4dk · on it %5dk · places %4dk · water %4dk · far coarse %5dk · and middle half %5dk\n".format(
                world.name, chunks, total / 1000, surface / 1000, props / 1000, places / 1000, water / 1000, lod / 1000, middle / 1000))
        }
        println(out)
        File("build/vertices.txt").writeText(out.toString())
    }
}
