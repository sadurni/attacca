package com.sadurni.attacca.art

import com.sadurni.attacca.diorama.SongWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtPainterTest {
    private fun song(id: String = "/music/a.mp3") = ArtSong(
        id = id, key = 7, keyClarity = 0.7f, brightness = 0.6f, roughness = 0.4f, voice = 0.8f,
        energy = 0.6f, bpm = 120f, pulse = 0.8f,
        wave = SongWave(FloatArray(512) { 0.3f + 0.6f * kotlin.math.sin(it / 40.0).toFloat().coerceAtLeast(0f) }, 200_000L, 0L, 200_000L),
    )

    /** A song played through with a beat every half second: what the painter laid. */
    private fun paint(s: ArtSong): ArtPainter {
        val p = ArtPainter()
        p.start(s, 900f, 1600f, 0L, veil = false)
        var t = 0.0
        var onset = Long.MIN_VALUE
        while (t < 200_000.0) {
            val now = t.toLong()
            if (now % 500 < 33) onset = now
            p.frame(now, t, true, 0.6f, 0.7f, 0.5f, onset)
            t += 33.0
        }
        p.dryAll()
        return p
    }

    @Test
    fun everyKindOfMarkStaysOnTheCanvas() {
        val p = paint(song())
        val kinds = p.history.map { it.kind }.toSet()
        for (k in listOf(ArtMark.BLOOM, ArtMark.DAB, ArtMark.LINE, ArtMark.GLAZE, ArtMark.DROP, ArtMark.SEAL, ArtMark.INK)) {
            assertTrue("kind $k painted", k in kinds)
        }
        for (m in p.history) {
            assertTrue("place ${m.kind} ${m.x} ${m.y}", m.x > -200f && m.x < 1100f && m.y > -200f && m.y < 1800f)
            assertTrue("alpha ${m.kind} ${m.alpha}", m.alpha in 0f..1f)
            assertTrue("colour ${m.kind}", m.r in 0f..1f && m.g in 0f..1f && m.b in 0f..1f)
        }
        assertEquals(1, p.history.count { it.kind == ArtMark.SEAL })
    }

    @Test
    fun theSameSongPaintsAlike() {
        val a = paint(song()).history
        val b = paint(song()).history
        assertEquals(a.size, b.size)
        assertEquals(a[a.size / 2].x, b[b.size / 2].x, 0f)
    }

    @Test
    fun openedInTheMiddleItCatchesUp() {
        val p = ArtPainter()
        p.start(song(), 900f, 1600f, 0L, veil = true)
        p.frame(0L, 90_000.0, true, 0.5f, 0.5f, 0.5f, Long.MIN_VALUE)
        assertEquals(ArtMark.VEIL, p.history.first().kind)
        assertTrue("catch-up painted ${p.history.size}", p.history.size > 100)
        assertTrue(p.wet.isEmpty())
    }

    private fun paintIn(style: ArtStyle, notes: FloatArray? = null): ArtPainter {
        val p = ArtPainter()
        p.start(song(), 900f, 1600f, 0L, veil = false, style = style)
        var t = 0.0
        var onset = Long.MIN_VALUE
        while (t < 120_000.0) {
            val now = t.toLong()
            if (now % 500 < 33) onset = now
            p.frame(now, t, true, 0.6f, 0.7f, 0.5f, onset, notes)
            t += 33.0
        }
        p.dryAll()
        return p
    }

    @Test
    fun eachStylePaintsItsOwnWay() {
        val oil = paintIn(ArtStyle.OIL).history.map { it.kind }.toSet()
        assertTrue("oil $oil", ArtMark.OIL in oil && ArtMark.DOT in oil && ArtMark.BLOOM !in oil)
        val pen = paintIn(ArtStyle.PEN)
        val penKinds = pen.history.map { it.kind }.toSet()
        assertTrue("pen $penKinds", ArtMark.PEN in penKinds && ArtMark.PENCIL in penKinds && ArtMark.DAB !in penKinds)
        val ink = paintIn(ArtStyle.INK).history
        // Ink: mostly dark, its colour kept for few strokes.
        val dark = ink.filter { it.kind == ArtMark.DAB }.count { it.r + it.g + it.b < 0.9f }
        assertTrue("ink $dark of ${ink.count { it.kind == ArtMark.DAB }}", dark > ink.count { it.kind == ArtMark.DAB } / 2)
        val oilPainter = ArtPainter().apply { start(song(), 900f, 1600f, 0L, veil = false, style = ArtStyle.OIL) }
        assertTrue("oil on a toned ground", oilPainter.ground.sum() < 2.95f)
    }

    @Test
    fun turnedOnItsSideThePaintingIsLaidOutAgain() {
        val p = paintIn(ArtStyle.WATERCOLOUR)
        val before = p.history.filter { it.kind == ArtMark.DAB }.take(20).map { it.rx }
        p.resize(1600f, 900f)
        assertEquals(1600f, p.width, 0f)
        for (m in p.history) assertTrue("in the wide canvas", m.x > -300f && m.x < 1900f && m.y > -300f && m.y < 1200f)
        // The strokes keep their size (the short side is the same).
        val after = p.history.filter { it.kind == ArtMark.DAB }.take(20).map { it.rx }
        assertEquals(before, after)
    }

    @Test
    fun byTheSongPicksAStyle() {
        val p = ArtPainter()
        p.start(song(), 900f, 1600f, 0L, veil = false, style = ArtStyle.AUTO)
        assertEquals(ArtStyle.AUTO, p.chosen)
        assertTrue(p.style != ArtStyle.AUTO)
    }

    @Test
    fun theColoursFollowTheChords() {
        // The song is in G (7); a chord on C# (1, as far round the fifths as can be) heard all along turns the hues.
        val d = FloatArray(12) { if (it == 1 || it == 5 || it == 8) 0.3f else 0.01f }
        val plain = paintIn(ArtStyle.WATERCOLOUR).history.filter { it.kind == ArtMark.DAB }
        val turned = paintIn(ArtStyle.WATERCOLOUR, d).history.filter { it.kind == ArtMark.DAB }
        val a = plain.takeLast(40).map { it.r - it.b }.average()
        val b = turned.takeLast(40).map { it.r - it.b }.average()
        assertTrue("hues turned: $a vs $b", kotlin.math.abs(a - b) > 0.01)
    }

    @Test
    fun aCoverTintsThePalette() {
        // Half a strong red, half grey: the average first, then the red.
        val pixels = IntArray(100) { if (it < 50) 0xffd02020.toInt() else 0xff808080.toInt() }
        val colours = ArtSong.coverColours(pixels)
        assertEquals(2, colours.size)
        assertEquals(0xd0, (colours[1] shr 16) and 0xff)
        val plain = ArtPalette.of(song())
        val red = ArtPalette.of(ArtSong("/music/a.mp3", 7, 0.7f, 0.6f, 0.4f, 0.8f, 0.6f, 120f, 0.8f, null, colours))
        assertTrue("main leans to red", red.main[0] - red.main[2] > plain.main[0] - plain.main[2])
        assertTrue("paper warmer", red.paper[0] - red.paper[2] >= plain.paper[0] - plain.paper[2])
    }

    @Test
    fun keysGiveDifferentPalettes() {
        val c = ArtPalette.of(song()).main
        val other = ArtPalette.of(ArtSong("/music/a.mp3", 19, 0.7f, 0.6f, 0.4f, 0.8f, 0.6f, 120f, 0.8f, null)).main
        assertTrue(kotlin.math.abs(c[0] - other[0]) + kotlin.math.abs(c[1] - other[1]) + kotlin.math.abs(c[2] - other[2]) > 0.15f)
    }
}
