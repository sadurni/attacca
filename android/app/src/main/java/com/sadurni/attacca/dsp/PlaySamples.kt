package com.sadurni.attacca.dsp

import android.content.Context
import com.sadurni.attacca.Background
import com.sadurni.attacca.analysis.AnalysisDb
import com.sadurni.attacca.analysis.TailDecoder
import com.sadurni.attacca.chain.ChainDb
import com.sadurni.attacca.library.MusicTrack
import com.sadurni.attacca.tuning.Fragments
import com.sadurni.attacca.tuning.Joints
import com.sadurni.attacca.tuning.LevelTrack
import com.sadurni.attacca.tuning.LevelTuning
import com.sadurni.attacca.tuning.PairKind
import com.sadurni.attacca.tuning.Pcm
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Two tracks of the library heard one after the other: the end of [first], the start of [second]. */
class TrackPair(val first: Pcm, val second: Pcm, val firstTitle: String, val secondTitle: String, val fits: Boolean)

/** Two tracks for the volume guide, and whether they are what was looked for. */
class LevelPair(val first: LevelTrack, val second: LevelTrack, val fits: Boolean)

/**
 * The samples of the transitions and volume guides, from the user's own library: pairs of tracks
 * (two different songs, one with silence at its end, two in a row of an album) and tracks of very
 * different loudness. What the analysis already knows (silences, loudness) picks them; otherwise
 * they are chosen at random and measured.
 */
class PlaySamples(private val context: Context, private val folder: String?, private val thresholdDb: Float) {

    private val lock = Mutex()
    private var tracks: List<MusicTrack>? = null
    private var bounds: Map<String, com.sadurni.attacca.analysis.SilenceScanner.Bounds?> = emptyMap()
    private var loudness: Map<String, Float> = emptyMap()

    private fun load(): List<MusicTrack> {
        tracks?.takeIf { it.isNotEmpty() }?.let { return it }
        val f = folder?.takeIf { File(it).isDirectory }
        val list = f?.let { com.sadurni.attacca.library.TrackSort.db?.tracks(it) }.orEmpty()
            .filter { it.tags.durationMs == 0L || it.tags.durationMs > 50_000 }
        val files = list.map { File(it.path) }
        val db = (context.applicationContext as com.sadurni.attacca.AttaccaApp).analysisDb
        bounds = runCatching { db.getBounds(files, thresholdDb) }.getOrDefault(emptyMap())
        val measured = runCatching { db.getAll(files, thresholdDb) }.getOrDefault(emptyMap())
            .mapNotNull { (path, l) -> (l as? AnalysisDb.Lookup.Hit)?.analysis?.loudnessLufs?.let { path to it } }.toMap()
        val features = runCatching { ChainDb(context).use { it.getFeatures(files) } }.getOrDefault(emptyMap())
            .mapNotNull { (path, f) -> f?.loudnessDb?.let { path to it } }.toMap()
        loudness = features + measured
        tracks = list
        return list
    }

    private fun title(t: MusicTrack) = listOf(t.tags.artist, t.title).filter { it.isNotBlank() }.joinToString(" — ")

    private fun durationMs(t: MusicTrack) = t.tags.durationMs.takeIf { it > 0 } ?: bounds[File(t.path).absolutePath]?.durationMs ?: 0L

    /** The end of [t], decoded. */
    private fun tail(t: MusicTrack): Pcm? {
        val d = durationMs(t)
        val from = ((d - Joints.TAIL_SECONDS * 1000L) * 1000).coerceAtLeast(0)
        val pcm = decodeRange(File(t.path), from, (Joints.TAIL_SECONDS + 10) * 1_000_000L) ?: return null
        return Pcm.of(pcm.samples, pcm.channels, pcm.frames, pcm.sampleRate, TunePlayer.RATE)
    }

    /** The start of [t], decoded. */
    private fun head(t: MusicTrack): Pcm? {
        val pcm = decodeRange(File(t.path), 0, Joints.HEAD_SECONDS * 1_000_000L) ?: return null
        return Pcm.of(pcm.samples, pcm.channels, pcm.frames, pcm.sampleRate, TunePlayer.RATE)
    }

    /** A stretch from the middle of [t] (its loudest twelve seconds of a third of the way in). */
    private fun middle(t: MusicTrack): Pcm? {
        val d = durationMs(t)
        val from = if (d > 0) (d * Fragments.SEARCH_FROM).toLong() * 1000 else 30_000_000L
        val pcm = decodeRange(File(t.path), from, Fragments.SEARCH_SECONDS * 1_000_000L) ?: return null
        val samples = pcm.samples.copyOf(pcm.frames * pcm.channels)
        val w = Fragments.bestWindow(samples, pcm.channels, pcm.sampleRate, com.sadurni.attacca.tuning.FragmentKind.ANY)
        val start = w?.startFrame ?: 0
        val frames = minOf(Fragments.SECONDS * pcm.sampleRate, pcm.frames - start)
        return Pcm.of(samples.copyOfRange(start * pcm.channels, (start + frames) * pcm.channels), pcm.channels, frames, pcm.sampleRate, TunePlayer.RATE)
    }

