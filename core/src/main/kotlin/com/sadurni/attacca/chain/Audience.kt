package com.sadurni.attacca.chain

/**
 * How much audience is heard at a track's start and end ([head], [tail]: 0 … 1): applause,
 * cheering, a crowd, clapping, whoops, as the sound network hears them in the first and last
 * [SECONDS] of its sound. A live recording almost always has them there (between songs), seldom
 * in the middle; a studio one never: measured on 102 tracks, studio ones at most 0.06, live ones
 * mostly far above [LIVE].
 */
class Audience(val head: Float, val tail: Float) {

    /** The stronger of the two ends (an end not heard counts as [NOT_HEARD]). */
    val most: Float get() = maxOf(head, tail)

    /** At least one end was heard: not a track left unheard because its album was already told ([SKIPPED]). */
    val heard: Boolean get() = head >= 0f || tail >= 0f

    companion object {
        /** Whoop, clapping, cheering, applause, chatter, crowd, hubbub (the network's class numbers). */
        val CLASSES = intArrayOf(8, 58, 61, 62, 63, 64, 65)

        /** Seconds of sound heard at each end. */
        const val SECONDS = 8

        /** Windows of the network spread over each end. */
        const val PATCHES = 8

        /** From here an end has an audience: the track is live. */
        const val LIVE = 0.15f

        /** An end not heard: its other end already told (an audience at the end needs no look at the start). */
        const val NOT_HEARD = -1f

        /** A track not heard at all: its album was already told by its other tracks (see [LiveHearing]). */
        val SKIPPED = Audience(NOT_HEARD, NOT_HEARD)


        /**
         * The audience in [wave] (mono, [SoundNet.SAMPLE_RATE]): the strongest of [CLASSES] in the
         * strongest of [PATCHES] windows. Null when shorter than a window or cancelled.
         */
        fun score(net: SoundNet, wave: FloatArray, isCancelled: () -> Boolean = { false }, patches: Int = PATCHES): Float? {
            val result = net.run(wave, patches, isCancelled) ?: return null
            var most = 0f
            for (scores in result.scores) for (c in CLASSES) if (scores[c] > most) most = scores[c]
            return most
        }

        fun toBytes(a: Audience): ByteArray = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(a.head).putFloat(a.tail).array()

        fun fromBytes(b: ByteArray): Audience? = if (b.size < 8) null else java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).let { Audience(it.float, it.float) }
    }
}

/**
 * Which tracks are live: an audience heard at one of its ends ([Audience.LIVE]); or its whole
 * album (its folder) when at least two and nearly a third of its tracks have one (an album played
 * as one concert, its songs run together, often has the audience only between some of them, while
 * a studio album with a couple of live extras keeps only those); or its album tagged live (the
 * MusicBrainz release type, right on every album checked) unless its tracks were heard and none
 * has any audience (a session played live in a studio, for the radio: not a concert); or said in
 * its album's name, or in its title as an addition ("(Live)", "- Live at …", not "Live Forever").
 */
object LiveVerdict {

    /** What is needed of a track. [audience] null: not heard yet; [taggedLive]: its album's release type says live. */
    data class Track(
        val id: String,
        val folder: String,
        val title: String,
        val album: String,
        val audience: Audience?,
        val taggedLive: Boolean = false,
        /** Its album has a MusicBrainz release type at all (live or not): the tags can be trusted. */
        val typed: Boolean = false,
    )

    /** Below this at both ends of every track heard, an album has no audience at all. */
    const val QUIET = 0.10f

    /** Words in a title or an album's name that make it live. */
    private val WORDS = listOf("live", "en vivo", "en directo", "directo", "concert", "concierto", "unplugged", "in concert")

