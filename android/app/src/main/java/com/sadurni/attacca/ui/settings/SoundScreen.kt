package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.common.tipAnchor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.dsp.GraphicEq
import com.sadurni.attacca.settings.AppSettings
import com.sadurni.attacca.settings.CrossfeedPreset
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.OutputType
import com.sadurni.attacca.settings.ProfilesState
import com.sadurni.attacca.settings.ReplayGainMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.abs

private val dbFormat: (Float) -> String = { "%+.1f dB".format(it) }

/** The sound settings in groups, in the order the audio goes through them. */
private enum class SoundPage { LEVEL, TONE, STEREO, DYNAMICS }

@Composable
fun SoundScreen(onBack: () -> Unit) {
    val settings = LocalContext.current.app.settings
    val active by settings.profiles.active.collectAsStateWithLifecycle()
    val dsp = active.settings
    val update: ((DspSettings) -> DspSettings) -> Unit = settings::updateDsp

    CategoryScreen(
        title = stringResource(R.string.sound_title),
        pages = SoundPage.entries.toTypedArray(),
        pageTitle = { stringResource(it.titleRes) },
        onBack = onBack,
        menu = { open ->
            ProfileSection(settings)
            SectionTitle(stringResource(R.string.section_effects, profileName(active)))
            for (page in SoundPage.entries) {
                CategoryRow(page.icon, stringResource(page.titleRes), soundSummary(page, dsp)) { open(page) }
            }
        },
    ) { page ->
        // Which profile is being edited stays in sight on every page.
        Hint(stringResource(R.string.editing_profile, profileName(active)))
        when (page) {
            SoundPage.LEVEL -> {
                ReplayGainSection(dsp, update)
                PreampSection(dsp, update)
            }
            SoundPage.TONE -> {
                BassSection(dsp, update)
                VirtualBassSection(dsp, update)
                EqSection(dsp, update)
            }
            SoundPage.STEREO -> {
                CrossfeedSection(dsp, update)
                WidthSection(dsp, update)
            }
            SoundPage.DYNAMICS -> {
                CompressorSection(dsp, update)
                LimiterSection(dsp, update)
            }
        }
    }
}

private val SoundPage.titleRes: Int
    get() = when (this) {
        SoundPage.LEVEL -> R.string.sound_level
        SoundPage.TONE -> R.string.sound_tone
        SoundPage.STEREO -> R.string.sound_stereo
        SoundPage.DYNAMICS -> R.string.sound_dynamics
    }

private val SoundPage.icon: ImageVector
    get() = when (this) {
        SoundPage.LEVEL -> Icons.AutoMirrored.Rounded.VolumeUp
        SoundPage.TONE -> Icons.Rounded.Equalizer
        SoundPage.STEREO -> Icons.Rounded.Headphones
        SoundPage.DYNAMICS -> Icons.Rounded.Compress
    }

/** The modules switched on in each group. */
@Composable
private fun soundSummary(page: SoundPage, dsp: DspSettings): String = when (page) {
    SoundPage.LEVEL -> activeSummary(
        (dsp.replayGainMode != ReplayGainMode.OFF) to stringResource(R.string.section_replaygain),
        dsp.preampEnabled to stringResource(R.string.section_preamp) + " " + dbFormat(dsp.preampDb),
    )
    SoundPage.TONE -> activeSummary(
        dsp.bassEnabled to stringResource(R.string.section_bass),
        dsp.eqEnabled to stringResource(R.string.section_eq),
    )
    SoundPage.STEREO -> activeSummary(
        dsp.crossfeedEnabled to stringResource(R.string.section_crossfeed),
        dsp.widthEnabled to stringResource(R.string.section_width) + " ${dsp.widthPercent} %",
        (abs(dsp.balance) >= 0.01f) to stringResource(R.string.section_balance) + " " + formatBalance(dsp.balance),
    )
    SoundPage.DYNAMICS -> activeSummary(
        dsp.compressorEnabled to stringResource(R.string.section_compressor),
        dsp.limiterEnabled to stringResource(R.string.section_limiter),
    )
}

