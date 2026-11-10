package com.sadurni.attacca.tuning

import com.sadurni.attacca.settings.BassCurve
import com.sadurni.attacca.settings.ChannelMode
import com.sadurni.attacca.settings.CrossfeedPreset
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.EqType

/**
 * The kind of thing the music comes out of, which decides the steps of the sound guide and what
 * each step suggests first.
 */
enum class TuneDevice { HEADPHONES, EARBUDS, PHONE_SPEAKER, PORTABLE_SPEAKER, CAR, HOME_STEREO;

    /** Worn on the head: crossfeed instead of width. */
    val onTheHead: Boolean get() = this == HEADPHONES || this == EARBUDS
}

/** One step of the sound guide, in the order the guide goes through them. */
enum class TuneStep {
    /** What the device is. */
    DEVICE,

    /** Left, centre and right: balance, or both channels as one. */
    SIDES,

    /** How deep the device's bass goes, with tones or a bass line going down. */
    BASS_REACH,

    /** Bass: none, a gentle boost, more, or bass heard through its harmonics. */
    BASS,

    /** Treble: darker, as it is, brighter. */
    TREBLE,

    /** On the head: the two sides blended a little, as speakers in front would sound. */
    CROSSFEED,

    /** On speakers: the stereo image narrower or wider. */
    WIDTH,

    /** Quiet and loud parts brought closer. */
    LEVELS,

    /** Everything chosen against the sound before the guide. */
    COMPARE,
}

/** What a step's music sample should have plenty of (see [Fragments]). */
enum class FragmentKind { BASS, BRIGHT, WIDE, DYNAMIC, ANY }

/**
 * The choices made so far in the sound guide; each is the index of the option chosen in its step
 * (see [SoundTuning.options]).
 */
data class TuneAnswers(
    val device: TuneDevice = TuneDevice.HEADPHONES,
    /** 0: stereo, 1: both channels as one. */
    val sides: Int = 0,
    /** -1 (left only) to 1 (right only). */
    val balance: Float = 0f,
    /** The lowest frequency heard (Hz), or null when the reach step was skipped. */
    val bassReachHz: Int? = null,
    val bass: Int = -1,
    val treble: Int = 1,
    val crossfeed: Int = -1,
    val width: Int = 1,
    val levels: Int = -1,
)

/** One of the choices of a step: [id] names it (the text shown is the platform's). */
class TuneOption(val id: String, val suggested: Boolean = false)

/**
 * The sound guide: steps that each let the listener hear two to four choices on the same sample
 * and keep the one that sounds best on the device in use. Every choice is plain [DspSettings], so
 * all of it stays editable in Sound afterwards.
 */
object SoundTuning {

    /** The tones of the bass reach step, from the highest down. */
    val REACH_TONES = intArrayOf(150, 120, 100, 80, 60, 50, 40, 30)

    /** The steps for [device], in order. */
    fun steps(device: TuneDevice): List<TuneStep> = buildList {
        add(TuneStep.DEVICE)
        if (device != TuneDevice.PHONE_SPEAKER) add(TuneStep.SIDES)
        add(TuneStep.BASS_REACH)
        add(TuneStep.BASS)
        add(TuneStep.TREBLE)
        if (device.onTheHead) add(TuneStep.CROSSFEED)
        else if (device != TuneDevice.PHONE_SPEAKER) add(TuneStep.WIDTH)
        add(TuneStep.LEVELS)
        add(TuneStep.COMPARE)
    }

    /** What the sample of [step] should have plenty of; null when it plays test sounds instead. */
    fun fragmentKind(step: TuneStep): FragmentKind? = when (step) {
        TuneStep.DEVICE -> FragmentKind.ANY
        TuneStep.SIDES, TuneStep.BASS_REACH -> null
        TuneStep.BASS -> FragmentKind.BASS
        TuneStep.TREBLE -> FragmentKind.BRIGHT
        TuneStep.CROSSFEED, TuneStep.WIDTH -> FragmentKind.WIDE
        TuneStep.LEVELS -> FragmentKind.DYNAMIC
        TuneStep.COMPARE -> FragmentKind.ANY
    }

