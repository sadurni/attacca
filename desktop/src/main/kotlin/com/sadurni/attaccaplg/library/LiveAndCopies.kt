package com.sadurni.attaccaplg.library

import com.sadurni.attacca.chain.Audience
import com.sadurni.attacca.chain.LiveHearing
import com.sadurni.attacca.chain.LiveVerdict
import com.sadurni.attacca.library.RecordingMatch
import com.sadurni.attacca.library.SameSong
import com.sadurni.attaccaplg.analysis.TrackAnalyzer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live tracks and copies of one song, as Attacca on the phone: which tracks are live
 * ([LiveVerdict], from the audience heard at their ends, their albums and names) and which are
 * another copy of a recording kept elsewhere ([SameSong], proposed by the tags, confirmed by the
 * sound). The scan hears new tracks as it measures them; [hearAudience] hears those measured
 * before, album by album and no more than tells each album; [compareCopies] compares the copies
 * the tags propose that were not compared yet.
 */
object LiveAndCopies {

    /** The MusicBrainz release type of each album read (folder → whether typed, whether live), by the first file's date. */
    private val types = ConcurrentHashMap<String, Triple<Long, Boolean, Boolean>>()

    private fun folderOf(path: String) = path.substringBeforeLast('/', "")

    /** Whether the album of [tracks] in [folder] has a MusicBrainz release type, and whether it says live. */
    private fun releaseType(root: File?, folder: String, tracks: List<StoredTrack>): Pair<Boolean, Boolean> {
        val file = root?.let { r -> tracks.firstOrNull()?.let { File(r, it.path) } }?.takeIf { it.isFile } ?: return false to false
        types[folder]?.let { (at, typed, live) -> if (at == file.lastModified()) return typed to live }
        val type = runCatching {
            org.jaudiotagger.audio.AudioFileIO.read(file).tag?.getAll(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_RELEASE_TYPE)?.joinToString("/").orEmpty()
        }.getOrDefault("")
        val typed = type.isNotBlank()
        val live = Regex("""\blive\b""").containsMatchIn(type.lowercase())
        types[folder] = Triple(file.lastModified(), typed, live)
        return typed to live
    }

    /** The tracks of one album as [LiveVerdict] needs them (audience as stored). */
    private fun verdictTracks(root: File?, folder: String, album: List<StoredTrack>): List<LiveVerdict.Track> {
        val (typed, live) = releaseType(root, folder, album)
        return album.map { LiveVerdict.Track(it.path, folder, it.tags.title, it.tags.album, it.audience, live, typed) }
    }

    /** The live tracks among [tracks] (whole albums judged together). */
    fun live(root: File?, tracks: List<StoredTrack>): Set<String> {
        val out = HashSet<String>()
        for ((folder, album) in tracks.groupBy { folderOf(it.path) }) out += LiveVerdict.live(verdictTracks(root, folder, album))
        return out
    }

