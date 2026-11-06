package com.sadurni.attacca.library

import java.io.File

/**
 * Smart lists: a list of the user's whose tracks come from conditions, kept in a plain text file
 * next to it (`<list>.smart.txt`) so it can also be written by hand. The list itself (an ordinary
 * M3U8) is computed from them again after each library scan and when it is opened.
 *
 * ```
 * genre contains rock
 * year 1990-1999
 * not artist is Queen
 * played not-within 30
 * mood energetic, cheerful
 * order random
 * limit 150
 * ```
 *
 * Every condition must be met; `not` in front of one turns it round.
 */
object SmartRules {

    /** What a condition looks at. */
    enum class Field(val word: String, val kind: Kind) {
        ARTIST("artist", Kind.TEXT),
        ALBUM_ARTIST("albumartist", Kind.TEXT),
        ALBUM("album", Kind.TEXT),
        TITLE("title", Kind.TEXT),
        GENRE("genre", Kind.TEXT),
        FOLDER("folder", Kind.TEXT),
        PATH("path", Kind.TEXT),
        YEAR("year", Kind.RANGE),
        LENGTH("length", Kind.RANGE),
        BPM("bpm", Kind.RANGE),
        ENERGY("energy", Kind.RANGE),
        POSITIVITY("positivity", Kind.RANGE),
        MOOD("mood", Kind.SET),
        VOICE("voice", Kind.CHOICE),
        ADDED("added", Kind.DAYS),
        PLAYED("played", Kind.PLAYED),
        SKIPPED("skipped", Kind.COUNT),
        INCLUDED("included", Kind.FLAG),
    }

    enum class Kind { TEXT, RANGE, SET, CHOICE, DAYS, PLAYED, COUNT, FLAG }

    /** Text comparisons. */
    enum class TextOp(val word: String) { IS("is"), CONTAINS("contains"), STARTS("starts") }

    /** Played conditions: never, within N days, not within N days, at least N times. */
    enum class PlayedOp(val word: String) { NEVER("never"), WITHIN("within"), NOT_WITHIN("not-within"), AT_LEAST("at-least") }

    data class Condition(
        val field: Field,
        val negated: Boolean = false,
        val textOp: TextOp = TextOp.CONTAINS,
        val text: String = "",
        val min: Double? = null,
        val max: Double? = null,
        val values: Set<String> = emptySet(),
        val playedOp: PlayedOp = PlayedOp.NEVER,
        val number: Int = 0,
    ) {
        fun format(): String {
            val head = (if (negated) "not " else "") + field.word
            return when (field.kind) {
                Kind.TEXT -> "$head ${textOp.word} $text"
                Kind.RANGE -> "$head ${formatRange(min, max)}"
                Kind.SET, Kind.CHOICE -> "$head ${values.joinToString(", ")}"
                Kind.DAYS -> "$head within $number"
                Kind.PLAYED -> if (playedOp == PlayedOp.NEVER) "$head never" else "$head ${playedOp.word} $number"
                Kind.COUNT -> "$head at-least $number"
                Kind.FLAG -> head
            }
        }
    }

    /** How the tracks are put in order. */
    enum class Order(val word: String) {
        AS_FOUND("found"), RANDOM("random"), ARTIST("artist"), ALBUM("album"), YEAR("year"),
        ADDED("added"), MOST_PLAYED("most-played"), LAST_PLAYED("last-played"), CHAINED("chained"),
    }

    data class Rules(val conditions: List<Condition>, val order: Order = Order.AS_FOUND, val limit: Int? = null)

    /** What the conditions need to know about each track; unknown values are null. */
    class Track(
        val path: String,
        val tags: SortTags,
        val modifiedMs: Long,
        val plays: Int = 0,
        val skips: Int = 0,
        val lastPlayedMs: Long = 0,
        val mood: String? = null,
        val bpm: Double? = null,
        /** Percentiles in the collection, 0 … 1. */
        val energy: Double? = null,
        val positivity: Double? = null,
        /** True with voice, false instrumental. */
        val voice: Boolean? = null,
        val included: Boolean = true,
    )

    fun rulesFileOf(list: File): File = File(list.parentFile, list.nameWithoutExtension + ".smart.txt")

