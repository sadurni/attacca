package com.sadurni.attacca.dsp

import android.content.Context
import com.sadurni.attacca.Background
import com.sadurni.attacca.chain.ChainDb
import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.TasteGroup
import com.sadurni.attacca.library.MusicTrack
import com.sadurni.attacca.tuning.JoinChoice
import com.sadurni.attacca.tuning.Joint
import com.sadurni.attacca.tuning.Joints
import com.sadurni.attacca.tuning.Pcm
import com.sadurni.attacca.tuning.TasteRound
import com.sadurni.attacca.tuning.TasteTrack
import com.sadurni.attacca.tuning.TasteTuning
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A question of the chaining guide ready to hear: the song's end joined into each of the two starts. */
class TasteSample(val round: TasteRound, val fromTitle: String, val firstTitle: String, val secondTitle: String, val first: Joint, val second: Joint)

/**
 * The samples of the chaining guide, from the analysed tracks of the user's library: a song's end
 * and two songs that could follow it, joined as the player joins tracks ([join]).
 */
class TasteSamples(private val context: Context, private val folder: String?, private val edgeSeconds: Int, private val join: JoinChoice) {

    private val lock = Mutex()
    private var tracks: List<TasteTrack>? = null
    private var byId: List<MusicTrack> = emptyList()
    private val used = mutableSetOf<Int>()

    /** How many tracks have their ends measured (none: the guide cannot start). */
    suspend fun count(): Int = lock.withLock { withContext(Background.dispatcher) { load().size } }

    private fun load(): List<TasteTrack> {
        tracks?.let { return it }
        val f = folder?.takeIf { File(it).isDirectory }
        val list = f?.let { com.sadurni.attacca.library.TrackSort.db?.tracks(it) }.orEmpty()
            .filter { it.tags.durationMs == 0L || it.tags.durationMs > 50_000 }
        val edges = runCatching { ChainDb(context).use { db -> db.getAll(list.map { File(it.path) }, edgeSeconds) } }.getOrDefault(emptyMap())
        val known = list.filter { edges[File(it.path).absolutePath] != null }
        byId = known
        val result = known.mapIndexed { i, t ->
            val e = edges[File(t.path).absolutePath]!!
            TasteTrack(i, e.head, e.tail, t.tags.artist, t.folder)
        }.filter { it.head?.timbre != null || it.tail?.timbre != null }
        tracks = result
        return result
    }

    private fun title(t: MusicTrack) = listOf(t.tags.artist, t.title).filter { it.isNotBlank() }.joinToString(" — ")

    private fun tail(t: MusicTrack): Pcm? {
        val d = t.tags.durationMs
        if (d <= 0) return null
        val from = ((d - Joints.TAIL_SECONDS * 1000L) * 1000).coerceAtLeast(0)
        val pcm = decodeRange(File(t.path), from, (Joints.TAIL_SECONDS + 10) * 1_000_000L) ?: return null
        return Pcm.of(pcm.samples, pcm.channels, pcm.frames, pcm.sampleRate, TunePlayer.RATE)
    }

    private fun head(t: MusicTrack): Pcm? {
        val pcm = decodeRange(File(t.path), 0, Joints.HEAD_SECONDS * 1_000_000L) ?: return null
        return Pcm.of(pcm.samples, pcm.channels, pcm.frames, pcm.sampleRate, TunePlayer.RATE)
    }

    /** A question about [group], or null when the library has none (too few tracks analysed). */
    suspend fun next(group: TasteGroup, taste: ChainTaste): TasteSample? = lock.withLock {
        withContext(Background.dispatcher) {
            val all = load()
            repeat(4) {
                val round = TasteTuning.pick(all, group, taste, avoid = used) ?: return@withContext null
                used += round.from.id
                val from = byId[round.from.id]
                val first = byId[round.first.id]
                val second = byId[round.second.id]
                val t = runCatching { tail(from) }.getOrNull()
                val a = runCatching { head(first) }.getOrNull()
                val b = runCatching { head(second) }.getOrNull()
                if (t != null && a != null && b != null && t.frames > TunePlayer.RATE * 10) {
                    return@withContext TasteSample(
                        round, title(from), title(first), title(second),
                        Joints.render(t, a, join), Joints.render(t, b, join),
                    )
                }
            }
            null
        }
    }
}