    /** The ids of the live tracks among [tracks]. */
    fun live(tracks: List<Track>): Set<String> {
        val out = HashSet<String>()
        for ((_, album) in tracks.groupBy { it.folder }) {
            val heard = album.filter { it.audience?.heard == true }
            val own = heard.count { it.audience!!.most >= Audience.LIVE }
            val silentAudience = heard.size >= 2 && heard.all { it.audience!!.most < QUIET }
            val tagged = album.any { it.taggedLive } && !silentAudience
            val wholeAlbum = tagged || own >= 2 && own * 10 >= heard.size * 3
            for (t in album) {
                if (wholeAlbum || (t.audience?.most ?: 0f) >= Audience.LIVE || said(t.album) || said(addition(t.title))) out += t.id
            }
        }
        return out
    }

    /** Whether [t]'s own name says it is live (its title as an addition, or its album's name): no need to hear it. */
    fun saidLive(t: Track): Boolean = said(t.album) || said(addition(t.title))

    /** What a title adds to the song's name: its brackets and what follows " - ". */
    private fun addition(title: String): String {
        val brackets = BRACKETED.findAll(title).joinToString(" ") { it.groupValues[1] }
        val after = listOf(" - ", " – ").mapNotNull { d -> title.indexOf(d).takeIf { it >= 0 }?.let { title.substring(it + d.length) } }.joinToString(" ")
        return "$brackets $after"
    }

    /** Whether [name] says it is live. */
    fun said(name: String): Boolean {
        val plain = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
        return WORD_PATTERNS.any { it.containsMatchIn(plain) }
    }

    // Made once: asked for every track of a library.
    private val BRACKETED = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
    private val MARKS = Regex("\\p{M}+")
    private val WORD_PATTERNS by lazy { WORDS.map { Regex("\\b${Regex.escape(it)}\\b") } }
}

/**
 * How few of an album's tracks need hearing for [LiveVerdict]: the network is the slow part of the
 * analysis, and on a big library hearing every end takes hours. An album is heard in [order]
 * (first, last, middle, then the rest), and no more of it once [decided]: what is left would not
 * change its verdict. Within a track, the end is heard first and the start only when the end has
 * no audience ([needsHead]).
 */
object LiveHearing {

    /** Below this at both ends, a track has no audience at all. */
    const val CLEAR = 0.05f

    /** So many tracks without any audience make a studio album (the rest is not heard). */
    const val STUDIO_AFTER = 4

    /** As few for an album whose MusicBrainz release type says it is not live. */
    const val TYPED_STUDIO_AFTER = 2

    /** So many tracks with an audience, nearly all of those heard, make a concert. */
    const val LIVE_AFTER = 2

    /** The order an album of [n] tracks is heard in: first, last, middle, then the rest from the start. */
    fun order(n: Int): List<Int> {
        if (n <= 0) return emptyList()
        val first = listOf(0, n - 1, n / 2).distinct().filter { it in 0 until n }
        return first + (0 until n).filter { it !in first }
    }

    /** Whether a track whose end gave [tail] needs its start heard too. */
    fun needsHead(tail: Float): Boolean = tail < Audience.LIVE

    /**
     * Whether [album] (its tracks; audience null: not heard yet) needs no more hearing. Tracks whose
     * name says live never need it.
     */
    fun decided(album: List<LiveVerdict.Track>): Boolean {
        val open = album.filter { it.audience == null && !LiveVerdict.saidLive(it) }
        if (open.isEmpty()) return true
        val heard = album.filter { it.audience?.heard == true }
        val most = heard.map { it.audience!!.most }
        if (album.any { it.taggedLive }) {
            // Tagged live: a concert as soon as any audience is heard; a studio session for the
            // radio when two tracks have none at all.
            return most.any { it >= LiveVerdict.QUIET } || (most.size >= 2 && most.all { it < LiveVerdict.QUIET })
        }
        val own = most.count { it >= Audience.LIVE }
        if (own >= LIVE_AFTER && own * 2 > most.size) return true
        if (most.any { it >= CLEAR }) return false
        val clearAfter = if (album.any { it.typed }) TYPED_STUDIO_AFTER else STUDIO_AFTER
        return most.size >= clearAfter
    }
}
