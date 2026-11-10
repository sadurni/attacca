package com.sadurni.attacca.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.Background
import com.sadurni.attacca.R
import com.sadurni.attacca.analysis.AnalysisDb
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.ChainDb
import com.sadurni.attacca.library.DamagedTracks
import com.sadurni.attacca.playback.SystemEffects
import com.sadurni.attacca.settings.OutputType
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.withContext
import java.io.File

/** What the check-up counts in the library (in the background: it reads the databases). */
private class LibraryCounts(
    val tracks: Int,
    val notAnalysed: Int,
    /** Tracks without a measured loudness. */
    val noLoudness: Int,
    /** Of those, not read for ReplayGain tags yet. */
    val unread: Int,
    /** Of those read, without ReplayGain tags: nothing to level them by (their paths). */
    val unlevelled: Set<String>,
)

/**
 * The check-up: what keeps Attacca from sounding and working at its best, each with what it
 * affects and a way to fix it — tracks not analysed, loudness not measured, damaged tracks,
 * effects of the phone on top, battery saving, devices without a sound of their own — and the
 * guides that tune the rest by ear.
 */
@Composable
fun Checkup(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { CheckupContent(onDone) }
    }
}

@Composable
private fun CheckupContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val settings = app.settings
    val trim by settings.trim.collectAsStateWithLifecycle()
    val profiles by settings.profiles.state.collectAsStateWithLifecycle()
    val damaged by DamagedTracks.all.collectAsStateWithLifecycle()
    val analysing by app.chainAnalyzer.libraryProgress.collectAsStateWithLifecycle()
    val folder = settings.musicFolder
    var check by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { check++ }
    var counts by remember { mutableStateOf<LibraryCounts?>(null) }
    val rgProgress by com.sadurni.attacca.library.ReplayGainCheck.progress.collectAsStateWithLifecycle()
    val rgPasses by com.sadurni.attacca.library.ReplayGainCheck.passes.collectAsStateWithLifecycle()
    LaunchedEffect(folder, check, analysing == null, rgPasses) {
        counts = withContext(Background.dispatcher) {
            val f = folder?.takeIf { File(it).isDirectory } ?: return@withContext null
            val tracks = com.sadurni.attacca.library.TrackSort.db?.tracks(f).orEmpty()
            val files = tracks.map { File(it.path) }
            val features = runCatching { ChainDb(context).use { it.getFeatures(files) } }.getOrDefault(emptyMap())
            val measured = runCatching { app.analysisDb.getAll(files, trim.thresholdDb) }.getOrDefault(emptyMap())
                .filter { (_, l) -> (l as? AnalysisDb.Lookup.Hit)?.analysis?.loudnessLufs != null }.keys
            val unmeasured = files.filter { it.absolutePath !in measured }
            // Tracks with ReplayGain tags need no measure: which have them is kept in the tag database.
            val rg = runCatching { com.sadurni.attacca.library.TrackSort.db?.replayGainKnown() }.getOrNull().orEmpty()
            val unread = unmeasured.count { f -> rg[f.absolutePath]?.let { !com.sadurni.attacca.library.FileStamp.unchanged(it[0], it[1], f) } ?: true }
            val unlevelled = unmeasured.filter { rg[it.absolutePath]?.get(2) == 0L }.map { it.absolutePath }.toSet()
            LibraryCounts(tracks.size, files.count { it.absolutePath !in features }, unmeasured.size, unread, unlevelled)
        }
    }
    val effects = remember(check) { SystemEffects.installedApps(context) }
    val builtIn = remember(check) { SystemEffects.builtInProcessing() }
    val batteryOk = remember(check) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val untuned = profiles.devices.filter { it.type != OutputType.SPEAKER && it.profileId == null }
    val damagedHere = remember(damaged, folder) { folder?.let { DamagedTracks.under(it).size } ?: 0 }
    var guide by remember { mutableStateOf<String?>(null) }
    var showDamaged by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
            Text(stringResource(R.string.checkup_title), fontSize = AttaccaType.heading, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Hint(stringResource(R.string.checkup_intro))
                val c = counts
                if (folder == null) {
                    CheckRow(false, stringResource(R.string.checkup_folder), stringResource(R.string.checkup_folder_none))
                } else if (c == null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalAccent.current)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.checkup_reading), fontSize = AttaccaType.secondary)
                    }
                } else {
                    CheckRow(
                        c.notAnalysed == 0,
                        stringResource(R.string.checkup_analysis),
                        if (c.notAnalysed == 0) stringResource(R.string.checkup_analysis_ok, c.tracks)
                        else stringResource(R.string.checkup_analysis_missing, c.notAnalysed, c.tracks),
                        action = if (c.notAnalysed > 0 && analysing == null) stringResource(R.string.checkup_analysis_do) to { app.chainAnalyzer.analyzeLibrary(File(folder)) } else null,
                        busy = analysing != null,
                    )
                    // Some not read for their tags yet: read now (in the background), counted when done.
                    LaunchedEffect(c.unread) { if (c.unread > 0) com.sadurni.attacca.library.ReplayGainCheck.checkFolder(folder) }
                    val reading = rgProgress
                    CheckRow(
                        c.unlevelled.isEmpty() && c.unread == 0,
                        stringResource(R.string.checkup_loudness),
                        when {
                            c.noLoudness == 0 -> stringResource(R.string.checkup_loudness_ok)
                            c.unread > 0 -> stringResource(R.string.checkup_loudness_reading, reading?.done ?: 0, reading?.total ?: c.unread)
                            c.unlevelled.isEmpty() -> stringResource(R.string.checkup_loudness_tagged, c.noLoudness)
                            else -> stringResource(R.string.checkup_loudness_exact, c.unlevelled.size)
                        },
                        action = if (c.unlevelled.isNotEmpty() && c.unread == 0 && analysing == null) stringResource(R.string.checkup_loudness_do) to {
                            app.chainAnalyzer.measureWholeOf(File(folder), c.unlevelled)
                        } else null,
                        busy = analysing != null,
                        minor = true,
                    )
                    CheckRow(
                        damagedHere == 0,
                        stringResource(R.string.checkup_damaged),
                        if (damagedHere == 0) stringResource(R.string.checkup_damaged_ok) else stringResource(R.string.damaged_count, damagedHere),
                        action = if (damagedHere > 0) stringResource(R.string.damaged_show) to { showDamaged = true } else null,
                    )
                }
                CheckRow(
                    effects.isEmpty() && builtIn.isEmpty(),
                    stringResource(R.string.checkup_effects),
                    when {
                        effects.isNotEmpty() -> stringResource(R.string.checkup_effects_apps, effects.joinToString { it.name })
                        builtIn.isNotEmpty() -> stringResource(R.string.checkup_effects_builtin, builtIn.joinToString())
                        else -> stringResource(R.string.checkup_effects_ok)
                    },
                    action = effects.firstOrNull()?.let { e -> stringResource(R.string.checkup_effects_open, e.name) to { SystemEffects.open(context, e); Unit } },
                    minor = effects.isEmpty(),
                )
                CheckRow(
                    batteryOk,
                    stringResource(R.string.checkup_battery),
                    stringResource(if (batteryOk) R.string.checkup_battery_ok else R.string.checkup_battery_saving),
                    action = if (!batteryOk) stringResource(R.string.device_allow_background) to {
                        @SuppressLint("BatteryLife")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                        com.sadurni.attacca.ui.common.AppPermissions.openOrAppSettings(context, intent)
                    } else null,
                    minor = true,
                )
                CheckRow(
                    untuned.isEmpty(),
                    stringResource(R.string.checkup_devices),
                    if (untuned.isEmpty()) stringResource(R.string.checkup_devices_ok)
                    else stringResource(R.string.checkup_devices_missing, untuned.joinToString { it.name }),
                    action = if (untuned.isNotEmpty()) stringResource(R.string.guide_open) to { guide = "sound" } else null,
                    minor = true,
                )
                SectionTitle(stringResource(R.string.checkup_by_ear))
                Hint(stringResource(R.string.checkup_by_ear_hint))
                CategoryRow(Icons.Rounded.Tune, stringResource(R.string.guide_open), stringResource(R.string.checkup_sound_hint)) { guide = "sound" }
                CategoryRow(Icons.Rounded.SwapHoriz, stringResource(R.string.join_guide_open), stringResource(R.string.join_guide_hint)) { guide = "join" }
                CategoryRow(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.level_guide_open), stringResource(R.string.level_guide_hint)) { guide = "level" }
            }
        }
    }
    when (guide) {
        "sound" -> SoundGuide { guide = null }
        "join" -> TransitionsGuide { guide = null }
        "level" -> LevelGuide { guide = null }
    }
    if (showDamaged && folder != null) DamagedListDialog(folder) { showDamaged = false }
}

/** One thing checked: fine (✓) or to look at (!, or a lighter ! when it only may matter), what was found, and the fix. */
@Composable
private fun CheckRow(ok: Boolean, title: String, detail: String, action: Pair<String, () -> Unit>? = null, busy: Boolean = false, minor: Boolean = false) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    val color = when {
        ok -> Color(0xFF3E9E5A)
        minor -> Color(0xFFD9A21B)
        else -> accent
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(26.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.PriorityHigh, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = AttaccaType.body, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = AttaccaType.secondary, color = grayed)
            if (busy && !ok) {
                Text(stringResource(R.string.checkup_busy), fontSize = AttaccaType.secondary, color = accent)
            }
            action?.let { (label, run) ->
                OutlinedButton(onClick = run, modifier = Modifier.padding(top = 6.dp)) { Text(label) }
            }
        }
    }
}