@Composable
private fun ProfileSection(settings: AppSettings) {
    val store = settings.profiles
    val state by store.state.collectAsStateWithLifecycle()
    val active by store.active.collectAsStateWithLifecycle()
    val device by store.currentDevice.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) } // "new" | "rename"
    // The built-in speaker reports the phone model as its name.
    val deviceName = device?.let { if (it.type == OutputType.SPEAKER) outputTypeName(it.type) else it.name }

    androidx.compose.foundation.layout.Box(Modifier.tipAnchor("sound.profile")) { SectionTitle(stringResource(R.string.section_profile)) }
    Hint(stringResource(R.string.profile_output, deviceName ?: stringResource(R.string.profile_output_unknown)))
    ProfilePicker(
        label = stringResource(R.string.section_profile),
        profiles = state.profiles,
        selectedId = active.id,
        allowInherit = false,
        onSelect = { id -> device?.let { store.assignProfile(it.key, id) } },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { dialog = "new" }) { Text(stringResource(R.string.profile_new)) }
        OutlinedButton(onClick = { dialog = "template" }) { Text(stringResource(R.string.template_button)) }
        if (active.id != ProfilesState.DEFAULT_PROFILE_ID) {
            OutlinedButton(onClick = { dialog = "rename" }) { Text(stringResource(R.string.profile_rename)) }
            OutlinedButton(onClick = { store.deleteProfile(active.id) }) { Text(stringResource(R.string.profile_delete)) }
        }
    }
    Hint(stringResource(R.string.profile_hint))

    when (dialog) {
        "new" -> NameDialog(
            title = stringResource(R.string.profile_new),
            initial = deviceName.orEmpty(),
            onConfirm = { name ->
                store.createProfile(name, active.settings)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        // On a device: its own profile from the template; with no device known, the profile in use.
        "template" -> TemplateDialog(
            title = stringResource(R.string.template_button),
            message = device?.takeIf { it.type != OutputType.SPEAKER }?.let { stringResource(R.string.template_for_device, it.name) }
                ?: stringResource(R.string.template_for_profile, profileName(active)),
            initial = com.sadurni.attacca.settings.SoundTemplate.suggestedFor(device?.type),
            onApply = { template ->
                val d = device
                if (d != null && d.type != OutputType.SPEAKER) store.applyTemplate(template, d.name, d.key, null)
                else store.applyTemplateToProfile(active.id, template)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "rename" -> NameDialog(
            title = stringResource(R.string.profile_rename),
            initial = active.name,
            onConfirm = { name ->
                store.renameProfile(active.id, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ReplayGainSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.tipAnchor("sound.replaygain")) { SectionTitle(stringResource(R.string.section_replaygain)) }
    SegmentedChoice(
        options = listOf(
            ReplayGainMode.OFF to stringResource(R.string.rg_off),
            ReplayGainMode.TRACK to stringResource(R.string.rg_track),
            ReplayGainMode.ALBUM to stringResource(R.string.rg_album),
            ReplayGainMode.AUTO to stringResource(R.string.rg_auto),
        ),
        selected = dsp.replayGainMode,
        onSelect = { mode -> update { it.copy(replayGainMode = mode) } },
    )
    Hint(stringResource(R.string.rg_auto_hint))
    val on = dsp.replayGainMode != ReplayGainMode.OFF
    ValueSlider(stringResource(R.string.rg_preamp), dsp.replayGainPreampDb, -15f..15f, 0.5f, dbFormat,
        { v -> update { it.copy(replayGainPreampDb = v) } }, enabled = on)
    ValueSlider(stringResource(R.string.rg_untagged), dsp.untaggedGainDb, -15f..15f, 0.5f, dbFormat,
        { v -> update { it.copy(untaggedGainDb = v) } }, enabled = on)
    Hint(stringResource(R.string.rg_measured_hint))
    SwitchRow(stringResource(R.string.rg_prevent_clipping), dsp.replayGainPreventClipping,
        { v -> update { it.copy(replayGainPreventClipping = v) } }, enabled = on)
}

@Composable
private fun PreampSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_preamp))
    SwitchRow(stringResource(R.string.preamp_enabled), dsp.preampEnabled, { v -> update { it.copy(preampEnabled = v) } })
    ValueSlider(stringResource(R.string.section_preamp), dsp.preampDb, -12f..15f, 0.5f, dbFormat,
        { v -> update { it.copy(preampDb = v) } }, enabled = dsp.preampEnabled)
    Hint(stringResource(R.string.preamp_hint))
}

@Composable
private fun EqSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_eq))
    SwitchRow(stringResource(R.string.eq_enabled), dsp.eqEnabled, { v -> update { it.copy(eqEnabled = v) } })
    if (!dsp.eqEnabled) return
    SegmentedChoice(
        listOf(
            com.sadurni.attacca.settings.EqType.GRAPHIC to stringResource(R.string.eq_graphic),
            com.sadurni.attacca.settings.EqType.PARAMETRIC to stringResource(R.string.eq_parametric),
        ),
        dsp.eqType,
    ) { type -> update { it.copy(eqType = type) } }
    SwitchRow(stringResource(R.string.eq_precut), dsp.eqAutoPrecut, { v -> update { it.copy(eqAutoPrecut = v) } })
    Hint(stringResource(R.string.eq_precut_hint))
    if (dsp.eqType == com.sadurni.attacca.settings.EqType.PARAMETRIC) {
        ParametricEqSection(dsp, update)
        return
    }
    GraphicEq.BAND_FREQUENCIES.forEachIndexed { band, freq ->
        val label = if (freq >= 1000) "${freq / 1000} kHz" else "$freq Hz"
        ValueSlider(label, dsp.eqGains[band], -12f..12f, 0.5f, dbFormat, { v ->
            update { s -> s.copy(eqGains = s.eqGains.toMutableList().also { it[band] = v }) }
        })
    }
    var presets by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { presets = true }) { Text(stringResource(R.string.eq_presets) + "…") }
        OutlinedButton(onClick = { update { it.copy(eqGains = List(DspSettings.EQ_BANDS) { 0f }) } }) {
            Text(stringResource(R.string.eq_reset))
        }
    }
    if (presets) {
        EqPresetDialog(dsp.eqGains, onPick = { gains ->
            update { it.copy(eqGains = gains) }
            presets = false
        }, onDismiss = { presets = false })
    }
}

