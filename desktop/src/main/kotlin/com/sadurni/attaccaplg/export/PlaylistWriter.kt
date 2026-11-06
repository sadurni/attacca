package com.sadurni.attaccaplg.export

import com.sadurni.attaccaplg.generate.Playlist
import com.sadurni.attaccaplg.library.StoredTrack
import java.io.File
import java.util.Locale

/** The playlist formats, as beetsPlaylist wrote them. */
enum class PlaylistFormat(val extension: String) {
    /** M3U8 with `#EXTINF` and `#EXTVLCOPT:start-time/stop-time` (the silence at the edges skipped). */
    M3U8("m3u8"),

    /** XSPF with the AIMP (`<start>`, `<duration>`) and VLC (`start-time`, `stop-time`) extensions. */
    XSPF("xspf"),

    /** Plain M3U for Rockbox: no start/stop options (it has none). */
    ROCKBOX("m3u"),
}

/**
 * Where the files are on the device that plays the list: the library's relative paths joined to
 * [base] ("/storage/emulated/0/Music", "/<microSD1>/Music", "D:\Music"…), or the PC's own paths
 * when there is no base.
 */
class PathMapping(private val musicRoot: File?, private val base: String?) {
    fun location(path: String): String {
        // Without the music here and no device path: the path within the music folder.
        if (base.isNullOrBlank()) return musicRoot?.let { File(it, path).absolutePath } ?: path
        val windows = base.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(base)
        val separator = if (windows) "\\" else "/"
        return base.trimEnd('/', '\\') + separator + path.replace("/", separator)
    }
}

object PlaylistWriter {

    /** Writes [playlist] into [folder]; returns the file. */
    fun write(playlist: Playlist, tracks: Map<String, StoredTrack>, format: PlaylistFormat, mapping: PathMapping, folder: File): File {
        folder.mkdirs()
        val file = File(folder, fileName(playlist.name, format))
        file.writeText(content(playlist.paths.mapNotNull(tracks::get), format, mapping), Charsets.UTF_8)
        return file
    }

    /** A file name of letters, digits, `_`, `.` and `-` only (any player, any file system). */
    fun fileName(name: String, format: PlaylistFormat): String {
        val cleaned = name.replace(Regex("\\.(m3u8?|xspf)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "playlist" }
        return "$cleaned.${format.extension}"
    }

    fun content(tracks: List<StoredTrack>, format: PlaylistFormat, mapping: PathMapping): String {
        val lines = ArrayList<String>()
        if (format == PlaylistFormat.XSPF) {
            lines += "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            lines += "<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\" xmlns:vlc=\"http://www.videolan.org/vlc/playlist/ns/0/\">"
            lines += "  <trackList>"
            for (t in tracks) {
                lines += "    <track>"
                lines += "      <location>${fileUri(mapping.location(t.path))}</location>"
                lines += "      <creator>${xml(t.tags.artist)}</creator>"
                lines += "      <title>${xml(t.tags.title)}</title>"
                lines += "      <duration>${t.durationMs}</duration>"
                val start = startSeconds(t)
                val stop = stopSeconds(t)
                if (start != null || stop != null) {
                    val a = start ?: 0.0
                    val b = stop ?: (t.durationMs / 1000.0)
                    lines += "      <extension application=\"http://www.aimp.ru/\">"
                    lines += "        <start>${sec(a)}</start>"
                    lines += "        <duration>${sec(b - a)}</duration>"
                    lines += "      </extension>"
                    lines += "      <extension application=\"http://www.videolan.org/vlc/playlist/0\">"
                    if (start != null) lines += "        <vlc:option>start-time=${sec(start)}</vlc:option>"
                    if (stop != null) lines += "        <vlc:option>stop-time=${sec(stop)}</vlc:option>"
                    lines += "      </extension>"
                }
                lines += "    </track>"
            }
            lines += "  </trackList>"
            lines += "</playlist>"
        } else {
            lines += "#EXTM3U"
            for (t in tracks) {
                lines += "#EXTINF:${t.durationMs / 1000},${t.tags.artist} - ${t.tags.title}"
                if (format == PlaylistFormat.M3U8) {
                    startSeconds(t)?.let { lines += "#EXTVLCOPT:start-time=${sec(it)}" }
                    stopSeconds(t)?.let { lines += "#EXTVLCOPT:stop-time=${sec(it)}" }
                }
                lines += mapping.location(t.path)
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Where the sound starts, when there is silence worth skipping (over 0.1 s). */
    private fun startSeconds(t: StoredTrack): Double? = (t.audioStartMs / 1000.0).takeIf { it > MIN_SKIP_S }

    /** Where the sound ends, when the silence after it is worth skipping. */
    private fun stopSeconds(t: StoredTrack): Double? {
        if (t.durationMs <= 0 || t.audioEndMs <= 0) return null
        return (t.audioEndMs / 1000.0).takeIf { (t.durationMs - t.audioEndMs) / 1000.0 > MIN_SKIP_S }
    }

    private const val MIN_SKIP_S = 0.1

    private fun sec(x: Double): String {
        val r = Math.round(x * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(Locale.ROOT, "%s", r)
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun fileUri(path: String): String {
        val p = path.replace('\\', '/').let { if (it.startsWith("/")) it else "/$it" }
        val out = StringBuilder("file://")
        for (b in p.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            // The drive's colon stays as it is (file:///C:/…), as players expect.
            if (b >= 0 && (c.isLetterOrDigit() && c.code < 128 || c in "/._~-:")) out.append(c) else out.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return xml(out.toString())
    }
}