    /**
     * Hears the audience of the tracks measured before it was heard (none stored), album by album
     * in the order that tells each soonest, no more of an album once it is told (the rest stored as
     * not heard). Returns how many were heard.
     */
    fun hearAudience(
        db: LibraryDb,
        root: File,
        jobs: Int = Scanner.defaultJobs(),
        cancelled: () -> Boolean = { false },
        progress: (done: Int, todo: Int) -> Unit = { _, _ -> },
    ): Int {
        val all = db.all().filter { it.error == null && it.features != null }
        val albums = all.groupBy { folderOf(it.path) }.filterValues { album -> album.any { it.audience == null } }
        val todo = albums.values.sumOf { album -> album.count { it.audience == null } }
        if (todo == 0) return 0
        val done = AtomicInteger()
        val heard = AtomicInteger()
        val pool = Executors.newFixedThreadPool(jobs)
        for ((folder, album) in albums) {
            pool.execute {
                val sorted = album.sortedBy { it.path }
                val tracks = verdictTracks(root, folder, sorted).toMutableList()
                for (i in LiveHearing.order(sorted.size)) {
                    if (cancelled()) return@execute
                    if (tracks[i].audience != null) continue
                    val told = LiveVerdict.saidLive(tracks[i]) || LiveHearing.decided(tracks)
                    val file = File(root, sorted[i].path)
                    val audience = if (told || !file.isFile || file.length() != sorted[i].size) Audience.SKIPPED
                    else (runCatching { TrackAnalyzer.hearAudience(file) }.getOrNull() ?: Audience(0f, 0f)).also { heard.incrementAndGet() }
                    tracks[i] = tracks[i].copy(audience = audience)
                    db.setAudience(sorted[i].path, audience)
                    val n = done.incrementAndGet()
                    if (n % 50 == 0) db.commit()
                    progress(n, todo)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
        db.commit()
        return heard.get()
    }

    private fun sameTracks(root: File?, tracks: List<StoredTrack>): List<SameSong.Track> {
        val liveSet = live(root, tracks)
        return tracks.filter { it.error == null && it.durationMs > 0 }.map {
            SameSong.Track(it.path, it.tags.title, it.tags.artist, it.tags.album, it.tags.albumArtist, it.durationMs, it.path in liveSet)
        }
    }

    /** The verdicts stored for pairs whose files are unchanged since (by size). */
    private fun verdicts(db: LibraryDb, sizes: Map<String, Long>): Map<Pair<String, String>, Boolean> =
        db.sames().filter { sizes[it.a] == it.sizeA && sizes[it.b] == it.sizeB }.associate { (it.a to it.b) to it.same }

    private fun pair(a: String, b: String) = if (a < b) a to b else b to a

    /**
     * Compares the sound of the copies the tags propose that were not compared yet (half a minute
     * from the middle of each). Returns how many pairs were compared.
     */
    fun compareCopies(
        db: LibraryDb,
        root: File,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, todo: Int) -> Unit = { _, _ -> },
    ): Int {
        val all = db.all()
        val sizes = all.associate { it.path to it.size }
        val known = verdicts(db, sizes)
        val tracks = sameTracks(root, all)
        val pairs = SameSong.groups(tracks).flatMap { g ->
            (g.indices).flatMap { i -> (i + 1 until g.size).map { j -> g[i] to g[j] } }
        }.filter { (a, b) -> pair(a.id, b.id) !in known }
        if (pairs.isEmpty()) return 0
        val levels = HashMap<String, FloatArray?>()
        fun levelsOf(t: SameSong.Track) = levels.getOrPut(t.id) {
            File(root, t.id).takeIf { it.isFile }?.let { runCatching { TrackAnalyzer.recordingLevels(it, t.durationMs) }.getOrNull() }
        }
        var done = 0
        for ((a, b) in pairs) {
            if (cancelled()) break
            val la = levelsOf(a)
            val lb = levelsOf(b)
            if (la != null && lb != null) {
                val likeness = maxOf(RecordingMatch.likeness(la, lb), RecordingMatch.likeness(lb, la))
                if (!likeness.isNaN()) db.putSame(LibraryDb.Same(a.id, b.id, sizes.getValue(a.id), sizes.getValue(b.id), likeness >= RecordingMatch.SAME))
            }
            progress(++done, pairs.size)
        }
        db.commit()
        return done
    }

    /** Of [tracks], the copies to leave out of lists (another copy of the recording stays); [keep] always stays. */
    fun copies(db: LibraryDb, root: File?, tracks: List<StoredTrack>, keep: Set<String> = emptySet()): Set<String> {
        val sizes = tracks.associate { it.path to it.size }
        val known = verdicts(db, sizes)
        val all = sameTracks(root, tracks)
        if (all.size < 2) return emptySet()
        return SameSong.others(all, keep, sizeOf = { sizes[it] ?: 0L }, same = { a, b -> known[pair(a.id, b.id)] == true })
    }
}
