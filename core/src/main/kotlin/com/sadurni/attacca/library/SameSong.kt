package com.sadurni.attacca.library

import java.text.Normalizer

/**
 * The same recording found more than once: a song on its album and again on a compilation, a
 * remaster beside the first edition. Two tracks are the same when the artist and the title are
 * the same (the title without what only tells the edition: "(Remastered 2011)", "- Album
 * Version", "feat. …"), they last the same (within [SAME_LENGTH_MS]) and neither is another
 * version: a live one, acoustic, a demo, a remix… (said in the title, or a live album). That
 * only proposes them: a live album need not say so, and its songs last about as long, so the
 * sound has the last word ([RecordingMatch], given as `same`).
 * Of each such group the best copy stays: lossless first, then the higher bit rate, then the one
 * from the artist's own album rather than a compilation.
 */
object SameSong {

    /** What is needed of a track: its tags and length (its file's size is asked only for copies). */
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val durationMs: Long,
        /** Known to be live by its sound (an audience heard), not only by its name. */
        val live: Boolean = false,
    )

    /** Lengths closer than this are the same recording (editions differ by a little silence). */
    const val SAME_LENGTH_MS = 3_000L

    /** Words that make a track another version of a song (kept apart from the others). */
    private val VERSION_WORDS = listOf(
        "live", "en vivo", "en directo", "directo", "vivo", "concert", "concierto", "unplugged",
        "acoustic", "acustico", "acustica", "demo", "remix", "rmx", "mix", "instrumental", "karaoke",
        "rehearsal", "ensayo", "session", "sessions", "take", "edit", "dub", "reprise", "orchestral",
        "piano version", "cover", "medley",
    )

    /** In an album's name, these make all its tracks live ones. */
    private val LIVE_ALBUM_WORDS = listOf("live", "en vivo", "en directo", "directo", "concert", "concierto", "unplugged")

    /** Formats that keep every bit of the sound. */
    private val LOSSLESS = setOf("flac", "wav", "aif", "aiff", "ape", "wv", "dsf", "dff", "tta")

    /** Above this many kbit/s an M4A or the like is lossless (ALAC). */
    private const val LOSSLESS_KBPS = 600

    /**
     * The ids of the copies to leave out: in each group of the same recording all but the best.
     * Those in [keep] (the track a list starts from) always stay, and then stand for their group.
     */
    fun others(
        tracks: List<Track>,
        keep: Set<String> = emptySet(),
        sizeOf: (String) -> Long = { java.io.File(it).length() },
        same: (Track, Track) -> Boolean = { _, _ -> true },
    ): Set<String> {
        val out = HashSet<String>()
        for (group in groups(tracks)) {
            for (one in confirmed(group, sizeOf, same)) {
                if (one.size < 2) continue
                val kept = one.firstOrNull { it.id in keep } ?: one.first()
                for (t in one) if (t.id != kept.id && t.id !in keep) out += t.id
            }
        }
        return out
    }

    /**
     * [group] (proposed by the tags) split into what [same] confirms as one recording, each part
     * best copy first: from the best copy down, each joins the first part whose best copy it
     * matches, or starts a part of its own.
     */
    fun confirmed(group: List<Track>, sizeOf: (String) -> Long, same: (Track, Track) -> Boolean): List<List<Track>> {
        val parts = ArrayList<MutableList<Track>>()
        for (t in byQuality(group, sizeOf)) {
            val part = parts.firstOrNull { same(it[0], t) }
            if (part != null) part += t else parts += mutableListOf(t)
        }
        return parts
    }

    /** The groups of two or more tracks that are the same recording. */
    fun groups(tracks: List<Track>): List<List<Track>> {
        val byKey = HashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val key = keyOf(t) ?: continue
            byKey.getOrPut(key) { ArrayList() } += t
        }
        val out = ArrayList<List<Track>>()
        for (same in byKey.values) {
            if (same.size < 2) continue
            // Within one name, those of the same length (one after another, each near the last).
            val sorted = same.sortedBy { it.durationMs }
            var run = arrayListOf(sorted[0])
            for (t in sorted.drop(1)) {
                if (t.durationMs - run.last().durationMs <= SAME_LENGTH_MS && t.durationMs > 0) run += t
                else {
                    if (run.size > 1) out += run
                    run = arrayListOf(t)
                }
            }
            if (run.size > 1) out += run
        }
        return out
    }

    /** The best copy of [group]. */
    fun best(group: List<Track>, sizeOf: (String) -> Long = { java.io.File(it).length() }): Track = byQuality(group, sizeOf).first()

    /** [group] from the best copy to the worst. */
    fun byQuality(group: List<Track>, sizeOf: (String) -> Long = { java.io.File(it).length() }): List<Track> {
        val kbps = group.associate { it.id to kbps(it, sizeOf(it.id)) }
        return group.sortedWith(
            compareBy<Track>({ if (lossless(it, kbps.getValue(it.id))) 1 else 0 }, { kbps.getValue(it.id) }, { if (ownAlbum(it)) 1 else 0 }, { -it.id.length })
                .thenByDescending { it.id }
                .reversed(),
        )
    }

    /** The name two copies of one recording share; null without a title or an artist. */
    fun keyOf(t: Track): String? {
        val artist = performer(t.artist).ifBlank { performer(t.albumArtist) }
        val raw = plain(t.title)
        if (raw.isBlank() || artist.isBlank()) return null
        // What tells another version, from anywhere in the title or a live album.
        val versions = VERSION_WORDS.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(raw) }.toSortedSet()
        if (t.live || LIVE_ALBUM_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(plain(t.album)) }) versions += "live"
        // The song's own name: without brackets, a " - " addition or a guest.
        val base = plain(
            t.title.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
                .substringBefore(" - ").substringBefore(" – ")
                .replace(Regex("(?i)\\s(feat\\.?|ft\\.|featuring)\\s.*$"), " "),
        ).replace(" ", "")
        if (base.isBlank()) return null
        return artist.replace(" ", "") + "|" + base + "|" + versions.joinToString(",")
    }

    /** An artist's name as compared: without a guest or a leading "the". */
    private fun performer(name: String): String =
        plain(name).substringBefore(" feat ").substringBefore(" ft ").substringBefore(" featuring ").removePrefix("the ").trim()

    private fun lossless(t: Track, kbps: Int): Boolean {
        val ext = t.id.substringAfterLast('.', "").lowercase()
        return ext in LOSSLESS || kbps >= LOSSLESS_KBPS
    }

    /** The average bit rate, from the file's size and length (kbit/s). */
    private fun kbps(t: Track, sizeBytes: Long): Int = if (t.durationMs <= 0) 0 else (sizeBytes * 8 / t.durationMs).toInt()

    /** From the artist's own album (not a compilation of several). */
    private fun ownAlbum(t: Track): Boolean {
        val albumArtist = plain(t.albumArtist)
        val various = albumArtist.isBlank() || albumArtist in setOf("various", "various artists", "varios", "varios artistas", "va")
        return !various && (albumArtist == plain(t.artist) || plain(t.artist).startsWith(albumArtist))
    }

    /** Lower case, without accents and punctuation, single spaces. */
    private fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('’', '\'').replace("'", "")
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