    /** The choices of [step] given what was answered before it. */
    fun options(step: TuneStep, a: TuneAnswers): List<TuneOption> = when (step) {
        TuneStep.DEVICE -> TuneDevice.entries.map { TuneOption(it.name.lowercase()) }
        TuneStep.SIDES -> listOf(
            TuneOption("stereo", suggested = true),
            TuneOption("mono"),
        )
        TuneStep.BASS_REACH -> emptyList()
        TuneStep.BASS -> when (bassKind(a)) {
            // It plays deep bass: a boost of what is there.
            BassKind.DEEP -> listOf(TuneOption("bass_none"), TuneOption("bass_gentle"), TuneOption("bass_more"))
            // Some bass: a gentle boost, or the deepest heard through harmonics.
            BassKind.SOME -> listOf(TuneOption("bass_none"), TuneOption("bass_gentle"), TuneOption("bass_virtual"))
            // Little bass: only harmonics help.
            BassKind.LITTLE -> listOf(TuneOption("bass_none"), TuneOption("bass_virtual_light"), TuneOption("bass_virtual"))
        }.mapIndexed { i, o -> TuneOption(o.id, i == suggestedBass(a)) }
        TuneStep.TREBLE -> listOf(TuneOption("treble_darker"), TuneOption("treble_same", suggested = true), TuneOption("treble_brighter"))
        TuneStep.CROSSFEED -> listOf("crossfeed_off", "crossfeed_light", "crossfeed_strong", "crossfeed_speakers")
            .mapIndexed { i, id -> TuneOption(id, i == suggestedCrossfeed(a.device)) }
        TuneStep.WIDTH -> listOf(TuneOption("width_narrower"), TuneOption("width_same", suggested = true), TuneOption("width_wider"))
        TuneStep.LEVELS -> listOf("levels_off", "levels_gentle", "levels_even")
            .mapIndexed { i, id -> TuneOption(id, i == suggestedLevels(a.device)) }
        TuneStep.COMPARE -> listOf(TuneOption("before"), TuneOption("after", suggested = true))
    }

    /** The option of [step] chosen in [a] (the suggested one while none is). */
    fun chosen(step: TuneStep, a: TuneAnswers): Int {
        val raw = when (step) {
            TuneStep.DEVICE -> a.device.ordinal
            TuneStep.SIDES -> a.sides
            TuneStep.BASS -> a.bass
            TuneStep.TREBLE -> a.treble
            TuneStep.CROSSFEED -> a.crossfeed
            TuneStep.WIDTH -> a.width
            TuneStep.LEVELS -> a.levels
            TuneStep.BASS_REACH, TuneStep.COMPARE -> -1
        }
        if (raw >= 0) return raw
        return options(step, a).indexOfFirst { it.suggested }.coerceAtLeast(0)
    }

    /** [a] with option [index] chosen in [step]. */
    fun choose(step: TuneStep, a: TuneAnswers, index: Int): TuneAnswers = when (step) {
        TuneStep.DEVICE -> {
            // Another device starts the rest over from its own suggestions.
            val d = TuneDevice.entries[index]
            if (d == a.device) a else TuneAnswers(device = d)
        }
        TuneStep.SIDES -> a.copy(sides = index)
        TuneStep.BASS -> a.copy(bass = index)
        TuneStep.TREBLE -> a.copy(treble = index)
        TuneStep.CROSSFEED -> a.copy(crossfeed = index)
        TuneStep.WIDTH -> a.copy(width = index)
        TuneStep.LEVELS -> a.copy(levels = index)
        TuneStep.BASS_REACH, TuneStep.COMPARE -> a
    }

    /**
     * [base] with the choices of [a] (each step's suggestion where nothing was chosen) in place of
     * its effects, for the steps up to [upTo]: while a step is heard, the ones after it stay out, so
     * what is compared is that step over what was chosen before it. ReplayGain stays as it was; the
     * limiter is always on.
     */
    fun settings(base: DspSettings, a: TuneAnswers, upTo: TuneStep = TuneStep.COMPARE): DspSettings {
        val steps = steps(a.device).filter { it.ordinal <= upTo.ordinal }
        var s = base.withoutEffects().copy(limiterEnabled = true)
        for (step in steps) s = apply(step, s, a, chosen(step, a))
        return s
    }

