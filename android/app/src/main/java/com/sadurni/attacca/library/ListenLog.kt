package com.sadurni.attacca.library

import com.sadurni.attacca.playback.Listen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tracks played, appended to a text file in the standard portable-player scrobbling format
 * (tab-separated: artist, album, title, track number, length in seconds, L = listened or
 * S = skipped, start time in seconds since 1970 UTC, recording id). Scrobbling tools on a computer
 * read it and send it on, so Attacca needs no Internet access.
 */
object ListenLog {

    val file: File get() = File(UserLists.directory, "scrobbler.log")

    /** Tracks shorter than this are never logged (they would be rejected anyway). */
    private const val MIN_LENGTH_MS = 30_000L

    /** Appends one line. Blocking (may read the track's tags). */
    @Synchronized
    fun append(listen: Listen, listened: Boolean, clientVersion: String) {
        if (listen.mediaId.contains("://")) return
        val tags = TrackSort.tags(listen.mediaId)
        val lengthMs = tags.durationMs.takeIf { it > 0 } ?: listen.durationMs
        if (lengthMs < MIN_LENGTH_MS) return
        // Without an artist no service takes the entry.
        if (tags.artist.isBlank()) return
        val title = tags.title.ifBlank { File(listen.mediaId).nameWithoutExtension }
        val line = listOf(
            tags.artist,
            tags.album,
            title,
            if (tags.track > 0) tags.track.toString() else "",
            (lengthMs / 1000).toString(),
            if (listened) "L" else "S",
            (listen.startedAtMs / 1000).toString(),
            "",
        ).joinToString("\t") { clean(it) }
        val target = file
        target.parentFile?.mkdirs()
        if (!target.exists() || target.length() == 0L) {
            target.writeText("#AUDIOSCROBBLER/1.1\n#TZ/UTC\n#CLIENT/Attacca $clientVersion\n", Charsets.UTF_8)
        }
        target.appendText(line + "\n", Charsets.UTF_8)
    }

    /** Entries in the log now (header lines not counted). */
    fun entries(): Int =
        if (!file.exists()) 0 else file.useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }

    /**
     * Puts the current log aside under a dated name (once it has been sent) so a new one starts.
     * Returns the file it became, or null when there was nothing to put aside.
     */
    @Synchronized
    fun startNew(): File? {
        val current = file
        if (!current.exists()) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())
        var target = File(current.parentFile, "scrobbler-$stamp.log")
        var n = 2
        while (target.exists()) target = File(current.parentFile, "scrobbler-$stamp-${n++}.log")
        return target.takeIf { current.renameTo(it) }
    }

    private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
