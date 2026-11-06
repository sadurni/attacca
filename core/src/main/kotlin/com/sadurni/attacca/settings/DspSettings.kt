package com.sadurni.attacca.settings

import org.json.JSONArray
import org.json.JSONObject

enum class ReplayGainMode { OFF, TRACK, ALBUM, AUTO }

/**
 * How the two channels reach the ears: as they are, both as one (mono), one of them on both
 * sides, swapped, or with what they share taken out (karaoke: voices panned to the centre fade).
 */
enum class ChannelMode { STEREO, MONO, LEFT, RIGHT, SWAP, KARAOKE }

/** The 10 fixed bands, or bands of free frequency, gain and Q. */
enum class EqType { GRAPHIC, PARAMETRIC }

/**
 * Shape of the bass boost: GENTLE rises gradually from the low mids down (a first-order shelf, as
 * a tone control), keeps the boost steady and lowers the level only as far as the track's headroom
 * falls short; DEEP lifts only the deep bass (a steep shelf) and backs off when the bass gets loud.
 */
enum class BassCurve { GENTLE, DEEP }

/**
 * Crossfeed modes. The Bauer stereophonic-to-binaural presets (cutoff in Hz, feed level in dB; a
 * lower feed level means more crossfeed) go from [JAN_MEIER], the lightest, to [DEFAULT], the
 * strongest. [SPEAKERS] is a head model with virtual speakers instead.
 */
enum class CrossfeedPreset(val cutoffHz: Int, val feedDb: Float) {
    DEFAULT(700, 4.5f),
    CHU_MOY(700, 6.0f),
    JAN_MEIER(650, 9.5f),
    SPEAKERS(0, 0f),
    CUSTOM(700, 4.5f),
}