@Composable
private fun BassSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_bass))
    SwitchRow(stringResource(R.string.bass_enabled), dsp.bassEnabled, { v -> update { it.copy(bassEnabled = v) } })
    val gentle = dsp.bassCurve == com.sadurni.attacca.settings.BassCurve.GENTLE
    if (dsp.bassEnabled) {
        SegmentedChoice(
            listOf(
                com.sadurni.attacca.settings.BassCurve.GENTLE to stringResource(R.string.bass_curve_gentle),
                com.sadurni.attacca.settings.BassCurve.DEEP to stringResource(R.string.bass_curve_deep),
            ),
            dsp.bassCurve,
        ) { curve -> update { it.copy(bassCurve = curve) } }
    }
    Hint(stringResource(if (gentle) R.string.bass_hint else R.string.bass_hint_deep))
    if (!dsp.bassEnabled) return
    if (gentle) {
        ValueSlider(stringResource(R.string.bass_freq), dsp.bassGentleFreqHz.toFloat(), 60f..400f, 10f, { "${it.toInt()} Hz" },
            { v -> update { it.copy(bassGentleFreqHz = v.toInt()) } })
        ValueSlider(stringResource(R.string.bass_amount), dsp.bassMaxGainDb, 1f..15f, 0.5f, dbFormat,
            { v -> update { it.copy(bassMaxGainDb = v) } })
    } else {
        ValueSlider(stringResource(R.string.bass_freq), dsp.bassFreqHz.toFloat(), 40f..200f, 5f, { "${it.toInt()} Hz" },
            { v -> update { it.copy(bassFreqHz = v.toInt()) } })
        ValueSlider(stringResource(R.string.bass_max), dsp.bassMaxGainDb, 1f..15f, 0.5f, dbFormat,
            { v -> update { it.copy(bassMaxGainDb = v) } })
        ValueSlider(stringResource(R.string.bass_ceiling), dsp.bassCeilingDb, -24f..0f, 1f, { "%.0f dB".format(it) },
            { v -> update { it.copy(bassCeilingDb = v) } })
    }
}

