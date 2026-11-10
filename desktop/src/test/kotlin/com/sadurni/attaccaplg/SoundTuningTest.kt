package com.sadurni.attaccaplg

import com.sadurni.attacca.settings.ChannelMode
import com.sadurni.attacca.settings.CrossfeedPreset
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.tuning.FragmentKind
import com.sadurni.attacca.tuning.Fragments
import com.sadurni.attacca.tuning.SoundTuning
import com.sadurni.attacca.tuning.TestSignals
import com.sadurni.attacca.tuning.TuneAnswers
import com.sadurni.attacca.tuning.TuneDevice
import com.sadurni.attacca.tuning.TuneLoop
import com.sadurni.attacca.tuning.TuneStep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SoundTuningTest {

    @Test
    fun stepsFitTheDevice() {
        val head = SoundTuning.steps(TuneDevice.HEADPHONES)
        assertTrue(TuneStep.CROSSFEED in head && TuneStep.WIDTH !in head)
        val car = SoundTuning.steps(TuneDevice.CAR)
        assertTrue(TuneStep.WIDTH in car && TuneStep.CROSSFEED !in car)
        val phone = SoundTuning.steps(TuneDevice.PHONE_SPEAKER)
        assertFalse(TuneStep.SIDES in phone || TuneStep.WIDTH in phone || TuneStep.CROSSFEED in phone)
        for (d in TuneDevice.entries) {
            val s = SoundTuning.steps(d)
            assertEquals(TuneStep.DEVICE, s.first())
            assertEquals(TuneStep.COMPARE, s.last())
        }
    }

    @Test
    fun suggestionsAndChoicesBecomeSettings() {
        val base = DspSettings(eqEnabled = true, eqGains = List(10) { 5f })
        // Headphones, nothing chosen: strong crossfeed suggested, the old EQ gone, limiter on.
        val s = SoundTuning.settings(base, TuneAnswers(TuneDevice.HEADPHONES))
        assertTrue(s.crossfeedEnabled && s.crossfeedPreset == CrossfeedPreset.DEFAULT)
        assertFalse(s.eqEnabled)
        assertTrue(s.limiterEnabled)
        // A device that stops at 100 Hz gets harmonics, cut off a little above.
        var a = TuneAnswers(TuneDevice.PORTABLE_SPEAKER, bassReachHz = 100)
        val opts = SoundTuning.options(TuneStep.BASS, a)
        assertEquals("bass_virtual", opts[SoundTuning.chosen(TuneStep.BASS, a)].id)
        assertEquals(120, SoundTuning.settings(base, a).virtualBassCutoffHz)
        // Choices: mono, brighter.
        a = SoundTuning.choose(TuneStep.SIDES, a, 1)
        a = SoundTuning.choose(TuneStep.TREBLE, a, 2)
        val t = SoundTuning.settings(base, a)
        assertEquals(ChannelMode.MONO, t.channelMode)
        assertTrue(t.eqEnabled && t.eqGains[9] > 0f)
        // Up to the bass step, the treble chosen later is not heard yet.
        assertFalse(SoundTuning.settings(base, a, TuneStep.BASS).eqEnabled)
        // Another device starts over.
        assertEquals(0, SoundTuning.choose(TuneStep.DEVICE, a, TuneDevice.CAR.ordinal).sides)
    }

    @Test
    fun testSoundsLoopQuietly() {
        val rate = 48000
        for (s in listOf(TestSignals.sides(rate), TestSignals.tone(rate, 60), TestSignals.bassLine(rate), TestSignals.groove(rate))) {
            assertTrue(s.isNotEmpty() && s.size % 2 == 0)
            assertTrue(s.all { abs(it) < 1f })
            // The loop's ends are (nearly) silent: no click going round.
            assertTrue(abs(s[0]) < 0.01f && abs(s[s.size - 1]) < 0.01f)
        }
        val sides = TestSignals.sides(rate)
        val l = sides[(rate * 0.4).toInt() * 2]; val r = sides[(rate * 0.4).toInt() * 2 + 1]
        assertTrue(r == 0f && l != 0f)
        assertEquals(0, TestSignals.sidesPart((rate * 0.4).toInt(), rate))
        assertEquals(2, TestSignals.sidesPart((rate * 2.8).toInt(), rate))
        assertEquals(-1, TestSignals.sidesPart(rate, rate))
        assertEquals(1, TestSignals.bassLineNote((rate * 1.5).toInt(), rate))
    }

    @Test
    fun theBassiestStretchIsFound() {
        val rate = 22050
        val seconds = 40
        val s = FloatArray(rate * seconds * 2)
        for (i in 0 until rate * seconds) {
            val t = i.toDouble() / rate
            // A deep bass only from second 20 to 34; a high tone throughout.
            val bass = if (t in 20.0..34.0) 0.4 * sin(2 * PI * 50 * t) else 0.0
            val v = (0.1 * sin(2 * PI * 3000 * t) + bass).toFloat()
            s[i * 2] = v; s[i * 2 + 1] = v
        }
        val w = assertNotNull(Fragments.bestWindow(s, 2, rate, FragmentKind.BASS))
        val start = w.startFrame.toDouble() / rate
        assertTrue(start in 19.5..22.5, "starts at $start")
        val f = Fragments.cut(s, 2, rate, w.startFrame, "x", outRate = 48000)
        assertEquals(48000, f.sampleRate)
        assertTrue(abs(f.frames - Fragments.SECONDS * 48000) < 200)
        assertTrue(f.samples.all { abs(it) <= 0.95f })
    }

    @Test
    fun theLoopPlaysThroughTheSettings() {
        val rate = 48000
        val loop = TuneLoop(rate)
        loop.play(Fragments.groove(rate))
        val out = FloatArray(1024 * 2)
        var energy = 0.0
        repeat(200) { loop.read(out, 1024); energy += out.sumOf { (it * it).toDouble() } }
        assertTrue(energy > 0.1)
        loop.settings = DspSettings().withoutEffects().copy(channelMode = ChannelMode.LEFT)
        repeat(50) { loop.read(out, 1024) }
        for (f in 0 until 1024) assertTrue(abs(out[f * 2] - out[f * 2 + 1]) < 1e-4f)
    }
}