/** The whole sound processing chain; one instance per profile. */
data class DspSettings(
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val replayGainPreampDb: Float = 0f,
    val untaggedGainDb: Float = 0f,
    val replayGainPreventClipping: Boolean = true,
    val preampEnabled: Boolean = false,
    val preampDb: Float = 0f,
    val eqEnabled: Boolean = false,
    val eqGains: List<Float> = List(EQ_BANDS) { 0f },
    /** Lowers the level by the highest band's boost, so boosting never pushes into the limiter. */
    val eqAutoPrecut: Boolean = true,
    val eqType: EqType = EqType.GRAPHIC,
    val peqBands: List<com.sadurni.attacca.dsp.PeqBand> = emptyList(),
    /** Level before the parametric bands (as correction files give it). */
    val peqPreampDb: Float = 0f,
    val bassEnabled: Boolean = false,
    val bassCurve: BassCurve = BassCurve.GENTLE,
    /** Midpoint of the gentle curve (where it gives half the boost). */
    val bassGentleFreqHz: Int = 200,
    /** Corner of the deep curve. */
    val bassFreqHz: Int = 100,
    val bassMaxGainDb: Float = 8f,
    /** Bass level the deep boost may reach (dBFS); louder bass gets less boost. */
    val bassCeilingDb: Float = -6f,
    /** Harmonics of the deep bass, for speakers and earbuds that cannot play it (see VirtualBass). */
    val virtualBassEnabled: Boolean = false,
    val virtualBassAmount: Int = 50,
    val virtualBassCutoffHz: Int = 100,
    val widthEnabled: Boolean = false,
    val widthPercent: Int = 100,
    val widthBassSafe: Boolean = true,
    /** -1 = left only, 0 = centered, 1 = right only. */
    val balance: Float = 0f,
    val channelMode: ChannelMode = ChannelMode.STEREO,
    val crossfeedEnabled: Boolean = false,
    val crossfeedPreset: CrossfeedPreset = CrossfeedPreset.DEFAULT,
    val crossfeedCustomCutoffHz: Int = 700,
    val crossfeedCustomFeedDb: Float = 4.5f,
    val compressorEnabled: Boolean = false,
    val compressorThresholdDb: Float = -18f,
    val compressorRatio: Float = 3f,
    val compressorKneeDb: Float = 6f,
    val compressorAttackMs: Float = 10f,
    val compressorReleaseMs: Float = 150f,
    val compressorMakeupDb: Float = 0f,
    /** Makeup gain worked out from the threshold and ratio: what the compression takes from a full-scale signal. */
    val compressorAutoMakeup: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -0.3f,
) {
    /**
     * These settings with every effect off (preamp, equalizer, bass, width and balance, channels,
     * crossfeed, compressor): what the music sounds like without them. ReplayGain and the limiter
     * stay, so the level does not jump.
     */
    fun withoutEffects(): DspSettings = copy(
        preampEnabled = false,
        eqEnabled = false,
        bassEnabled = false,
        virtualBassEnabled = false,
        widthEnabled = false,
        balance = 0f,
        channelMode = ChannelMode.STEREO,
        crossfeedEnabled = false,
        compressorEnabled = false,
    )

    val crossfeedCutoffHz: Int
        get() = if (crossfeedPreset == CrossfeedPreset.CUSTOM) crossfeedCustomCutoffHz else crossfeedPreset.cutoffHz
    val crossfeedFeedDb: Float
        get() = if (crossfeedPreset == CrossfeedPreset.CUSTOM) crossfeedCustomFeedDb else crossfeedPreset.feedDb

    /** The makeup gain in use: set by hand, or what compression takes from a full-scale signal. */
    val effectiveMakeupDb: Float
        get() = if (compressorAutoMakeup) {
            (-com.sadurni.attacca.dsp.Compressor.staticCurve(0.0, compressorThresholdDb.toDouble(), compressorRatio.toDouble(), compressorKneeDb.toDouble())).toFloat().coerceAtLeast(0f)
        } else {
            compressorMakeupDb
        }

    fun toJson(): JSONObject = JSONObject()
        .put("rgMode", replayGainMode.name)
        .put("rgPreamp", replayGainPreampDb.toDouble())
        .put("rgUntagged", untaggedGainDb.toDouble())
        .put("rgPreventClipping", replayGainPreventClipping)
        .put("preampOn", preampEnabled)
        .put("preampDb", preampDb.toDouble())
        .put("eqOn", eqEnabled)
        .put("eqGains", JSONArray(eqGains.map { it.toDouble() }))
        .put("eqPrecut", eqAutoPrecut)
        .put("eqType", eqType.name)
        .put("peqPreamp", peqPreampDb.toDouble())
        .put("peqBands", JSONArray(peqBands.map {
            JSONObject().put("t", it.type.name).put("f", it.freqHz.toDouble()).put("g", it.gainDb.toDouble()).put("q", it.q.toDouble()).put("on", it.enabled)
        }))
        .put("vbOn", virtualBassEnabled)
        .put("vbAmount", virtualBassAmount)
        .put("vbCutoff", virtualBassCutoffHz)
        .put("channels", channelMode.name)
        .put("compAutoMakeup", compressorAutoMakeup)
        .put("bassOn", bassEnabled)
        .put("bassFreq", bassFreqHz)
        .put("bassCurve", bassCurve.name)
        .put("bassGentleFreq", bassGentleFreqHz)
        .put("bassMax", bassMaxGainDb.toDouble())
        .put("bassCeiling", bassCeilingDb.toDouble())
        .put("widthOn", widthEnabled)
        .put("width", widthPercent)
        .put("widthBassSafe", widthBassSafe)
        .put("balance", balance.toDouble())
        .put("cfOn", crossfeedEnabled)
        .put("cfPreset", crossfeedPreset.name)
        .put("cfCutoff", crossfeedCustomCutoffHz)
        .put("cfFeed", crossfeedCustomFeedDb.toDouble())
        .put("compOn", compressorEnabled)
        .put("compThreshold", compressorThresholdDb.toDouble())
        .put("compRatio", compressorRatio.toDouble())
        .put("compKnee", compressorKneeDb.toDouble())
        .put("compAttack", compressorAttackMs.toDouble())
        .put("compRelease", compressorReleaseMs.toDouble())
        .put("compMakeup", compressorMakeupDb.toDouble())
        .put("limOn", limiterEnabled)
        .put("limCeiling", limiterCeilingDb.toDouble())

    companion object {
        const val EQ_BANDS = 10

        /** Missing keys fall back to defaults, so profiles saved by older versions still load. */
        fun fromJson(json: JSONObject): DspSettings {
            val d = DspSettings()
            fun f(key: String, default: Float) = json.optDouble(key, default.toDouble()).toFloat()
            val gains = json.optJSONArray("eqGains")
            return DspSettings(
                replayGainMode = enumOr(json.optString("rgMode"), d.replayGainMode),
                replayGainPreampDb = f("rgPreamp", d.replayGainPreampDb),
                untaggedGainDb = f("rgUntagged", d.untaggedGainDb),
                replayGainPreventClipping = json.optBoolean("rgPreventClipping", d.replayGainPreventClipping),
                preampEnabled = json.optBoolean("preampOn", d.preampEnabled),
                preampDb = f("preampDb", d.preampDb),
                eqEnabled = json.optBoolean("eqOn", d.eqEnabled),
                eqGains = List(EQ_BANDS) { i -> gains?.optDouble(i, 0.0)?.toFloat() ?: 0f },
                eqAutoPrecut = json.optBoolean("eqPrecut", d.eqAutoPrecut),
                eqType = enumOr(json.optString("eqType"), d.eqType),
                peqPreampDb = f("peqPreamp", d.peqPreampDb),
                peqBands = json.optJSONArray("peqBands")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { o ->
                            com.sadurni.attacca.dsp.PeqBand(
                                enumOr(o.optString("t"), com.sadurni.attacca.dsp.PeqBand.Type.PEAK),
                                o.optDouble("f", 1000.0).toFloat(), o.optDouble("g", 0.0).toFloat(), o.optDouble("q", 0.71).toFloat(),
                                o.optBoolean("on", true),
                            )
                        }
                    }
                } ?: d.peqBands,
                virtualBassEnabled = json.optBoolean("vbOn", d.virtualBassEnabled),
                virtualBassAmount = json.optInt("vbAmount", d.virtualBassAmount),
                virtualBassCutoffHz = json.optInt("vbCutoff", d.virtualBassCutoffHz),
                channelMode = enumOr(json.optString("channels"), d.channelMode),
                compressorAutoMakeup = json.optBoolean("compAutoMakeup", d.compressorAutoMakeup),
                bassEnabled = json.optBoolean("bassOn", d.bassEnabled),
                bassFreqHz = json.optInt("bassFreq", d.bassFreqHz),
                bassCurve = enumOr(json.optString("bassCurve"), d.bassCurve),
                bassGentleFreqHz = json.optInt("bassGentleFreq", d.bassGentleFreqHz),
                bassMaxGainDb = f("bassMax", d.bassMaxGainDb),
                // Replaces the old back-off threshold, whose values meant something else.
                bassCeilingDb = f("bassCeiling", d.bassCeilingDb),
                widthEnabled = json.optBoolean("widthOn", d.widthEnabled),
                widthPercent = json.optInt("width", d.widthPercent),
                widthBassSafe = json.optBoolean("widthBassSafe", d.widthBassSafe),
                balance = f("balance", d.balance),
                crossfeedEnabled = json.optBoolean("cfOn", d.crossfeedEnabled),
                crossfeedPreset = enumOr(json.optString("cfPreset"), d.crossfeedPreset),
                crossfeedCustomCutoffHz = json.optInt("cfCutoff", d.crossfeedCustomCutoffHz),
                crossfeedCustomFeedDb = f("cfFeed", d.crossfeedCustomFeedDb),
                compressorEnabled = json.optBoolean("compOn", d.compressorEnabled),
                compressorThresholdDb = f("compThreshold", d.compressorThresholdDb),
                compressorRatio = f("compRatio", d.compressorRatio),
                compressorKneeDb = f("compKnee", d.compressorKneeDb),
                compressorAttackMs = f("compAttack", d.compressorAttackMs),
                compressorReleaseMs = f("compRelease", d.compressorReleaseMs),
                compressorMakeupDb = f("compMakeup", d.compressorMakeupDb),
                limiterEnabled = json.optBoolean("limOn", d.limiterEnabled),
                limiterCeilingDb = f("limCeiling", d.limiterCeilingDb),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
