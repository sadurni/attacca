package com.sadurni.attacca.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.TasteGroup
import com.sadurni.attacca.settings.AppSettings
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlin.math.roundToInt

/** The four groups of a link a listener hears, with their names. */
private val GROUPS = listOf(
    TasteGroup.TEMPO to R.string.taste_group_tempo,
    TasteGroup.SOUND to R.string.taste_group_sound,
    TasteGroup.ENERGY to R.string.taste_group_energy,
    TasteGroup.KEY to R.string.taste_group_key,
)

/** The key and notes' share that "Steps in tune" sets. */
private const val IN_TUNE = 0.4f

/** "Beat 33 % · Sound 45 % · …" of [taste]. */
@Composable
internal fun tasteSummary(taste: ChainTaste): String =
    GROUPS.map { (group, label) -> "${stringResource(label)} ${(taste.share(group) * 100).roundToInt()} %" }.joinToString(" · ")

/**
 * Chaining by hand (Settings → Chaining): what counts most when one song leads into the next —
 * the beat, the sound, the energy, the key and notes — set with sliders (the chaining guide sets
 * the same by ear), and kept as named profiles to choose when a list is made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChainByHand(settings: AppSettings) {
    val taste by settings.chainTaste.collectAsStateWithLifecycle()
    val profiles by settings.tasteProfiles.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<com.sadurni.attacca.settings.TasteProfile?>(null) }

    SectionTitle(stringResource(R.string.chain_by_hand))
    for ((group, label) in GROUPS) {
        ValueSlider(
            label = stringResource(label),
            value = (taste.share(group) * 100).roundToInt().toFloat(),
            range = 0f..100f,
            step = 5f,
            format = { "${it.roundToInt()} %" },
            onChange = { v -> settings.setChainTaste(taste.withShare(group, v / 100f)) },
        )
    }
    Hint(stringResource(R.string.chain_by_hand_hint))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = { naming = true }) { Text(stringResource(R.string.taste_save_profile)) }
        // The key and notes at 40 %: four steps in five with keys that go together (measured on
        // real libraries: half of them by default), the beat and the sound still counting.
        if (taste.share(TasteGroup.KEY) < IN_TUNE - 0.01f) {
            TextButton(onClick = { settings.setChainTaste(taste.withShare(TasteGroup.KEY, IN_TUNE)) }) { Text(stringResource(R.string.taste_in_tune)) }
        }
        if (!taste.isDefault) TextButton(onClick = { settings.setChainTaste(ChainTaste.DEFAULT) }) { Text(stringResource(R.string.taste_reset)) }
    }

    if (profiles.isNotEmpty()) {
        Text(stringResource(R.string.taste_profiles), fontSize = AttaccaType.secondary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        for (p in profiles) {
            Row(
                Modifier.fillMaxWidth().clickable { settings.setChainTaste(p.taste) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = AttaccaType.body)
                    Text(tasteSummary(p.taste), fontSize = AttaccaType.caption, color = LocalAttaccaColors.current.textGrayed)
                }
                IconButton(onClick = { deleting = p }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
            }
        }
        Hint(stringResource(R.string.taste_profiles_hint))
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.taste_save_profile)) },
            text = {
                Column {
                    Text(tasteSummary(taste), fontSize = AttaccaType.secondary)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.taste_profile_name)) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    settings.saveTasteProfile(name.trim(), taste)
                    naming = false
                }) { Text(stringResource(R.string.smart_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(p.name) },
            text = { Text(stringResource(R.string.taste_profile_delete_ask)) },
            confirmButton = {
                TextButton(onClick = {
                    settings.deleteTasteProfile(p.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
