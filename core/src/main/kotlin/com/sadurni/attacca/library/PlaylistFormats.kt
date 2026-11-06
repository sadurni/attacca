package com.sadurni.attacca.library

import java.io.File

/** Reads any supported playlist file, by extension. */
object PlaylistFiles {
    fun parse(file: File): List<PlaylistEntry> = when (file.extension.lowercase()) {
        "pls" -> PlsParser.parse(file)
        "cue" -> CueParser.parse(file)
        "xspf" -> XspfParser.parse(file)
        else -> M3uParser.parse(file)
    }
}

/** The formats a list can be saved in. */
enum class PlaylistFormat(val extension: String, val mimeType: String) {
    M3U8("m3u8", "audio/x-mpegurl"),
    PLS("pls", "audio/x-scpls"),
    XSPF("xspf", "application/xspf+xml"),
}

/** Writes lists as PLS or XSPF (M3U8: [M3uParser.format]), paths relative to [baseDir] where they can be. */
object PlaylistWriter {

    fun format(entries: List<PlaylistEntry>, format: PlaylistFormat, baseDir: File?): String = when (format) {
        PlaylistFormat.M3U8 -> M3uParser.format(entries, baseDir)
        PlaylistFormat.PLS -> pls(entries, baseDir)
        PlaylistFormat.XSPF -> xspf(entries, baseDir)
    }

    private fun located(entry: PlaylistEntry, baseDir: File?): String =
        baseDir?.let { M3uParser.relativeLocation(entry.location, it) } ?: entry.location

    private fun pls(entries: List<PlaylistEntry>, baseDir: File?): String = buildString {
        append("[playlist]\n")
        entries.forEachIndexed { i, entry ->
            val n = i + 1
            append("File").append(n).append('=').append(located(entry, baseDir)).append('\n')
            entry.title?.let { append("Title").append(n).append('=').append(it).append('\n') }
        }
        append("NumberOfEntries=").append(entries.size).append('\n')
        append("Version=2\n")
    }

