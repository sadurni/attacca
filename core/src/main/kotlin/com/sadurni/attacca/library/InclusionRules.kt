package com.sadurni.attacca.library

import java.io.File

/**
 * Rules that build the inclusion list: tracks, folders (with their subfolders), albums and
 * artists added (+) or taken out (-, which wins). They live in a plain text file next to the
 * list (`<list>.rules.txt`), one rule per line, so they can be edited by hand:
 *
 * ```
 * + folder /storage/emulated/0/Music/Jazz
 * + artist Miles Davis
 * + album AC/DC :: Back in Black
 * - track /storage/emulated/0/Music/Jazz/intro.mp3
 * ```
 *
 * The list itself is computed from them, so folders, albums and artists also take in tracks
 * added later.
 */
object InclusionRules {

    enum class Kind(val word: String) { TRACK("track"), FOLDER("folder"), ALBUM("album"), ARTIST("artist") }

    data class Rule(val include: Boolean, val kind: Kind, val value: String) {
        fun format(): String = "${if (include) "+" else "-"} ${kind.word} $value"
    }

    /** Album rules name the album with its artist: "artist :: album". */
    const val ALBUM_SEPARATOR = " :: "

    /** The tag database's answers for album and artist rules. */
    interface Tags {
        fun pathsOfArtist(artist: String): List<String>
        fun pathsOfAlbum(artist: String, album: String): List<String>
    }

    fun rulesFileOf(list: File): File = File(list.parentFile, list.nameWithoutExtension + ".rules.txt")

    fun parse(text: String): List<Rule> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val include = when (line[0]) {
            '+' -> true
            '-' -> false
            else -> return@mapNotNull null
        }
        val rest = line.substring(1).trim()
        val word = rest.substringBefore(' ')
        val kind = Kind.entries.firstOrNull { it.word == word } ?: return@mapNotNull null
        val value = rest.substringAfter(' ', "").trim()
        if (value.isEmpty()) null else Rule(include, kind, value)
    }.toList()

    fun format(rules: List<Rule>): String = buildString {
        append(HEADER)
        for (rule in rules) append(rule.format()).append('\n')
    }

    /**
     * The tracks of [rules], in the order the rules give them: everything the "+" rules take in,
     * minus everything the "-" rules take out. [folderTracks] lists the audio files of a folder
     * and its subfolders.
     */
    fun compute(rules: List<Rule>, tags: Tags, folderTracks: (String) -> List<String>): List<String> {
        fun expand(rule: Rule): List<String> = when (rule.kind) {
            Kind.TRACK -> listOf(rule.value)
            Kind.FOLDER -> folderTracks(rule.value)
            Kind.ARTIST -> tags.pathsOfArtist(rule.value)
            Kind.ALBUM -> tags.pathsOfAlbum(rule.value.substringBefore(ALBUM_SEPARATOR), rule.value.substringAfter(ALBUM_SEPARATOR, ""))
        }.map(Inclusion::normalize)

        val result = LinkedHashSet<String>()
        for (rule in rules) if (rule.include) result.addAll(expand(rule))
        for (rule in rules) if (!rule.include) result.removeAll(expand(rule).toSet())
        return result.toList()
    }

    /**
     * Rules with [target] added ([add]) or taken out: its opposite rule goes away and its own
     * rule is added; taking out a target that was added as such just drops that rule, unless
     * other rules still take its tracks in.
     */
    fun change(rules: List<Rule>, target: Rule, add: Boolean, stillIncluded: (List<Rule>) -> Boolean): List<Rule> {
        val same = { r: Rule -> r.kind == target.kind && r.value == target.value }
        val others = rules.filterNot(same)
        if (add) return others + target.copy(include = true)
        val withoutIt = others
        return if (stillIncluded(withoutIt)) withoutIt + target.copy(include = false) else withoutIt
    }

    /**
     * Rules with all [targets] added ([add]) or taken out at once, as [change] does one by one:
     * the targets' own rules go away; added ones come back as "+"; taken out ones come back as
     * "-" only if the remaining rules still take some of their tracks in. [compute] gives the
     * tracks of some rules.
     */
    fun changeAll(rules: List<Rule>, targets: List<Rule>, add: Boolean, compute: (List<Rule>) -> List<String>): List<Rule> {
        val keys = targets.mapTo(HashSet()) { it.kind to it.value }
        val others = rules.filterNot { (it.kind to it.value) in keys }
        val unique = targets.distinctBy { it.kind to it.value }
        if (add) return others + unique.map { it.copy(include = true) }
        val remaining = compute(others).toHashSet()
        return others + unique
            .filter { t -> compute(listOf(t.copy(include = true))).any { it in remaining } }
            .map { it.copy(include = false) }
    }

    private const val HEADER = "# Inclusion rules. The list next to this file is computed from them.\n" +
        "# + takes tracks in, - takes them out (and wins). Kinds: track <path>, folder <path> (with\n" +
        "# its subfolders), album <artist> :: <album>, artist <name>.\n"
}
