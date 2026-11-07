package com.sadurni.attacca.settings

import com.sadurni.attacca.R

/**
 * Starting points for the sound of a kind of output: a few effects set the way that kind of device
 * usually wants them, applied over the profile's other settings (ReplayGain and the limiter stay as
 * they are). Each one is only a start: everything stays editable in Sound.
 */
enum class SoundTemplate(val titleRes: Int, val hintRes: Int) {
    /** Headphones over or on the ears: strong crossfeed, as speakers in front would sound. */
    HEADPHONES(R.string.template_headphones, R.string.template_headphones_hint),

    /** Earbuds: lighter crossfeed and a gentle lift of the bass they tend to lose. */
    EARBUDS(R.string.template_earbuds, R.string.template_earbuds_hint),

    /** The car: even levels over the road noise, more bass, no crossfeed. */
    CAR(R.string.template_car, R.string.template_car_hint),

    /** A small Bluetooth speaker: bass it cannot play heard through its harmonics, a little levelling. */
    PORTABLE_SPEAKER(R.string.template_portable_speaker, R.string.template_portable_speaker_hint),

    /** A home stereo: the music as it is, nothing added. */
    HOME_STEREO(R.string.template_home_stereo, R.string.template_home_stereo_hint),

    /** The phone's own speaker: virtual bass and levelling, so quiet parts are not lost. */
    PHONE_SPEAKER(R.string.template_phone_speaker, R.string.template_phone_speaker_hint);

    /** [base] with this template's effects; everything the template does not use is switched off. */
    fun applyTo(base: DspSettings): DspSettings {
        val clean = base.withoutEffects()
        return when (this) {
            HEADPHONES -> clean.copy(crossfeedEnabled = true, crossfeedPreset = CrossfeedPreset.DEFAULT)
            EARBUDS -> clean.copy(
                crossfeedEnabled = true, crossfeedPreset = CrossfeedPreset.CHU_MOY,
                bassEnabled = true, bassCurve = BassCurve.GENTLE, bassGentleFreqHz = 150, bassMaxGainDb = 3f,
            )
            CAR -> clean.copy(
                bassEnabled = true, bassCurve = BassCurve.GENTLE, bassGentleFreqHz = 200, bassMaxGainDb = 4f,
                compressorEnabled = true, compressorThresholdDb = -20f, compressorRatio = 2.5f, compressorKneeDb = 6f,
                compressorAttackMs = 15f, compressorReleaseMs = 250f, compressorAutoMakeup = true,
            )
            PORTABLE_SPEAKER -> clean.copy(
                virtualBassEnabled = true, virtualBassAmount = 50, virtualBassCutoffHz = 120,
                compressorEnabled = true, compressorThresholdDb = -16f, compressorRatio = 2f, compressorKneeDb = 6f,
                compressorAttackMs = 10f, compressorReleaseMs = 200f, compressorAutoMakeup = true,
            )
            HOME_STEREO -> clean
            PHONE_SPEAKER -> clean.copy(
                virtualBassEnabled = true, virtualBassAmount = 80, virtualBassCutoffHz = 150,
                compressorEnabled = true, compressorThresholdDb = -24f, compressorRatio = 3f, compressorKneeDb = 6f,
                compressorAttackMs = 5f, compressorReleaseMs = 150f, compressorAutoMakeup = true,
            )
        }.copy(limiterEnabled = true)
    }

    companion object {
        /** The template that fits an output type best, offered first. */
        fun suggestedFor(type: OutputType?): SoundTemplate = when (type) {
            OutputType.SPEAKER -> PHONE_SPEAKER
            OutputType.WIRED -> HEADPHONES
            OutputType.BLUETOOTH -> EARBUDS
            OutputType.USB -> HEADPHONES
            else -> HEADPHONES
        }
    }
}
