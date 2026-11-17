package com.sadurni.attaccaplg

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The MusicBrainz release type each album of a real music folder carries (LIVE_MUSIC=<folder>),
 * written to build/release-types.txt: album folder, how many tracks, the values found.
 */
class ReleaseTypeRealTest {
    @Test
    fun listTheTypes() {
        val root = System.getenv("LIVE_MUSIC")?.let(::File)
        assumeTrue(root != null && root.isDirectory)
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        val audio = setOf("mp3", "flac", "ogg", "m4a")
        val byFolder = root!!.walkTopDown().filter { it.isFile && it.extension.lowercase() in audio }.groupBy { it.parentFile }
        val out = StringBuilder()
        for ((folder, files) in byFolder.toSortedMap(compareBy { it.path })) {
            val values = files.map { f ->
                runCatching {
                    val tag = AudioFileIO.read(f).tag
                    listOf(FieldKey.MUSICBRAINZ_RELEASE_TYPE).flatMap { k -> runCatching { tag.getAll(k) }.getOrDefault(emptyList()) }
                        .plus(runCatching { tag.getFirst("RELEASETYPE") }.getOrDefault(""))
                        .plus(runCatching { tag.getFirst("MusicBrainz Album Type") }.getOrDefault(""))
                        .filter { it.isNotBlank() }.joinToString("/").lowercase()
                }.getOrDefault("?")
            }.groupingBy { it.ifBlank { "-" } }.eachCount()
            out.append(folder.path.removePrefix(root.path)).append("  ").append(files.size).append("  ").append(values).append('\n')
        }
        File("build/release-types.txt").writeText(out.toString())
    }
}