@Composable
private fun VirtualBassSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_virtual_bass))
    SwitchRow(stringResource(R.string.virtual_bass_enabled), dsp.virtualBassEnabled, { v -> update { it.copy(virtualBassEnabled = v) } })
    Hint(stringResource(R.string.virtual_bass_hint))
    if (!dsp.virtualBassEnabled) return
    ValueSlider(stringResource(R.string.virtual_bass_amount), dsp.virtualBassAmount.toFloat(), 10f..150f, 5f, { "${it.toInt()} %" },
        { v -> update { it.copy(virtualBassAmount = v.toInt()) } })
    ValueSlider(stringResource(R.string.virtual_bass_cutoff), dsp.virtualBassCutoffHz.toFloat(), 60f..200f, 10f, { "${it.toInt()} Hz" },
        { v -> update { it.copy(virtualBassCutoffHz = v.toInt()) } })
}

@Composable
private fun WidthSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_width))
    SwitchRow(stringResource(R.string.width_enabled), dsp.widthEnabled, { v -> update { it.copy(widthEnabled = v) } })
    if (dsp.widthEnabled) {
        ValueSlider(stringResource(R.string.width_amount), dsp.widthPercent.toFloat(), 0f..200f, 5f, { "${it.toInt()} %" },
            { v -> update { it.copy(widthPercent = v.toInt()) } })
        SwitchRow(stringResource(R.string.width_bass_safe), dsp.widthBassSafe, { v -> update { it.copy(widthBassSafe = v) } })
    }
    SectionTitle(stringResource(R.string.section_balance))
    ValueSlider(stringResource(R.string.balance_value), dsp.balance, -1f..1f, 0.05f, ::formatBalance,
        { v -> update { it.copy(balance = v) } })
    SectionTitle(stringResource(R.string.section_channels))
    val modes = listOf(
        com.sadurni.attacca.settings.ChannelMode.STEREO to R.string.channels_stereo,
        com.sadurni.attacca.settings.ChannelMode.MONO to R.string.channels_mono,
        com.sadurni.attacca.settings.ChannelMode.LEFT to R.string.channels_left,
        com.sadurni.attacca.settings.ChannelMode.RIGHT to R.string.channels_right,
        com.sadurni.attacca.settings.ChannelMode.SWAP to R.string.channels_swap,
        com.sadurni.attacca.settings.ChannelMode.KARAOKE to R.string.channels_karaoke,
    )
    for ((mode, label) in modes) {
        RadioRow(stringResource(label), dsp.channelMode == mode) { update { it.copy(channelMode = mode) } }
    }
    Hint(stringResource(R.string.channels_hint))
}

private fun formatBalance(value: Float): String = when {
    abs(value) < 0.01f -> "C"
    value < 0 -> "L ${(-value * 100).toInt()} %"
    else -> "R ${(value * 100).toInt()} %"
}

