package com.sadurni.attacca.dsp

import android.content.Context
import com.sadurni.attacca.Background
import com.sadurni.attacca.analysis.TailDecoder
import com.sadurni.attacca.chain.ChainDb
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attacca.library.MusicTrack
import com.sadurni.attacca.tuning.Fragment
import com.sadurni.attacca.tuning.FragmentKind
import com.sadurni.attacca.tuning.Fragments
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The samples of the sound guide from the user's own music: for each kind, the tracks with most of
 * it first (from their analysis) and in each the best twelve seconds. "Another sample" goes on to
 * the next tracks. With no music to use, the guide's own groove.
 */
class TuneSamples(private val context: Context, private val folder: String?) {

    private var tracks: List<MusicTrack>? = null
    private var features: Map<String, TrackFeatures?> = emptyMap()
    private val ordered = HashMap<FragmentKind, List<MusicTrack>>()
    private val next = HashMap<FragmentKind, Int>()

    /** The next sample for [kind]; blocking work runs in the background. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun next(kind: FragmentKind): Fragment = lock.withLock { find(kind) }

    private suspend fun find(kind: FragmentKind): Fragment = withContext(Background.dispatcher) {
        val list = order(kind)
        // A few tracks are looked into, and the one with most of what is wanted kept.
        val tries = if (kind == FragmentKind.ANY) 1 else 2
        var best: Pair<Fragment, Double>? = null
        var looked = 0
        var i = next[kind] ?: 0
        while (looked < tries && i < list.size) {
            val track = list[i++]
            val found = runCatching { sample(track, kind) }.getOrNull() ?: continue
            looked++
            if (best == null || found.second > best.second) best = found
        }
        // Round again once every track has been heard.
        next[kind] = if (i >= list.size) 0 else i
        best?.first ?: Fragments.groove(TunePlayer.RATE)
    }

    private fun order(kind: FragmentKind): List<MusicTrack> = ordered[kind] ?: run {
        // (A library still being read gives none yet: asked again next time.)
        val all = tracks?.takeIf { it.isNotEmpty() } ?: run {
            val f = folder?.takeIf { File(it).isDirectory }
            val list = f?.let { com.sadurni.attacca.library.TrackSort.db?.tracks(it) }.orEmpty()
                // Long enough to have a fragment from a third of the way in.
                .filter { it.tags.durationMs == 0L || it.tags.durationMs > 40_000 }
                .shuffled()
            tracks = list
            features = runCatching { ChainDb(context).use { db -> db.getFeatures(list.map { File(it.path) }) } }.getOrDefault(emptyMap())
            list
        }
        val sorted = Fragments.order(kind, all) { features[File(it.path).absolutePath] }
        // The best few dozen, mixed, so each run of the guide hears something else.
        (sorted.take(24).shuffled() + sorted.drop(24)).also { if (it.isNotEmpty()) ordered[kind] = it }
    }

    private fun sample(track: MusicTrack, kind: FragmentKind): Pair<Fragment, Double>? {
        val file = File(track.path)
        val duration = track.tags.durationMs
        val fromUs = if (duration > 0) (duration * Fragments.SEARCH_FROM).toLong() * 1000 else 30_000_000L
        val pcm = decodeRange(file, fromUs, Fragments.SEARCH_SECONDS * 1_000_000L) ?: return null
        val samples = if (pcm.samples.size == pcm.frames * pcm.channels) pcm.samples else pcm.samples.copyOf(pcm.frames * pcm.channels)
        val window = Fragments.bestWindow(samples, pcm.channels, pcm.sampleRate, kind) ?: return null
        val title = listOf(track.tags.artist, track.title).filter { it.isNotBlank() }.joinToString(" — ")
        return Fragments.cut(samples, pcm.channels, pcm.sampleRate, window.startFrame, title, TunePlayer.RATE) to window.score
    }
}
