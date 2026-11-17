package com.sadurni.attaccaplg

import com.sadurni.attacca.chain.Audience
import com.sadurni.attacca.chain.LiveHearing
import com.sadurni.attacca.chain.LiveVerdict
import com.sadurni.attacca.chain.SoundNet
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.library.TagReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The audience at the ends of real live and studio albums (LIVE_MUSIC=<folder>), heard by the
 * app's own network, and the live verdict per album: with every end heard, and heard as the app
 * does it ([LiveHearing]: the end first, the start only when needed, an album no more once told),
 * with how many ends each way needs; written to build/live.txt. Skipped without the variable.
 */
class LiveRealTest {
    private val live = listOf(
        "Bob Dylan/1974-Before the Flood-CD01", "Bob Dylan/1976-Hard Rain", "Bob Dylan/1978-At Budokan-CD01",
        "Bob Dylan/1995-MTV Unplugged", "The Cure/1993-Paris", "The Cure/2003-Disintegration (Trilogy)",
        "Queen/1979-Live Killers-CD01", "Queen/1992-Live at Wembley ’86-CD01", "Genesis/1982-Three Sides Live-CD01",
        "Queen/2015-A Night at the Odeon", "Genesis/1977-Seconds Out-CD01", "Metallica/1999-S&M-CD01",
        "Joe Satriani/2001-Live in San Francisco-CD01", "Joe Satriani/1997-G3 - Live in Concert", "The Cure/2003-Pornography (Trilogy)",
    )
    private val studio = listOf(
        "Bob Dylan/1975-Blood on the Tracks", "Bob Dylan/1965-Highway 61 Revisited", "The Cure/1992-Wish",
        "The Cure/1989-Disintegration", "Queen/1975-A Night at the Opera", "Queen/1989-At the Beeb",
        "Metallica/1984-Ride the Lightning", "Joe Satriani/1989-Flying in a Blue Dream",
    )

    @Test
    fun hearTheAlbums() {
        val root = System.getenv("LIVE_MUSIC")?.let(::File)
        assumeTrue(root != null && root.isDirectory)
        val net = javaClass.getResourceAsStream("/soundnet.bin")!!.use { SoundNet(it) }
        val out = StringBuilder()
        var fullEnds = 0
        var quickEnds = 0
        var differ = 0
        var liveTotal = 0
        var liveFull = 0
        var liveQuick = 0
        var studioFull = 0
        var studioQuick = 0
        val liveTag = Regex("""\blive\b""")
        for ((kind, albums) in listOf("LIVE" to live, "STUDIO" to studio)) for (album in albums) {
            val files = File(root, album).listFiles()!!.filter { it.extension.lowercase() in setOf("mp3", "ogg", "flac") }.sortedBy { it.name }
            val all = files.map { f ->
                val tags = TagReader.read(f)
                val type = runCatching { org.jaudiotagger.audio.AudioFileIO.read(f).tag.getAll(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_RELEASE_TYPE).joinToString("/") }.getOrDefault("")
                LiveVerdict.Track(f.path, album, tags.title, tags.album, hear(net, f), liveTag.containsMatchIn(type.lowercase()), type.isNotBlank())
            }.let { tracks -> if (System.getenv("LIVE_NO_TAGS") != null) tracks.map { it.copy(taggedLive = false, typed = false) } else tracks }
            val full = LiveVerdict.live(all)
            fullEnds += 2 * all.size
            // As the app hears it: in order, the end first, no more once the album is told.
            val quick = all.map { it.copy(audience = null) }.toMutableList()
            var ends = 0
            for (i in LiveHearing.order(quick.size)) {
                if (LiveHearing.decided(quick)) break
                if (LiveVerdict.saidLive(quick[i])) continue
                val a = all[i].audience ?: Audience(0f, 0f)
                ends++
                val head = if (LiveHearing.needsHead(a.tail)) { ends++; a.head } else Audience.NOT_HEARD
                quick[i] = quick[i].copy(audience = Audience(head, a.tail))
            }
            for (i in quick.indices) if (quick[i].audience == null) quick[i] = quick[i].copy(audience = Audience.SKIPPED)
            quickEnds += ends
            val fast = LiveVerdict.live(quick)
            if (fast != full) differ++
            if (kind == "LIVE") {
                liveTotal += all.size; liveFull += full.size; liveQuick += fast.size
            } else {
                studioFull += full.size; studioQuick += fast.size
            }
            out.append("$kind  $album: all heard ${full.size} of ${all.size} live; heard as the app ${fast.size} live with $ends of ${2 * all.size} ends")
            out.append(if (fast != full) "  DIFFERS" else "").append(if (all.any { it.taggedLive }) "  (tagged live)" else if (all.any { it.typed }) "  (typed)" else "").append('\n')
            for ((i, t) in all.withIndex()) {
                val q = quick[i].audience!!
                out.append("   %s%s %4.2f %4.2f | %5.2f %5.2f  %s\n".format(
                    if (t.id in full) "L" else "-", if (t.id in fast) "L" else "-",
                    t.audience?.head ?: -1f, t.audience?.tail ?: -1f, q.head, q.tail, File(t.id).name,
                ))
            }
        }
        out.insert(0, "every end: live $liveFull of $liveTotal, studio taken as live $studioFull, $fullEnds ends\n" +
            "as the app: live $liveQuick of $liveTotal, studio taken as live $studioQuick, $quickEnds ends (${quickEnds * 100 / fullEnds} %); albums that differ: $differ\n\n")
        File("build/live.txt").writeText(out.toString())
    }

    /** As the app does: the first and last 8 s of sound, through the network. */
    private fun hear(net: SoundNet, file: File): Audience? {
        var rate = 0
        var channels = 1
        val mono = ArrayList<FloatArray>()
        var total = 0
        Decoder.decode(file, { f -> rate = f.sampleRate; channels = f.channels; mono.clear(); total = 0 }) { samples, frames ->
            mono += FloatArray(frames) { k -> var s = 0f; for (c in 0 until channels) s += samples[k * channels + c]; s / channels }
            total += frames
        }
        if (rate == 0 || total == 0) return null
        val all = FloatArray(total).also { var o = 0; for (b in mono) { b.copyInto(it, o); o += b.size } }
        val start = all.indexOfFirst { abs(it) > 0.003f }.takeIf { it >= 0 } ?: return null
        val end = all.indexOfLast { abs(it) > 0.003f } + 1
        val n = Audience.SECONDS * rate
        val head = all.copyOfRange(start, minOf(end, start + n))
        val tail = all.copyOfRange(maxOf(start, end - n), end)
        val h = Audience.score(net, SoundNet.resample(head, head.size, rate)) ?: 0f
        val t = Audience.score(net, SoundNet.resample(tail, tail.size, rate)) ?: 0f
        return Audience(h, t)
    }
}