@Composable
private fun CrossfeedSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_crossfeed))
    SwitchRow(stringResource(R.string.crossfeed_enabled), dsp.crossfeedEnabled, { v -> update { it.copy(crossfeedEnabled = v) } })
    Hint(stringResource(R.string.crossfeed_hint))
    if (!dsp.crossfeedEnabled) return
    val presets = listOf(
        CrossfeedPreset.JAN_MEIER to R.string.crossfeed_jan_meier,
        CrossfeedPreset.CHU_MOY to R.string.crossfeed_chu_moy,
        CrossfeedPreset.DEFAULT to R.string.crossfeed_default,
        CrossfeedPreset.SPEAKERS to R.string.crossfeed_speakers,
        CrossfeedPreset.CUSTOM to R.string.crossfeed_custom,
    )
    for ((preset, label) in presets) {
        RadioRow(stringResource(label), dsp.crossfeedPreset == preset) { update { it.copy(crossfeedPreset = preset) } }
    }
    if (dsp.crossfeedPreset == CrossfeedPreset.CUSTOM) {
        ValueSlider(stringResource(R.string.crossfeed_cutoff), dsp.crossfeedCustomCutoffHz.toFloat(), 300f..2000f, 50f,
            { "${it.toInt()} Hz" }, { v -> update { it.copy(crossfeedCustomCutoffHz = v.toInt()) } })
        ValueSlider(stringResource(R.string.crossfeed_feed), dsp.crossfeedCustomFeedDb, 1f..15f, 0.5f,
            { "%.1f dB".format(it) }, { v -> update { it.copy(crossfeedCustomFeedDb = v) } })
    }
}

@Composable
private fun CompressorSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_compressor))
    SwitchRow(stringResource(R.string.compressor_enabled), dsp.compressorEnabled, { v -> update { it.copy(compressorEnabled = v) } })
    if (!dsp.compressorEnabled) return
    ValueSlider(stringResource(R.string.compressor_threshold), dsp.compressorThresholdDb, -40f..0f, 1f, { "%.0f dB".format(it) },
        { v -> update { it.copy(compressorThresholdDb = v) } })
    ValueSlider(stringResource(R.string.compressor_ratio), dsp.compressorRatio, 1f..10f, 0.5f, { "%.1f:1".format(it) },
        { v -> update { it.copy(compressorRatio = v) } })
    ValueSlider(stringResource(R.string.compressor_knee), dsp.compressorKneeDb, 0f..12f, 1f, { "%.0f dB".format(it) },
        { v -> update { it.copy(compressorKneeDb = v) } })
    ValueSlider(stringResource(R.string.compressor_attack), dsp.compressorAttackMs, 1f..100f, 1f, { "%.0f ms".format(it) },
        { v -> update { it.copy(compressorAttackMs = v) } })
    ValueSlider(stringResource(R.string.compressor_release), dsp.compressorReleaseMs, 20f..1000f, 10f, { "%.0f ms".format(it) },
        { v -> update { it.copy(compressorReleaseMs = v) } })
    SwitchRow(stringResource(R.string.compressor_auto_makeup), dsp.compressorAutoMakeup, { v -> update { it.copy(compressorAutoMakeup = v) } })
    ValueSlider(stringResource(R.string.compressor_makeup), if (dsp.compressorAutoMakeup) dsp.effectiveMakeupDb else dsp.compressorMakeupDb, 0f..12f, 0.5f, dbFormat,
        { v -> update { it.copy(compressorMakeupDb = v) } }, enabled = !dsp.compressorAutoMakeup)
    Hint(stringResource(R.string.compressor_auto_makeup_hint))
}

@Composable
private fun LimiterSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    SectionTitle(stringResource(R.string.section_limiter))
    SwitchRow(stringResource(R.string.limiter_enabled), dsp.limiterEnabled, { v -> update { it.copy(limiterEnabled = v) } })
    ValueSlider(stringResource(R.string.limiter_ceiling), dsp.limiterCeilingDb, -3f..0f, 0.1f, dbFormat,
        { v -> update { it.copy(limiterCeilingDb = v) } }, enabled = dsp.limiterEnabled)
}
