package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.playback.SystemEffects
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun warningPrefs(context: Context) = context.getSharedPreferences("effects_warning", Context.MODE_PRIVATE)

/**
 * Once for each new set of equalizer apps found on the phone: they can change the sound, and
 * how to keep them out. "Don't warn again" stops it for good.
 */
@Composable
fun EffectAppsWarning() {
    val context = LocalContext.current
    val apps by produceState<List<SystemEffects.EffectApp>?>(null) {
        value = withContext(Dispatchers.IO) { SystemEffects.installedApps(context) }
    }
    var dismissed by remember { mutableStateOf(false) }
    val found = apps ?: return
    if (found.isEmpty() || dismissed) return
    val prefs = warningPrefs(context)
    val signature = found.joinToString(",") { it.packageName }
    if (prefs.getBoolean(KEY_NEVER, false) || prefs.getString(KEY_WARNED, null) == signature) return
    val close = { never: Boolean ->
        prefs.edit().putString(KEY_WARNED, signature).putBoolean(KEY_NEVER, never).apply()
        dismissed = true
    }
    AlertDialog(
        onDismissRequest = { close(false) },
        title = { Text(stringResource(R.string.effects_warning_title)) },
        text = {
            Column {
                Text(stringResource(R.string.effects_warning_text, found.joinToString(", ") { it.name }))
                for (app in found) {
                    TextButton(onClick = { SystemEffects.open(context, app) }) {
                        Text(stringResource(R.string.effects_open_app, app.name), color = LocalAccent.current)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { close(false) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { close(true) }) { Text(stringResource(R.string.effects_warning_never)) } },
    )
}

/** Settings → Playback: other sound processing on the phone and how Attacca keeps it out. */
@Composable
fun SystemEffectsSection(settings: com.sadurni.attacca.settings.AppSettings) {
    val context = LocalContext.current
    val playback by settings.playback.collectAsStateWithLifecycle()
    val colors = LocalAttaccaColors.current
    SectionTitle(stringResource(R.string.section_system_effects))
    SwitchRow(stringResource(R.string.effects_guard), playback.guardEffects, { v ->
        settings.updatePlayback { it.copy(guardEffects = v) }
    })
    Hint(stringResource(R.string.effects_guard_hint))
    val found by produceState<Pair<List<SystemEffects.EffectApp>, List<String>>?>(null) {
        value = withContext(Dispatchers.IO) { SystemEffects.installedApps(context) to SystemEffects.builtInProcessing() }
    }
    val (apps, builtIn) = found ?: return
    if (apps.isEmpty() && builtIn.isEmpty()) {
        Hint(stringResource(R.string.effects_none_found))
        return
    }
    if (apps.isNotEmpty()) {
        Text(
            stringResource(R.string.effects_apps_found),
            fontSize = AttaccaType.body,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        for (app in apps) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
                TextButton(onClick = { SystemEffects.open(context, app) }) {
                    Text(stringResource(R.string.effects_open), color = LocalAccent.current)
                }
            }
        }
    }
    if (builtIn.isNotEmpty()) {
        Text(
            stringResource(R.string.effects_builtin_found),
            fontSize = AttaccaType.body,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        for (name in builtIn) {
            Text(name, fontSize = AttaccaType.secondary, color = colors.textGrayed, modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
        }
    }
    Hint(stringResource(R.string.effects_turn_off_hint))
}

private const val KEY_WARNED = "warned"
private const val KEY_NEVER = "never"