class PlayTuningTest {
    private val rate = 48000

    /** [seconds] of a tone at [db] dBFS, then [silence] seconds of nothing. */
    private fun tone(seconds: Double, db: Double, silence: Double = 0.0, hz: Double = 220.0): com.sadurni.attacca.tuning.Pcm {
        val n = (seconds * rate).toInt()
        val total = n + (silence * rate).toInt()
        val s = FloatArray(total * 2)
        val a = Math.pow(10.0, db / 20) * Math.sqrt(2.0)
        for (i in 0 until n) {
            val v = (a * sin(2 * PI * hz * i / rate)).toFloat()
            s[i * 2] = v; s[i * 2 + 1] = v
        }
        return com.sadurni.attacca.tuning.Pcm(s, rate)
    }

    @Test
    fun jointsFollowTheChoices() {
        val tail = tone(26.0, -12.0, silence = 3.0)
        val head = tone(20.0, -30.0, hz = 330.0)
        assertTrue(com.sadurni.attacca.tuning.Joints.silenceAfterMs(tail) in 2800..3000)
        // Gapless with the silence kept: the change is the silence, then the second track.
        val kept = com.sadurni.attacca.tuning.Joints.render(tail, head, com.sadurni.attacca.tuning.JoinChoice(0, false, false))
        val trimmed = com.sadurni.attacca.tuning.Joints.render(tail, head, com.sadurni.attacca.tuning.JoinChoice(0, false, true))
        // Kept, the last seconds before the change are the silence; left out, they are the music.
        val keptPcm = com.sadurni.attacca.tuning.Pcm(kept.samples, rate)
        val trimmedPcm = com.sadurni.attacca.tuning.Pcm(trimmed.samples, rate)
        assertTrue(com.sadurni.attacca.tuning.Joints.levelDb(keptPcm, kept.changeFrom - 2 * rate, kept.changeFrom) < -60)
        assertTrue(com.sadurni.attacca.tuning.Joints.levelDb(trimmedPcm, trimmed.changeFrom - 2 * rate, trimmed.changeFrom - rate / 5) > -25)
        // Crossfade of 4 s: the change lasts 4 s; both tracks come out at one level.
        val blended = com.sadurni.attacca.tuning.Joints.render(tail, head, com.sadurni.attacca.tuning.JoinChoice(4000, false, true))
        assertEquals(4 * rate, blended.changeTo - blended.changeFrom)
        val pcm = com.sadurni.attacca.tuning.Pcm(blended.samples, rate)
        val before = com.sadurni.attacca.tuning.Joints.levelDb(pcm, rate, blended.changeFrom - rate)
        val after = com.sadurni.attacca.tuning.Joints.levelDb(pcm, blended.changeTo + rate, blended.frames - rate)
        assertTrue(abs(before - after) < 1.0, "$before vs $after")
        // Automatic, a file cut off loud: a plain crossfade, not a hard stop.
        val cut = tone(26.0, -12.0)
        val plan = com.sadurni.attacca.tuning.Joints.plan(cut, head, com.sadurni.attacca.tuning.JoinChoice(6000, true, true))!!
        assertEquals(com.sadurni.attacca.dsp.CrossfadeShape.CROSSFADE, plan.shape)
    }

