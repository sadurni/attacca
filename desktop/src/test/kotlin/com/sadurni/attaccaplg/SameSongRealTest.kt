package com.sadurni.attaccaplg

import com.sadurni.attacca.library.RecordingMatch
import com.sadurni.attacca.library.SameSong
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.audio.PcmFormat
import com.sadurni.attaccaplg.library.TagReader
import org.jaudiotagger.audio.AudioFileIO
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The same songs found in a real music folder (SAME_SONG_MUSIC=<folder>): proposed by the tags,
 * confirmed by the sound as the app does, written to build/same-song.txt to look over by hand.
 * Skipped without the variable.
 */
class SameSongRealTest {
    @Test
    fun listTheGroups() {
        val root = System.getenv("SAME_SONG_MUSIC")?.let(::File)
        assumeTrue(root != null && root.isDirectory)
        val audio = setOf("mp3", "flac", "ogg", "m4a", "wav", "opus", "wma", "aiff")
        val files = root!!.walkTopDown().filter { it.isFile && it.extension.lowercase() in audio }.toList()
        val tracks = files.mapNotNull { f ->
            val tags = TagReader.read(f)
            val ms = runCatching { (AudioFileIO.read(f).audioHeader.preciseTrackLength * 1000).toLong() }.getOrNull() ?: return@mapNotNull null
            SameSong.Track(f.path, tags.title, tags.artist, tags.album, tags.albumArtist, ms)
        }
        val levels = HashMap<String, FloatArray?>()
        fun levelsOf(t: SameSong.Track): FloatArray? = levels.getOrPut(t.id) {
            var format: PcmFormat? = null
            var window = FloatArray(0)
            var from = 0L
            var got = 0
            var seen = 0L
            Decoder.decode(File(t.id), { f ->
                format = f
                seen = 0
                got = 0
                from = RecordingMatch.startMs(t.durationMs) * f.sampleRate / 1000
                window = FloatArray(RecordingMatch.SPAN_S * f.sampleRate * f.channels)
            }) { samples, frames ->
                val f = format!!
                for (k in 0 until frames) {
                    val at = seen + k
                    if (at >= from && got < window.size / f.channels) {
                        for (c in 0 until f.channels) window[got * f.channels + c] = samples[k * f.channels + c]
                        got++
                    }
                }
                seen += frames
            }
            format?.let { f -> RecordingMatch.envelope(window, 0, got, f.channels, f.sampleRate) }
        }
        val likeness = HashMap<Pair<String, String>, Float>()
        val same = { a: SameSong.Track, b: SameSong.Track ->
            val la = levelsOf(a)
            val lb = levelsOf(b)
            val l = if (la == null || lb == null) Float.NaN else maxOf(RecordingMatch.likeness(la, lb), RecordingMatch.likeness(lb, la))
            likeness[a.id to b.id] = l
            l >= RecordingMatch.SAME
        }
        val groups = SameSong.groups(tracks)
        val out = StringBuilder()
        var dropped = 0
        for (g in groups.sortedBy { SameSong.keyOf(it[0]) }) {
            out.append(SameSong.keyOf(g[0])).append('\n')
            for (part in SameSong.confirmed(g, { File(it).length() }, same)) {
                for ((i, t) in part.withIndex()) {
                    val kbps = if (t.durationMs > 0) File(t.id).length() * 8 / t.durationMs else 0
                    val l = if (i == 0) "" else "  like %.2f".format(likeness[part[0].id to t.id] ?: Float.NaN)
                    if (part.size > 1 && i > 0) dropped++
                    val mark = if (part.size < 2) "  alone" else if (i == 0) "  KEEP " else "  drop "
                    out.append(mark).append("%4d kbps %6.1f s  ".format(kbps, t.durationMs / 1000.0))
                        .append(t.id.removePrefix(root.path)).append("  [").append(t.title).append(" | ").append(t.album).append("]").append(l).append('\n')
                }
            }
        }
        out.insert(0, "${tracks.size} tracks, ${groups.size} groups proposed by the tags, $dropped copies left out after comparing the sound\n\n")
        File("build/same-song.txt").writeText(out.toString())
    }
}
