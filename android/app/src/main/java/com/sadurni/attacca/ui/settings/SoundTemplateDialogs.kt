package com.sadurni.attacca.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.OutputDevice
import com.sadurni.attacca.settings.SoundTemplate

/** Picks a sound template: [message] says what it will apply to. */
@Composable
fun TemplateDialog(
    title: String,
    message: String,
    initial: SoundTemplate,
    onApply: (SoundTemplate) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.cancel),
    /** Opens the sound guide instead; null: no such button. */
    onTune: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message)
                TemplateChoices(selected) { selected = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(selected) }) { Text(stringResource(R.string.template_apply)) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (onTune != null) TextButton(onClick = onTune) { Text(stringResource(R.string.guide_step_by_step)) }
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            }
        },
    )
}

/**
 * When an output is used for the first time (headphones, a car, a speaker…), a sound template is
 * offered for it: applied, it becomes that device's own profile.
 */
@Composable
fun NewDeviceTemplatePrompt() {
    val store = LocalContext.current.app.settings.profiles
    // The guide stays open once the prompt has gone.
    var guide by remember { mutableStateOf(false) }
    if (guide) SoundGuide { guide = false }
    val device: OutputDevice = store.newDevice.collectAsStateWithLifecycle().value ?: return
    TemplateDialog(
        title = stringResource(R.string.template_new_device_title),
        message = stringResource(R.string.template_new_device, device.name),
        initial = SoundTemplate.suggestedFor(device.type),
        onApply = { template ->
            store.applyTemplate(template, device.name, device.key, null)
            store.dismissNewDevice()
        },
        onDismiss = store::dismissNewDevice,
        dismissLabel = stringResource(R.string.template_not_now),
        onTune = {
            store.dismissNewDevice()
            guide = true
        },
    )
}