    @Test
    fun levelsFollowTheChoices() {
        val loud = com.sadurni.attacca.tuning.LevelTrack(tone(10.0, -8.0), "loud", -8f, -9f)
        val quiet = com.sadurni.attacca.tuning.LevelTrack(tone(10.0, -26.0), "quiet", -26f, -12f)
        val a = com.sadurni.attacca.tuning.LevelAnswers()
        val even = com.sadurni.attacca.tuning.LevelTuning.render(loud, quiet, a, com.sadurni.attacca.tuning.LevelStep.BETWEEN)
        val pcm = com.sadurni.attacca.tuning.Pcm(even.samples, rate)
        val l1 = com.sadurni.attacca.tuning.Joints.levelDb(pcm, rate, 7 * rate)
        val l2 = com.sadurni.attacca.tuning.Joints.levelDb(pcm, even.secondFrom + rate, even.secondFrom + 7 * rate)
        assertTrue(abs(l1 - l2) < 1.0)
        // The album as recorded: the quiet track stays quieter than the loud one.
        val album = com.sadurni.attacca.tuning.LevelTuning.gainDb(quiet, a, com.sadurni.attacca.tuning.LevelStep.WITHIN)
        assertEquals(-6f, album, 0.01f)
        assertEquals(listOf(com.sadurni.attacca.tuning.LevelStep.BETWEEN), com.sadurni.attacca.tuning.LevelTuning.steps(a.copy(between = 0)))
        assertEquals(3f, com.sadurni.attacca.tuning.LevelTuning.preampDb(a.copy(overall = 1)))
    }
}

class StripSkyTest {
    @Test
    fun aBalloonMovesWholeIntoAStrip() {
        val d = com.sadurni.attacca.diorama.DrawList()
        val cx = 300f; val cy = 200f; val r = 30f
        d.circle(-1, cx, cy, r)
        d.polygon(-1); d.point(cx - r * 0.85f, cy + r * 0.45f); d.point(cx, cy + r * 1.35f); d.point(cx + r * 0.85f, cy + r * 0.45f)
        d.line(-1, 2f, cx - r * 0.3f, cy + r * 1.25f, cx - r * 0.25f, cy + r * 1.6f)
        d.rect(-1, cx - r * 0.3f, cy + r * 1.6f, cx + r * 0.3f, cy + r * 1.95f)
        // A cloud far away: a thing of its own.
        d.circle(-1, 800f, 60f, 20f)
        d.mapYWhole(0, d.size, 100f, 0.4f)
        val c = d.coords
        val circleY = c[d.starts[0] + 1]
        val basketTop = c[d.starts[3] + 1]
        assertEquals(r * 1.6f, basketTop - circleY, 0.01f)
        // Brought down as the strip wants (its middle where 100 + y × 0.4 is) …
        assertTrue(circleY < cy)
        // … and the cloud on its own.
        assertEquals(100f + 60f * 0.4f, c[d.starts[4] + 1], 0.01f)
    }
}