    private fun xspf(entries: List<PlaylistEntry>, baseDir: File?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n  <trackList>\n")
        for (entry in entries) {
            append("    <track>\n      <location>").append(escape(XspfParser.toUri(located(entry, baseDir)))).append("</location>\n")
            entry.title?.let { append("      <title>").append(escape(it)).append("</title>\n") }
            append("    </track>\n")
        }
        append("  </trackList>\n</playlist>\n")
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** XSPF playlists: `<track>` elements with a `<location>` (a URI) and an optional `<title>`. */
object XspfParser {

    fun parse(file: File): List<PlaylistEntry> = parse(M3uParser.decode(file.readBytes()), file.parentFile)

    fun parse(text: String, baseDir: File?): List<PlaylistEntry> {
        val tracks = Regex("<track\\b[^>]*>(.*?)</track>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(text)
        return tracks.mapNotNull { match ->
            val body = match.groupValues[1]
            val location = element(body, "location") ?: return@mapNotNull null
            val title = element(body, "title")
            M3uParser.parse(fromUri(location), baseDir).firstOrNull()?.copy(title = title)
        }.toList()
    }

    private fun element(body: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(body)?.groupValues?.get(1)?.trim()?.let(::unescape)?.ifEmpty { null }

    private fun unescape(text: String): String = text
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** A file URI (or a relative one) back to a path; web addresses as they are. */
    fun fromUri(location: String): String {
        val path = when {
            location.startsWith("file://", ignoreCase = true) -> location.substring(7).let { if (it.startsWith("localhost/")) it.substring(9) else it }
            location.contains("://") -> return location
            else -> location
        }
        return percentDecode(path)
    }

    /** A path as XSPF writes it: absolute ones as file URIs, everything percent-encoded. */
    fun toUri(location: String): String = when {
        location.contains("://") -> location
        location.startsWith("/") -> "file://" + percentEncode(location)
        else -> percentEncode(location)
    }

    private fun percentEncode(path: String): String = buildString {
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~/") append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }

    private fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }
}

/** PLS playlists: `FileN=`, `TitleN=` entries in a `[playlist]` section. */
object PlsParser {

    fun parse(file: File): List<PlaylistEntry> = parse(M3uParser.decode(file.readBytes()), file.parentFile)

    fun parse(text: String, baseDir: File?): List<PlaylistEntry> {
        val files = sortedMapOf<Int, String>()
        val titles = HashMap<Int, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when {
                key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.let { files[it] = value }
                key.startsWith("title") -> key.removePrefix("title").toIntOrNull()?.let { titles[it] = value }
            }
        }
        // Reuse the M3U path resolution (relative, Windows, URLs) for each entry.
        return files.mapNotNull { (n, location) ->
            M3uParser.parse(location, baseDir).firstOrNull()?.copy(title = titles[n])
        }
    }
}

/**
 * CUE sheets: one audio file (or several) split into tracks by INDEX 01 times. Each track becomes
 * a clipped entry of its file; a track ends where the next one in the same file starts.
 */
object CueParser {

    fun parse(file: File): List<PlaylistEntry> = parse(M3uParser.decode(file.readBytes()), file.parentFile)

    fun parse(text: String, baseDir: File?): List<PlaylistEntry> {
        data class Track(val file: String, val title: String?, val performer: String?, val startMs: Long)

        val tracks = ArrayList<Track>()
        var albumPerformer: String? = null
        var currentFile: String? = null
        var title: String? = null
        var performer: String? = null
        var inTrack = false

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            val command = line.substringBefore(' ').uppercase()
            val args = line.substringAfter(' ', "")
            when (command) {
                "FILE" -> {
                    currentFile = unquote(args.substringBeforeLast(' ').ifEmpty { args })
                    inTrack = false
                }
                "TRACK" -> {
                    inTrack = true
                    title = null
                    performer = null
                }
                "TITLE" -> if (inTrack) title = unquote(args)
                "PERFORMER" -> if (inTrack) performer = unquote(args) else albumPerformer = unquote(args)
                "INDEX" -> {
                    val parts = args.trim().split(Regex("\\s+"))
                    if (inTrack && parts.size == 2 && parts[0] == "01" && currentFile != null) {
                        val start = parseTime(parts[1]) ?: continue
                        tracks.add(Track(currentFile!!, title, performer ?: albumPerformer, start))
                    }
                }
            }
        }

        return tracks.mapIndexedNotNull { i, track ->
            val next = tracks.getOrNull(i + 1)?.takeIf { it.file == track.file }
            val location = M3uParser.parse(track.file, baseDir).firstOrNull()?.location ?: return@mapIndexedNotNull null
            val display = listOfNotNull(track.performer, track.title).joinToString(" - ").ifEmpty { null }
            PlaylistEntry(
                location = location,
                title = display,
                startMs = track.startMs.takeIf { it > 0 } ?: if (next != null) 0L else null,
                endMs = next?.startMs,
            )
        }
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")

    /** mm:ss:ff with 75 frames per second. */
    fun parseTime(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        return minutes * 60_000 + seconds * 1000 + frames * 1000 / 75
    }
}

/** Lyrics, plain or time-synced (LRC). */
data class Lyrics(val lines: List<Line>, val synced: Boolean) {
    data class Line(val timeMs: Long, val text: String)

    /** Index of the line being sung at [positionMs], or -1. */
    fun currentLine(positionMs: Long): Int {
        if (!synced) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) index = i else break
        }
        return index
    }

    companion object {
        private val TIME_TAG = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
        private val META_TAG = Regex("^\\[[a-zA-Z]+:.*]$")

        /** Parses LRC if it has time tags, plain text otherwise. */
        fun parse(text: String): Lyrics? {
            val synced = ArrayList<Line>()
            val plain = ArrayList<Line>()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                val tags = TIME_TAG.findAll(line).toList()
                if (tags.isEmpty()) {
                    if (!META_TAG.matches(line.trim())) plain.add(Line(0, line))
                    continue
                }
                val lyric = line.substring(tags.last().range.last + 1).trim()
                for (tag in tags) {
                    val (min, sec, frac) = tag.destructured
                    val fraction = if (frac.isEmpty()) 0L else (("0.$frac").toDouble() * 1000).toLong()
                    synced.add(Line(min.toLong() * 60_000 + sec.toLong() * 1000 + fraction, lyric))
                }
            }
            return when {
                synced.isNotEmpty() -> Lyrics(synced.sortedBy { it.timeMs }, synced = true)
                plain.any { it.text.isNotBlank() } -> Lyrics(plain.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }, synced = false)
                else -> null
            }
        }
    }
}