    /** [s] with option [index] of [step]. */
    private fun apply(step: TuneStep, s: DspSettings, a: TuneAnswers, index: Int): DspSettings = when (step) {
        TuneStep.DEVICE, TuneStep.BASS_REACH, TuneStep.COMPARE -> s
        TuneStep.SIDES -> s.copy(channelMode = if (index == 1) ChannelMode.MONO else ChannelMode.STEREO, balance = a.balance)
        TuneStep.BASS -> {
            val reach = a.bassReachHz ?: defaultReach(a.device)
            // Harmonics of what the device cannot play: from a little above where it stops.
            val cutoff = (reach * 1.2f).toInt().coerceIn(60, 180)
            when (options(TuneStep.BASS, a).getOrNull(index)?.id) {
                "bass_gentle" -> s.copy(bassEnabled = true, bassCurve = BassCurve.GENTLE, bassGentleFreqHz = 150, bassMaxGainDb = 3f)
                "bass_more" -> s.copy(bassEnabled = true, bassCurve = BassCurve.GENTLE, bassGentleFreqHz = 200, bassMaxGainDb = 6f)
                "bass_virtual_light" -> s.copy(virtualBassEnabled = true, virtualBassAmount = 40, virtualBassCutoffHz = cutoff)
                "bass_virtual" -> s.copy(virtualBassEnabled = true, virtualBassAmount = 75, virtualBassCutoffHz = cutoff)
                else -> s
            }
        }
        TuneStep.TREBLE -> when (index) {
            0 -> s.copy(eqEnabled = true, eqType = EqType.GRAPHIC, eqGains = tilt(-1f))
            2 -> s.copy(eqEnabled = true, eqType = EqType.GRAPHIC, eqGains = tilt(1f))
            else -> s
        }
        TuneStep.CROSSFEED -> when (index) {
            1 -> s.copy(crossfeedEnabled = true, crossfeedPreset = CrossfeedPreset.JAN_MEIER)
            2 -> s.copy(crossfeedEnabled = true, crossfeedPreset = CrossfeedPreset.DEFAULT)
            3 -> s.copy(crossfeedEnabled = true, crossfeedPreset = CrossfeedPreset.SPEAKERS)
            else -> s
        }
        TuneStep.WIDTH -> when (index) {
            0 -> s.copy(widthEnabled = true, widthPercent = 75, widthBassSafe = true)
            2 -> s.copy(widthEnabled = true, widthPercent = 135, widthBassSafe = true)
            else -> s
        }
        TuneStep.LEVELS -> when (index) {
            1 -> s.copy(
                compressorEnabled = true, compressorThresholdDb = -20f, compressorRatio = 2f, compressorKneeDb = 6f,
                compressorAttackMs = 15f, compressorReleaseMs = 250f, compressorAutoMakeup = true,
            )
            2 -> s.copy(
                compressorEnabled = true, compressorThresholdDb = -26f, compressorRatio = 3f, compressorKneeDb = 6f,
                compressorAttackMs = 8f, compressorReleaseMs = 180f, compressorAutoMakeup = true,
            )
            else -> s
        }
    }

    /** The treble shelf of the graphic EQ: 1 kHz and up, rising to 3 dB at the top (negative: down). */
    fun tilt(sign: Float): List<Float> =
        listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f, 3f).map { it * sign }

    private enum class BassKind { DEEP, SOME, LITTLE }

    private fun bassKind(a: TuneAnswers): BassKind {
        val reach = a.bassReachHz ?: defaultReach(a.device)
        return when {
            reach <= 50 -> BassKind.DEEP
            reach <= 80 -> BassKind.SOME
            else -> BassKind.LITTLE
        }
    }

    /** How deep a kind of device usually goes, while the reach is not known. */
    private fun defaultReach(d: TuneDevice): Int = when (d) {
        TuneDevice.HEADPHONES, TuneDevice.HOME_STEREO, TuneDevice.CAR -> 40
        TuneDevice.EARBUDS -> 50
        TuneDevice.PORTABLE_SPEAKER -> 80
        TuneDevice.PHONE_SPEAKER -> 150
    }

    private fun suggestedBass(a: TuneAnswers): Int = when (bassKind(a)) {
        BassKind.DEEP -> if (a.device == TuneDevice.CAR || a.device == TuneDevice.EARBUDS) 1 else 0
        BassKind.SOME -> 1
        BassKind.LITTLE -> 2
    }

    private fun suggestedCrossfeed(d: TuneDevice): Int = if (d == TuneDevice.HEADPHONES) 2 else 1

    private fun suggestedLevels(d: TuneDevice): Int = when (d) {
        TuneDevice.CAR, TuneDevice.PHONE_SPEAKER, TuneDevice.PORTABLE_SPEAKER -> 1
        else -> 0
    }
}
