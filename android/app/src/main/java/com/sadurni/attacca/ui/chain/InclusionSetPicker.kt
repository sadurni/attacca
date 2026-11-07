package com.sadurni.attacca.ui.chain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.InclusionSets
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/** The name of the inclusion set in use, or "Everything" when none is. */
@Composable
fun activeSetName(): String {
    val chain by LocalContext.current.app.settings.chain.collectAsStateWithLifecycle()
    return InclusionSets.nameOf(chain, chain.activeSet) ?: stringResource(R.string.inclusion_everything)
}

/**
 * "Music: <set> ▾": which inclusion set is in use, changed with a tap (everything, or one of the
 * sets). Shown where lists are made; nothing when there are no sets.
 */
@Composable
fun InclusionSetPicker(modifier: Modifier = Modifier) {
    val settings = LocalContext.current.app.settings
    val chain by settings.chain.collectAsStateWithLifecycle()
    if (chain.inclusionSets.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.clickable { open = true }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.inclusion_set_label), fontSize = AttaccaType.body)
        Text(
            InclusionSets.nameOf(chain, chain.activeSet) ?: stringResource(R.string.inclusion_everything),
            fontSize = AttaccaType.body,
            fontWeight = FontWeight.SemiBold,
            color = LocalAccent.current,
            modifier = Modifier.padding(start = 6.dp),
        )
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = LocalAccent.current)
        DropdownMenu(open, { open = false }) {
            SetMenuItems { open = false }
        }
    }
}

/** The menu's items: everything, then each set, the one in use marked. */
@Composable
private fun SetMenuItems(onPicked: () -> Unit) {
    val settings = LocalContext.current.app.settings
    val chain by settings.chain.collectAsStateWithLifecycle()
    val mark = { on: Boolean -> if (on) "✓  " else "     " }
    DropdownMenuItem(
        text = { Text(mark(chain.activeSet == null) + stringResource(R.string.inclusion_everything)) },
        onClick = { InclusionSets.select(settings, null); onPicked() },
    )
    for (set in chain.inclusionSets) {
        DropdownMenuItem(
            text = { Text(mark(chain.activeSet == set.id) + set.name) },
            onClick = { InclusionSets.select(settings, set.id); onPicked() },
        )
    }
}

/** The same choice in a dialog, for menus that cannot hold a submenu. */
@Composable
fun InclusionSetDialog(onDismiss: () -> Unit) {
    val settings = LocalContext.current.app.settings
    val chain by settings.chain.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inclusion_sets_title)) },
        text = {
            Column {
                val rows = listOf<Pair<String?, String>>(null to stringResource(R.string.inclusion_everything)) + chain.inclusionSets.map { it.id to it.name }
                for ((id, name) in rows) {
                    com.sadurni.attacca.ui.settings.RadioRow(name, chain.activeSet == id) {
                        InclusionSets.select(settings, id)
                        onDismiss()
                    }
                }
                if (chain.inclusionSets.isEmpty()) {
                    Text(stringResource(R.string.inclusion_sets_none), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
