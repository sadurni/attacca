package com.sadurni.attaccaplg.generate

import com.sadurni.attacca.library.Inclusion
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attacca.library.InclusionRules
import com.sadurni.attacca.library.PathMatcher
import com.sadurni.attacca.library.PlaylistFiles
import java.io.File
import java.sql.DriverManager
import javax.xml.parsers.DocumentBuilderFactory

/** Reading playlists from anywhere, and which tracks lists may use. */
object Inclusions {

    /** The entries of a playlist file (M3U/M3U8, PLS, CUE, XSPF): location and title, when known. */
    fun readPlaylist(file: File): List<Pair<String, String?>> =
        if (file.extension.equals("xspf", ignoreCase = true)) readXspf(file) else PlaylistFiles.parse(file).map { it.location to it.title }

    private fun readXspf(file: File): List<Pair<String, String?>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val tracks = doc.getElementsByTagNameNS("*", "track")
        return (0 until tracks.length).mapNotNull { i ->
            val track = tracks.item(i) as org.w3c.dom.Element
            fun text(tag: String) = track.getElementsByTagNameNS("*", tag).item(0)?.textContent?.trim()
            val location = text("location") ?: return@mapNotNull null
            val title = listOfNotNull(text("creator"), text("title")).takeIf { it.size == 2 }?.joinToString(" - ")
            location to title
        }
    }

    /**
     * An inclusion list read as library paths: an M3U8 written anywhere (Attacca's on the phone, one
     * made here), or Attacca's rules file (`<list>.rules.txt`), worked out again each time so its
     * folders, albums and artists take in what the library has now. [mode] allows only its tracks,
     * or all but them.
     */
    fun inclusion(list: File, mode: InclusionMode, library: Library): Inclusion =
        Inclusion(mode) {
            if (isRulesFile(list)) {
                fromRules(list, library)
            } else {
                readPlaylist(list).mapNotNull { (location, _) -> library.matcher.match(location) }
            }
        }

    /** Whether [file] holds inclusion rules (Attacca's `.rules.txt`, or any text file of rules). */
    fun isRulesFile(file: File): Boolean = file.extension.equals("txt", ignoreCase = true)

    /**
     * The library tracks [rulesFile]'s rules take in. Its paths are the phone's: tracks are found as
     * playlist entries are, folders by their last names (as many as match, at least the folder's
     * own), with their subfolders; artists and albums by the tags (artist or album artist).
     */
    fun fromRules(rulesFile: File, library: Library): List<String> {
        val rules = InclusionRules.parse(rulesFile.readText(Charsets.UTF_8)).mapNotNull { rule ->
            if (rule.kind != InclusionRules.Kind.TRACK) rule else library.matcher.match(rule.value)?.let { rule.copy(value = it) }
        }
        val folders by lazy { library.all.map { it.path to PathMatcher.components(it.path).dropLast(1).map(String::lowercase) } }
        fun folderTracks(folder: String): List<String> {
            val wanted = PathMatcher.components(PathMatcher.clean(folder)).map(String::lowercase)
            for (k in wanted.size downTo 1) {
                val tail = wanted.takeLast(k)
                val found = folders.filter { (_, dirs) -> holds(dirs, tail) }.map { it.first }
                if (found.isNotEmpty()) return found
            }
            return emptyList()
        }
        val tags = object : InclusionRules.Tags {
            override fun pathsOfArtist(artist: String) = library.all.filter {
                it.tags.artist.equals(artist, ignoreCase = true) || it.tags.albumArtist.equals(artist, ignoreCase = true)
            }.map { it.path }

            override fun pathsOfAlbum(artist: String, album: String) = library.all.filter {
                it.tags.album.equals(album, ignoreCase = true) &&
                    (it.tags.artist.equals(artist, ignoreCase = true) || it.tags.albumArtist.equals(artist, ignoreCase = true))
            }.map { it.path }
        }
        return InclusionRules.compute(rules, tags, ::folderTracks)
    }

    /** Whether the folder names [dirs] hold [run] in a row (that folder, or one above the track's). */
    private fun holds(dirs: List<String>, run: List<String>): Boolean {
        if (run.size > dirs.size) return false
        for (start in 0..dirs.size - run.size) {
            if ((run.indices).all { dirs[start + it] == run[it] }) return true
        }
        return false
    }

    /** What [importBeets] found. */
    class BeetsImport(val included: List<String>, val excluded: Int, val unmatched: Int)

    /**
     * The tracks beets marked `include` (a flexible attribute: 1, true, yes, sí… or a non-zero
     * number), as library paths. Read once, to carry the old marks over.
     */
    fun importBeets(beetsDb: File, library: Library): BeetsImport {
        val included = ArrayList<String>()
        var excluded = 0
        var unmatched = 0
        DriverManager.getConnection("jdbc:sqlite:${beetsDb.absolutePath}").use { c ->
            c.createStatement().executeQuery(
                "SELECT i.path, a.value FROM items i JOIN item_attributes a ON a.entity_id = i.id AND a.key = 'include'",
            ).use { rs ->
                while (rs.next()) {
                    val raw = rs.getBytes(1) ?: continue
                    val path = library.matcher.match(String(raw, Charsets.UTF_8))
                    when {
                        path == null -> unmatched++
                        truthy(rs.getString(2)) -> included += path
                        else -> excluded++
                    }
                }
            }
        }
        return BeetsImport(included.distinct(), excluded, unmatched)
    }

    fun truthy(value: String?): Boolean {
        val v = value?.trim()?.lowercase() ?: return false
        if (v in setOf("1", "true", "yes", "y", "t", "si", "sí", "on")) return true
        return v.toDoubleOrNull()?.let { it != 0.0 } ?: false
    }

    /** Writes [paths] as an M3U8 inclusion list (library paths, as Attacca's lists hold paths). */
    fun writeList(paths: List<String>, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText((listOf("#EXTM3U") + paths).joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
