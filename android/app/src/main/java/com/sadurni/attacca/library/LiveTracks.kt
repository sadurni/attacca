package com.sadurni.attacca.library

import com.sadurni.attacca.chain.Audience
import com.sadurni.attacca.chain.LiveVerdict
import java.io.File

/**
 * Which tracks are live (see [LiveVerdict]): from the audience heard at their ends (kept by the
 * chaining analysis), their albums and their names. Albums are judged whole, so asking for some
 * tracks looks at their folders' other tracks too. Blocking (reads tags and the analysis).
 */
object LiveTracks {

    /** Where the audience heard at the ends is read from (set at app start). */
    @Volatile
    var audience: ((List<String>) -> Map<String, Audience?>)? = null

    /** Grows whenever an audience was heard, so what was worked out is worked out again. */
    @Volatile
    var version = 0
        private set

    fun changed() {
        version++
    }

    private val byFolder = HashMap<String, Pair<Int, Set<String>>>()

    /** The live tracks among [ids]. */
    fun live(ids: Collection<String>): Set<String> {
        val files = ids.filter { !it.contains("://") }.distinct()
        if (files.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for ((folder, inFolder) in files.groupBy { File(it).parent.orEmpty() }) {
            val live = folderLive(folder, inFolder)
            for (id in inFolder) if (id in live) out += id
        }
        saveTypes()
        return out
    }

    fun isLive(id: String): Boolean = id in live(listOf(id))

    /** The audience heard at the ends of [id], or null when not heard yet (or it could not be). */
    fun audienceOf(id: String): Audience? = audience?.invoke(listOf(id))?.get(id)

    /** The MusicBrainz release type of an album: whether it has one at all, and whether it says live. */
    private class ReleaseType(val typed: Boolean, val live: Boolean)

    private val typeByFolder = HashMap<String, Pair<Long, ReleaseType>>()

    /** Where the release types read are kept between runs (set at app start): reading them is the slow part. */
    @Volatile
    var store: File? = null

    private var typesLoaded = false
    private var typesChanged = false

    private fun loadTypes() {
        synchronized(typeByFolder) {
            if (typesLoaded) return
            typesLoaded = true
            runCatching {
                store?.takeIf { it.isFile }?.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 4) p[1].toLongOrNull()?.let { stamp -> typeByFolder[p[0]] = stamp to ReleaseType(p[2] == "1", p[3] == "1") }
                }
            }
        }
    }

    /** Writes the release types read since the last time. */
    fun saveTypes() {
        val target = store ?: return
        val text = synchronized(typeByFolder) {
            if (!typesChanged) return
            typesChanged = false
            typeByFolder.entries.joinToString("\n") { (folder, v) -> "$folder\t${v.first}\t${if (v.second.typed) 1 else 0}\t${if (v.second.live) 1 else 0}" }
        }
        runCatching {
            val tmp = File(target.path + ".tmp")
            tmp.writeText(text + "\n")
            tmp.renameTo(target)
        }
    }

    /**
     * The release type of the album in [folder] (read from one of its [tracks] and kept while the
     * file is unchanged).
     */
    private fun releaseType(folder: String, tracks: List<String>): ReleaseType {
        val file = tracks.firstOrNull()?.let(::File) ?: return ReleaseType(false, false)
        val stamp = file.lastModified() xor file.length()
        loadTypes()
        synchronized(typeByFolder) { typeByFolder[folder]?.let { (at, type) -> if (at == stamp) return type } }
        val type = runCatching {
            val tag = org.jaudiotagger.audio.AudioFileIO.read(file).tag
            tag?.getAll(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_RELEASE_TYPE)?.joinToString("/").orEmpty()
        }.getOrDefault("")
        val result = ReleaseType(type.isNotBlank(), Regex("""\blive\b""").containsMatchIn(type.lowercase()))
        synchronized(typeByFolder) {
            typeByFolder[folder] = stamp to result
            typesChanged = true
        }
        return result
    }

    /** The audio tracks of the album in [folder] (or [asked], when it cannot be listed). */
    fun albumOf(folder: String, asked: List<String> = emptyList()): List<String> =
        AudioFiles.keptFolder(folder)?.takeIf { it.isNotEmpty() && it.containsAll(asked) }?.sorted() ?: runCatching {
        File(folder).listFiles()?.filter { it.isFile && AudioFiles.isAudio(it) }?.map { it.absolutePath }?.sorted()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: asked

    /** The album [tracks] of [folder] as [LiveVerdict] needs them, with the audience in [heard] (missing: not heard yet). */
    fun verdictTracks(folder: String, tracks: List<String>, heard: Map<String, Audience?>): List<LiveVerdict.Track> {
        TrackSort.warm(tracks)
        val type = releaseType(folder, tracks)
        return tracks.map { id ->
            val t = TrackSort.tags(id)
            LiveVerdict.Track(id, folder, t.title, t.album, heard[id], type.live, type.typed)
        }
    }

    private fun folderLive(folder: String, asked: List<String>): Set<String> {
        val v = version
        synchronized(byFolder) { byFolder[folder]?.let { (at, live) -> if (at == v) return live } }
        // The album: the folder's tracks (or those asked, when it cannot be listed).
        val tracks = albumOf(folder, asked)
        val live = LiveVerdict.live(verdictTracks(folder, tracks, audience?.invoke(tracks).orEmpty()))
        synchronized(byFolder) { byFolder[folder] = v to live }
        return live
    }
}
