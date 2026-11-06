package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R
import com.sadurni.attacca.settings.DspProfile
import com.sadurni.attacca.settings.OutputType
import com.sadurni.attacca.settings.ProfilesState
import com.sadurni.attacca.ui.theme.LocalAccent

@Composable
fun profileName(profile: DspProfile?): String = when {
    profile == null -> stringResource(R.string.profile_type_default)
    profile.id == ProfilesState.DEFAULT_PROFILE_ID -> stringResource(R.string.profile_default)
    else -> profile.name
}

@Composable
fun outputTypeName(type: OutputType): String = stringResource(
    when (type) {
        OutputType.SPEAKER -> R.string.device_type_speaker
        OutputType.WIRED -> R.string.device_type_wired
        OutputType.BLUETOOTH -> R.string.device_type_bluetooth
        OutputType.USB -> R.string.device_type_usb
        OutputType.OTHER -> R.string.device_type_other
    },
)

/**
 * A labeled dropdown to pick a profile. With [allowInherit] the first entry is "device type
 * default" (null).
 */
@Composable
fun ProfilePicker(
    label: String,
    profiles: List<DspProfile>,
    selectedId: String?,
    allowInherit: Boolean,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
            Text(
                if (selected == null && !allowInherit) profileName(profiles.firstOrNull()) else profileName(selected),
                fontSize = AttaccaType.body,
                fontWeight = FontWeight.Bold,
                color = LocalAccent.current,
            )
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowInherit) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_type_default)) },
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                )
            }
            for (profile in profiles) {
                DropdownMenuItem(
                    text = { Text(profileName(profile)) },
                    onClick = {
                        open = false
                        onSelect(profile.id)
                    },
                )
            }
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = stringResource(R.string.profile_name),
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(label) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
