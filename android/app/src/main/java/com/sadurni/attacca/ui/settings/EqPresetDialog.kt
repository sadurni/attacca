package com.sadurni.attacca.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.settings.EqPresets
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/** Picks a preset for the graphic equalizer ([onPick] gets its gains), or saves the current bands as one. */
@Composable
fun EqPresetDialog(current: List<Float>, onPick: (List<Float>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var own by remember { mutableStateOf(EqPresets.own(context)) }
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.eq_preset_save),
            label = stringResource(R.string.eq_preset_name),
            initial = "",
            onConfirm = { name ->
                EqPresets.save(context, name, current)
                own = EqPresets.own(context)
                naming = false
            },
            onDismiss = { naming = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_presets)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (own.isNotEmpty()) {
                    item { Heading(stringResource(R.string.eq_presets_own)) }
                    items(own, key = { "own:" + it.first }) { (name, gains) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                fontSize = AttaccaType.heading,
                                modifier = Modifier.weight(1f).clickable { onPick(gains) }.padding(vertical = 10.dp),
                            )
                            IconButton(onClick = {
                                EqPresets.delete(context, name)
                                own = EqPresets.own(context)
                            }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.eq_preset_delete)) }
                        }
                    }
                    item { Heading(stringResource(R.string.eq_presets_built_in)) }
                }
                items(EqPresets.builtIn, key = { "built:" + it.nameRes }) { preset ->
                    Text(
                        stringResource(preset.nameRes),
                        fontSize = AttaccaType.heading,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(preset.gains) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = true }) { Text(stringResource(R.string.eq_preset_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed, modifier = Modifier.padding(top = 8.dp))
}