    fun parse(text: String): Rules {
        val conditions = ArrayList<Condition>()
        var order = Order.AS_FOUND
        var limit: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val words = line.split(Regex("\\s+"), limit = 2)
            when (words[0].lowercase()) {
                "order" -> order = Order.entries.firstOrNull { it.word == words.getOrNull(1)?.trim()?.lowercase() } ?: order
                "limit" -> limit = words.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                else -> parseCondition(line)?.let(conditions::add)
            }
        }
        return Rules(conditions, order, limit)
    }

    private fun parseCondition(line: String): Condition? {
        var rest = line
        var negated = false
        if (rest.startsWith("not ", ignoreCase = true)) {
            negated = true
            rest = rest.substring(4).trim()
        }
        val word = rest.substringBefore(' ').lowercase()
        val field = Field.entries.firstOrNull { it.word == word } ?: return null
        val value = rest.substringAfter(' ', "").trim()
        return when (field.kind) {
            Kind.TEXT -> {
                val opWord = value.substringBefore(' ').lowercase()
                val op = TextOp.entries.firstOrNull { it.word == opWord }
                val text = if (op != null) value.substringAfter(' ', "").trim() else value
                if (text.isEmpty()) null else Condition(field, negated, textOp = op ?: TextOp.CONTAINS, text = text)
            }
            Kind.RANGE -> parseRange(value)?.let { (min, max) -> Condition(field, negated, min = min, max = max) }
            Kind.SET, Kind.CHOICE -> value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                .takeIf { it.isNotEmpty() }?.let { Condition(field, negated, values = it) }
            Kind.DAYS -> value.substringAfter("within").trim().toIntOrNull()?.let { Condition(field, negated, number = it) }
            Kind.PLAYED -> {
                val opWord = value.substringBefore(' ').lowercase()
                val op = PlayedOp.entries.firstOrNull { it.word == opWord } ?: return null
                val n = value.substringAfter(' ', "").trim().toIntOrNull() ?: 0
                if (op != PlayedOp.NEVER && n <= 0) null else Condition(field, negated, playedOp = op, number = n)
            }
            Kind.COUNT -> value.substringAfter("at-least").trim().toIntOrNull()?.let { Condition(field, negated, number = it) }
            Kind.FLAG -> Condition(field, negated)
        }
    }

    /** "1990-1999", "1990-" (from), "-1999" (up to) or "1995" (just that). */
    private fun parseRange(text: String): Pair<Double?, Double?>? {
        val t = text.replace(',', '.').trim()
        if (t.isEmpty()) return null
        if (!t.contains('-')) return t.toDoubleOrNull()?.let { it to it }
        val min = t.substringBefore('-').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val max = t.substringAfter('-').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return if (min == null && max == null) null else min to max
    }

    private fun formatRange(min: Double?, max: Double?): String {
        fun n(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        return when {
            min != null && max != null && min == max -> n(min)
            else -> (min?.let(::n) ?: "") + "-" + (max?.let(::n) ?: "")
        }
    }

    fun format(rules: Rules): String = buildString {
        append(HEADER)
        for (c in rules.conditions) append(c.format()).append('\n')
        if (rules.order != Order.AS_FOUND) append("order ").append(rules.order.word).append('\n')
        rules.limit?.let { append("limit ").append(it).append('\n') }
    }

    /** Whether [t] meets [c] at the moment [nowMs]. */
    fun matches(c: Condition, t: Track, nowMs: Long): Boolean = c.negated != when (c.field) {
        Field.ARTIST -> textMatch(c, t.tags.artist)
        Field.ALBUM_ARTIST -> textMatch(c, t.tags.albumArtist.ifBlank { t.tags.artist })
        Field.ALBUM -> textMatch(c, t.tags.album)
        Field.TITLE -> textMatch(c, t.tags.title.ifBlank { File(t.path).nameWithoutExtension })
        Field.GENRE -> textMatch(c, t.tags.genre)
        Field.FOLDER -> {
            // A folder takes in its subfolders: by path, not by name.
            val folder = t.path.substringBeforeLast('/')
            val wanted = c.text.trimEnd('/')
            if (c.textOp == TextOp.IS) folder == wanted || folder.startsWith("$wanted/") else textMatch(c, folder)
        }
        Field.PATH -> textMatch(c, t.path)
        Field.YEAR -> t.tags.year.takeIf { it > 0 }?.let { inRange(c, it.toDouble()) } ?: false
        Field.LENGTH -> t.tags.durationMs.takeIf { it > 0 }?.let { inRange(c, it / 60_000.0) } ?: false
        Field.BPM -> t.bpm?.takeIf { it > 0 }?.let { inRange(c, it) } ?: false
        Field.ENERGY -> t.energy?.let { inRange(c, it * 100) } ?: false
        Field.POSITIVITY -> t.positivity?.let { inRange(c, it * 100) } ?: false
        Field.MOOD -> t.mood?.lowercase()?.let { it in c.values } ?: false
        Field.VOICE -> t.voice?.let { if (it) "voice" in c.values else "instrumental" in c.values } ?: false
        Field.ADDED -> t.modifiedMs > 0 && nowMs - t.modifiedMs <= c.number * DAY_MS
        Field.PLAYED -> when (c.playedOp) {
            PlayedOp.NEVER -> t.plays == 0
            PlayedOp.WITHIN -> t.lastPlayedMs > 0 && nowMs - t.lastPlayedMs <= c.number * DAY_MS
            PlayedOp.NOT_WITHIN -> t.lastPlayedMs == 0L || nowMs - t.lastPlayedMs > c.number * DAY_MS
            PlayedOp.AT_LEAST -> t.plays >= c.number
        }
        Field.SKIPPED -> t.skips >= c.number
        Field.INCLUDED -> t.included
    }

    private fun textMatch(c: Condition, value: String): Boolean {
        val v = value.trim()
        return when (c.textOp) {
            TextOp.IS -> v.equals(c.text.trim(), ignoreCase = true)
            TextOp.CONTAINS -> v.contains(c.text.trim(), ignoreCase = true)
            TextOp.STARTS -> v.startsWith(c.text.trim(), ignoreCase = true)
        }
    }

    private fun inRange(c: Condition, v: Double): Boolean = (c.min == null || v >= c.min) && (c.max == null || v <= c.max)

    /**
     * The tracks meeting every condition, in the order asked for (except [Order.CHAINED], which
     * the caller does, as it needs the analysis: they come out as found) and cut to the limit.
     * Random orders take [random].
     */
    fun evaluate(rules: Rules, tracks: List<Track>, nowMs: Long, random: kotlin.random.Random = kotlin.random.Random.Default): List<Track> {
        val found = tracks.filter { t -> rules.conditions.all { matches(it, t, nowMs) } }
        val ordered = when (rules.order) {
            Order.AS_FOUND, Order.CHAINED -> found
            Order.RANDOM -> found.shuffled(random)
            Order.ARTIST -> found.sortedWith(compareBy<Track> { it.tags.albumArtist.ifBlank { it.tags.artist }.lowercase() }.thenBy { it.tags.year }.thenBy { it.tags.album.lowercase() }.thenBy { it.tags.disc }.thenBy { it.tags.track })
            Order.ALBUM -> found.sortedWith(compareBy<Track> { it.tags.album.lowercase() }.thenBy { it.tags.disc }.thenBy { it.tags.track })
            Order.YEAR -> found.sortedWith(compareBy<Track> { it.tags.year }.thenBy { it.tags.album.lowercase() }.thenBy { it.tags.disc }.thenBy { it.tags.track })
            Order.ADDED -> found.sortedByDescending { it.modifiedMs }
            Order.MOST_PLAYED -> found.sortedWith(compareByDescending<Track> { it.plays }.thenByDescending { it.lastPlayedMs })
            Order.LAST_PLAYED -> found.sortedByDescending { it.lastPlayedMs }
        }
        // A random or chained list with a limit takes its tracks at random, not the first found.
        val pool = if (rules.order == Order.CHAINED && rules.limit != null) ordered.shuffled(random) else ordered
        return rules.limit?.let { pool.take(it) } ?: pool
    }

    private const val DAY_MS = 86_400_000L

    private const val HEADER = "# Smart list: the list next to this file is made again from these lines after each\n" +
        "# library scan and when it is opened. Every condition must be met; \"not\" turns one round.\n" +
        "# Text: artist, albumartist, album, title, genre, folder, path + is/contains/starts <text>.\n" +
        "# Ranges (a-b, a-, -b): year, length (minutes), bpm, energy, positivity (0-100).\n" +
        "# mood <names>; voice voice|instrumental; added within <days>; played never |\n" +
        "# within <days> | not-within <days> | at-least <times>; skipped at-least <times>; included.\n" +
        "# order found|random|artist|album|year|added|most-played|last-played|chained; limit <n>.\n"
}
