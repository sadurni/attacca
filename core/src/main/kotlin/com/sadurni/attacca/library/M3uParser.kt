package com.sadurni.attacca.library

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class PlaylistEntry(
    /** Absolute file path or http(s) URL. */
    val location: String,
    /** Title from #EXTINF, if present. */
    val title: String? = null,
    /** Trim points from #EXTVLCOPT:start-time / stop-time (VLC playlist options). */
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** Parses M3U / M3U8 playlists, including ones written on Windows (backslashes, ANSI). */
object M3uParser {

    /**
     * The user's music folder, if set. Lists from another device or computer keep working when
     * the music below it has the same structure: entries not found where the list says are
     * looked up below this folder, and exports are written relative to it.
     */
    @Volatile
    var musicFolder: File? = null

    fun parse(file: File): List<PlaylistEntry> =
        parse(decode(file.readBytes()), file.parentFile)

    fun parse(text: String, baseDir: File?): List<PlaylistEntry> {
        val entries = ArrayList<PlaylistEntry>()
        var pendingTitle: String? = null
        var pendingStart: Long? = null
        var pendingEnd: Long? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) ->
                    pendingTitle = line.substringAfter(',', "").trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:start-time=", ignoreCase = true) ->
                    pendingStart = parseSecondsMs(line.substringAfter('='))
                line.startsWith("#EXTVLCOPT:stop-time=", ignoreCase = true) ->
                    pendingEnd = parseSecondsMs(line.substringAfter('='))
                line.startsWith("#") -> Unit
                else -> {
                    resolve(line, baseDir)?.let {
                        entries.add(PlaylistEntry(it, pendingTitle, pendingStart, pendingEnd))
                    }
                    pendingTitle = null
                    pendingStart = null
                    pendingEnd = null
                }
            }
        }
        return entries
    }

    private fun parseSecondsMs(raw: String): Long? =
        raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }

    private fun resolve(location: String, baseDir: File?): String? {
        val lower = location.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return location
        if (lower.startsWith("file:")) {
            return runCatching { File(URI(location)).absolutePath }.getOrNull()
        }
        val path = location.replace('\\', '/')
        // Windows drive letters ("D:/Music/...") cannot be resolved on the phone; keep the
        // relative tail so playlists copied together with their folders still work.
        val withoutDrive = if (path.length > 2 && path[1] == ':' && path[2] == '/') path.substring(3) else path
        val file = File(withoutDrive)
        val resolved = when {
            withoutDrive != path -> if (baseDir != null) File(baseDir, withoutDrive) else file
            withoutDrive.startsWith("/") -> file
            baseDir != null -> File(baseDir, withoutDrive)
            else -> file
        }
        val found = if (resolved.exists()) resolved else inMusicFolder(withoutDrive) ?: resolved
        return runCatching { found.canonicalPath }.getOrDefault(found.absolutePath)
    }

    /**
     * Looks for a missing entry below the music folder, dropping leading folders one at a time:
     * "/storage/1234-ABCD/Music/Artist/Album/01.flac" or "D:/Music/Artist/Album/01.flac" is found
     * as "Artist/Album/01.flac" here.
     */
    private fun inMusicFolder(path: String): File? {
        val root = musicFolder ?: return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        // At least folder + file, so a bare file name never matches a stranger's track.
        for (start in 0..parts.size - 2) {
            val candidate = File(root, parts.subList(start, parts.size).joinToString("/"))
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** UTF-8 when valid (always for .m3u8), otherwise Windows-1252 as old .m3u files use. */
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    fun write(file: File, entries: List<PlaylistEntry>) {
        // The app's own lists keep absolute paths; exports are what travel.
        file.writeText(format(entries, baseDir = null, portable = false), Charsets.UTF_8)
    }

    /**
     * M3U8 text for [entries]. With [baseDir], files on the same storage are written relative to
     * it, so the list keeps working when the whole music folder is copied elsewhere.
     */
    fun format(entries: List<PlaylistEntry>, baseDir: File? = null, portable: Boolean = true): String = buildString {
        // Inside the music folder, paths relative to the list are standard and portable; out of
        // it, paths relative to the music folder are what another device can resolve.
        val music = musicFolder.takeIf { portable }
        val base = when {
            music == null -> baseDir
            baseDir != null && isInside(baseDir, music) -> baseDir
            else -> music
        }
        append("#EXTM3U\n")
        for (entry in entries) {
            entry.title?.let { append("#EXTINF:-1,").append(it).append('\n') }
            entry.startMs?.let { append("#EXTVLCOPT:start-time=").append(it / 1000.0).append('\n') }
            entry.endMs?.let { append("#EXTVLCOPT:stop-time=").append(it / 1000.0).append('\n') }
            append(base?.let { relativeLocation(entry.location, it) } ?: entry.location).append('\n')
        }
    }

    private fun isInside(dir: File, root: File): Boolean {
        fun normalized(file: File) = file.path.replace('\\', '/').trimEnd('/') + "/"
        return normalized(dir).startsWith(normalized(root))
    }

    /**
     * [location] relative to [baseDir] when both are on the same storage volume
     * ("/storage/emulated/0", "/storage/1234-ABCD").
     */
    fun relativeLocation(location: String, baseDir: File): String? {
        if (location.contains("://") || !location.startsWith("/")) return null
        // Plain path arithmetic: the lists are about Android paths, whatever runs this.
        val target = location.split('/').filter { it.isNotEmpty() }
        val base = baseDir.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val volumeDepth = if (target.getOrNull(1) == "emulated") 3 else 2
        if (target.size <= volumeDepth || target.take(volumeDepth) != base.take(volumeDepth)) return null
        var common = 0
        while (common < base.size && common < target.size - 1 && base[common] == target[common]) common++
        return (List(base.size - common) { ".." } + target.drop(common)).joinToString("/")
    }
}
