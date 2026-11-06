package com.sadurni.attaccaplg

import com.sadurni.attaccaplg.analysis.TrackAnalyzer
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A stereo WAV: [silenceS] of silence, then [toneS] of a [hz] sine at [amplitude], then silence again. */
    private fun wav(name: String, silenceS: Double, toneS: Double, hz: Double = 1000.0, amplitude: Double = 0.5, rate: Int = 44_100): File {
        val silence = (silenceS * rate).toInt()
        val tone = (toneS * rate).toInt()
        val frames = silence * 2 + tone
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = if (i in silence until silence + tone) amplitude * sin(2 * PI * hz * (i - silence) / rate) else 0.0
            val s = (v * 32767).toInt()
            for (c in 0 until 2) {
                bytes[i * 4 + c * 2] = s.toByte()
                bytes[i * 4 + c * 2 + 1] = (s shr 8).toByte()
            }
        }
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        val file = tmp.newFile(name)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()), AudioFileFormat.Type.WAVE, file)
        return file
    }

    @Test
    fun measuresWhereTheSoundIsAndHowLoud() {
        val track = TrackAnalyzer.analyze(wav("tone.wav", silenceS = 2.0, toneS = 20.0))!!
        val a = track.analysis
        assertEquals(24_000.0, a.durationMs.toDouble(), 20.0)
        assertEquals(2_000.0, a.audioStartMs.toDouble(), 20.0)
        assertEquals(22_000.0, a.audioEndMs.toDouble(), 20.0)
        // A 1 kHz sine at half scale in both channels: 0 dBFS in one channel reads −3 LUFS, in two 0 LUFS: half scale, −6 LUFS.
        assertEquals(-6.0, a.loudnessLufs!!.toDouble(), 0.3)
        assertNotNull(track.head)
        assertNotNull(track.tail)
        assertNotNull(track.features)
        // A pure tone sits on one pitch class (1 kHz is close to B5).
        val chroma = track.head!!.chroma
        assertTrue("chroma: ${chroma.toList()}", chroma.max() > 0.5f)
    }

    @Test
    fun silenceHasNoEdges() {
        val track = TrackAnalyzer.analyze(wav("silent.wav", silenceS = 5.0, toneS = 0.0))!!
        assertNull(track.head)
        assertNull(track.tail)
    }

    @Test
    fun scansIncrementallyAndForgetsRemovedFiles() {
        val music = tmp.newFolder("music")
        File(music, "a").mkdirs()
        wav("one.wav", 1.0, 12.0).copyTo(File(music, "a/one.wav"))
        wav("two.wav", 0.5, 15.0, hz = 440.0).copyTo(File(music, "b.wav"))
        File(music, "notes.txt").writeText("not audio")
        LibraryDb(File(tmp.root, "lib.db")).use { db ->
            val first = Scanner(db, jobs = 2).scan(music)
            assertEquals(2, first.total)
            assertEquals(2, first.analysed)
            val again = Scanner(db, jobs = 2).scan(music)
            assertEquals(0, again.analysed)
            assertEquals(2, again.unchanged)
            File(music, "b.wav").delete()
            val after = Scanner(db, jobs = 2).scan(music)
            assertEquals(1, after.removed)
            val stored = db.all().single()
            assertEquals("a/one.wav", stored.path)
            assertTrue(stored.head != null && stored.tail != null && stored.features != null)
            assertEquals(1_000.0, stored.audioStartMs.toDouble(), 20.0)
            assertTrue(abs(stored.lufs!! + 6.0f) < 0.3f)
        }
    }

    @Test
    fun exchangeFileCarriesTheLibraryToAnotherOne() {
        val music = tmp.newFolder("music2")
        wav("a.wav", 1.0, 12.0).copyTo(File(music, "x/a.wav"))
        wav("b.wav", 0.5, 15.0, hz = 440.0).copyTo(File(music, "b.wav"))
        val attlib = File(tmp.root, "lib.attlib")
        LibraryDb(File(tmp.root, "one.db")).use { db ->
            Scanner(db, jobs = 2).scan(music)
            assertEquals(2, com.sadurni.attaccaplg.exchange.Exchange.export(db, attlib))
        }
        // The same music elsewhere (another folder, as on another computer).
        val copy = tmp.newFolder("copy")
        music.copyRecursively(copy)
        File(copy, "b.wav").appendBytes(ByteArray(10)) // changed: another size, not taken
        LibraryDb(File(tmp.root, "two.db")).use { db ->
            val r = com.sadurni.attaccaplg.exchange.Exchange.import(attlib, copy, db)
            assertEquals(2, r.total)
            assertEquals(1, r.matched)
            assertEquals(1, r.differentSize)
            val again = Scanner(db, jobs = 2).scan(copy)
            assertEquals(1, again.analysed) // only the changed one
            assertEquals(1, again.unchanged)
            val taken = db.all().first { it.path == "x/a.wav" }
            assertTrue(taken.head != null && taken.features != null && taken.waveform != null)
        }
    }
}