    /** A pair of [kind]; a later call gives another one. */
    suspend fun pair(kind: PairKind): TrackPair? = lock.withLock {
        withContext(Background.dispatcher) {
            val all = load()
            if (all.size < 2) return@withContext null
            repeat(6) {
                val (a, b, fits) = choose(kind, all) ?: return@withContext null
                val t = runCatching { tail(a) }.getOrNull()
                val h = runCatching { head(b) }.getOrNull()
                if (t != null && h != null && t.frames > TunePlayer.RATE * 10 && h.frames > TunePlayer.RATE * 5) {
                    val reallyFits = when (kind) {
                        PairKind.SILENCE -> Joints.silenceAfterMs(t) >= 1_200 || Joints.silenceBeforeMs(h) >= 1_200
                        else -> fits
                    }
                    return@withContext TrackPair(t, h, title(a), title(b), reallyFits)
                }
            }
            null
        }
    }

    private fun choose(kind: PairKind, all: List<MusicTrack>): Triple<MusicTrack, MusicTrack, Boolean>? {
        fun other(a: MusicTrack) = all.filter { it.folder != a.folder }.randomOrNull() ?: all.filter { it !== a }.randomOrNull()
        return when (kind) {
            PairKind.DIFFERENT -> {
                val a = all.random()
                other(a)?.let { Triple(a, it, true) }
            }
            PairKind.SILENCE -> {
                val silent = all.filter { t ->
                    val b = bounds[File(t.path).absolutePath] ?: return@filter false
                    b.durationMs - b.audioEndMs >= 1_500
                }
                val a = silent.randomOrNull() ?: all.random()
                other(a)?.let { Triple(a, it, silent.isNotEmpty()) }
            }
            PairKind.ALBUM -> {
                val pairs = all.groupBy { it.folder }.values.filter { it.size >= 2 }.flatMap { album ->
                    album.sortedWith(compareBy({ it.tags.disc }, { it.tags.track }, { it.path })).zipWithNext()
                }
                if (pairs.isEmpty()) return null
                // Tracks that run on into the next first: there it matters most.
                val runOn = pairs.filter { (a, _) ->
                    val b = bounds[File(a.path).absolutePath] ?: return@filter false
                    b.durationMs - b.audioEndMs < 300
                }
                val (a, b) = (runOn.takeIf { it.isNotEmpty() } ?: pairs).random()
                Triple(a, b, true)
            }
        }
    }

    /**
     * Two tracks for the volume guide: with [sameAlbum], a quiet and a loud one of one album;
     * otherwise the loudest and the quietest of the library (of different albums).
     */
    suspend fun levelPair(sameAlbum: Boolean): LevelPair? = lock.withLock {
        withContext(Background.dispatcher) {
            val all = load()
            if (all.size < 2) return@withContext null
            val known = all.filter { File(it.path).absolutePath in loudness }
            fun lufs(t: MusicTrack) = loudness[File(t.path).absolutePath]
            val pick: Pair<MusicTrack, MusicTrack>? = if (!sameAlbum) {
                val sorted = known.sortedBy { lufs(it)!! }
                if (sorted.size >= 4) {
                    val quiet = sorted.take(5).random()
                    val loud = sorted.takeLast(5).filter { it.folder != quiet.folder }.randomOrNull()
                    loud?.let { it to quiet }
                } else null
            } else {
                val albums = known.groupBy { it.folder }.values.filter { it.size >= 3 }
                albums.maxByOrNull { a -> a.maxOf { lufs(it)!! } - a.minOf { lufs(it)!! } }
                    ?.let { a -> a.minBy { lufs(it)!! } to a.maxBy { lufs(it)!! } }
            }
            val (first, second) = pick ?: guess(all, sameAlbum) ?: return@withContext null
            fun level(t: MusicTrack, pcm: Pcm): LevelTrack {
                val l = lufs(t) ?: LevelTuning.estimateLufs(pcm)
                val album = all.filter { it.folder == t.folder }.mapNotNull { lufs(it) }
                return LevelTrack(pcm, title(t), l, LevelTuning.albumLufs(album.ifEmpty { listOf(l) }))
            }
            val a = runCatching { middle(first) }.getOrNull() ?: return@withContext null
            val b = runCatching { middle(second) }.getOrNull() ?: return@withContext null
            LevelPair(level(first, a), level(second, b), pick != null)
        }
    }

    /** With little measured: a few tracks measured now, the two furthest apart. */
    private fun guess(all: List<MusicTrack>, sameAlbum: Boolean): Pair<MusicTrack, MusicTrack>? {
        val pool = if (sameAlbum) all.groupBy { it.folder }.values.filter { it.size >= 2 }.randomOrNull()?.take(5) ?: return null
        else all.shuffled().take(6)
        val measured = pool.mapNotNull { t -> runCatching { middle(t) }.getOrNull()?.let { t to LevelTuning.estimateLufs(it) } }
        if (measured.size < 2) return null
        loudness = loudness + measured.associate { File(it.first.path).absolutePath to it.second }
        val loud = measured.maxBy { it.second }.first
        val quiet = measured.minBy { it.second }.first
        return if (sameAlbum) quiet to loud else loud to quiet
    }
}

/**
 * [lengthUs] of [file] decoded from [fromUs] ([TailDecoder] wants a point inside what it decodes
 * to line it up by: one second in).
 */
internal fun decodeRange(file: File, fromUs: Long, lengthUs: Long): com.sadurni.attacca.dsp.CrossfadeTail? =
    TailDecoder.decode(file, fromUs + 1_000_000L, (lengthUs - 1_000_000L).coerceAtLeast(1_000_000L), leadUs = 1_000_000L)
