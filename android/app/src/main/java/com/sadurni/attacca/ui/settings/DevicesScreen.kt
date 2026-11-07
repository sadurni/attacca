package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.AttaccaShapes
import com.sadurni.attacca.ui.common.tipAnchor
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.playback.DeviceVolume
import com.sadurni.attacca.settings.DeviceRule
import com.sadurni.attacca.settings.OutputType
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlin.math.roundToInt

/** Profile per device type and per device, and the list each device continues. */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val store = app.settings.profiles
    val state by store.state.collectAsStateWithLifecycle()
    val current by store.currentDevice.collectAsStateWithLifecycle()
    val currentList by app.currentListSource.collectAsStateWithLifecycle()
    val noListMessage = stringResource(R.string.device_no_current_list)
    val useCurrentList = { rule: DeviceRule ->
        val list = currentList
        if (list == null) {
            Toast.makeText(context, noListMessage, Toast.LENGTH_LONG).show()
        } else {
            store.setResumeList(rule.key, list, rule.autoPlay)
        }
    }

    // Each device is a block that folds; the one playing now starts open.
    val currentKey = state.rule(current?.key)?.key
    var opened by remember { mutableStateOf<Set<String>?>(null) }
    val open = opened ?: setOfNotNull(currentKey)
    val allKeys = state.devices.map { it.key }.toSet()
    val toggle = { key: String -> opened = if (key in open) open - key else open + key }
    val allOpen = allKeys.isNotEmpty() && open.containsAll(allKeys)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.devices_title), onBack) {
            IconButton(onClick = { opened = if (allOpen) emptySet() else allKeys }) {
                Icon(
                    if (allOpen) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                    contentDescription = stringResource(if (allOpen) R.string.devices_collapse_all else R.string.devices_expand_all),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hint(stringResource(R.string.devices_hint))

            SectionTitle(stringResource(R.string.devices_types))
            for (type in listOf(OutputType.SPEAKER, OutputType.WIRED, OutputType.BLUETOOTH, OutputType.USB)) {
                ProfilePicker(
                    label = outputTypeName(type),
                    profiles = state.profiles,
                    selectedId = state.typeDefaults[type],
                    allowInherit = false,
                    onSelect = { id -> store.setTypeDefault(type, id) },
                )
            }

            state.devices.firstOrNull { it.type == OutputType.SPEAKER }?.let { speaker ->
                SectionTitle(outputTypeName(OutputType.SPEAKER))
                DeviceBlock(speaker, isCurrent = speaker.key == currentKey, open = speaker.key in open, onToggle = { toggle(speaker.key) }) {
                    ListSetting(
                        rule = speaker,
                        onUseCurrentList = { useCurrentList(speaker) },
                        onClearList = { store.setResumeList(speaker.key, null, false) },
                        onAutoPlay = { auto -> store.setResumeList(speaker.key, speaker.resumeList, auto) },
                        onFollow = { follow -> store.setFollowLastList(speaker.key, follow) },
                    )
                    VolumeSetting(speaker) { store.setVolume(speaker.key, it) }
                }
            }

            SectionTitle(stringResource(R.string.devices_known))
            val devices = state.devices.filter { it.type != OutputType.SPEAKER }
            if (devices.isEmpty()) Hint(stringResource(R.string.devices_empty))
            for (rule in devices) {
                androidx.compose.foundation.layout.Box(Modifier.tipAnchor("devices.first", enabled = rule == devices.first())) {
                DeviceCard(
                    rule = rule,
                    isCurrent = rule.key == currentKey,
                    open = rule.key in open,
                    onToggle = { toggle(rule.key) },
                    onProfile = { id -> store.assignProfile(rule.key, id) },
                    onUseCurrentList = { useCurrentList(rule) },
                    onClearList = { store.setResumeList(rule.key, null, false) },
                    onAutoPlay = { auto -> store.setResumeList(rule.key, rule.resumeList, auto) },
                    onFollow = { follow -> store.setFollowLastList(rule.key, follow) },
                    onVolume = { percent -> store.setVolume(rule.key, percent) },
                    onForget = { store.forgetDevice(rule.key) },
                )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceCard(
    rule: DeviceRule,
    isCurrent: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onProfile: (String?) -> Unit,
    onUseCurrentList: () -> Unit,
    onClearList: () -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onFollow: (Boolean) -> Unit,
    onVolume: (Int?) -> Unit,
    onForget: () -> Unit,
) {
    val profiles by LocalContext.current.app.settings.profiles.state.collectAsStateWithLifecycle()
    DeviceBlock(rule, isCurrent, open, onToggle) {
        ProfilePicker(
            label = stringResource(R.string.section_profile),
            profiles = profiles.profiles,
            selectedId = rule.profileId,
            allowInherit = true,
            onSelect = onProfile,
        )
        ListSetting(rule, onUseCurrentList, onClearList, onAutoPlay, onFollow)
        VolumeSetting(rule, onVolume)
        DeviceInclusionSetting(rule)
        OutlinedButton(onClick = onForget, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.device_forget))
        }
    }
}

/** A device's frame: name and a one-line summary always shown; its settings only when open. */
@Composable
private fun DeviceBlock(
    rule: DeviceRule,
    isCurrent: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val profiles by LocalContext.current.app.settings.profiles.state.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val summary = buildList {
        add(outputTypeName(rule.type))
        if (isCurrent) add(stringResource(R.string.device_current))
        profiles.profile(rule.profileId)?.let { add(it.name) }
        rule.resumeList?.let { add(it.name) }
        rule.volumePercent?.let { add("♪ $it %") }
        rule.notificationPercent?.let { add("🔔 $it %") }
    }.joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, if (isCurrent) accent else LocalAttaccaColors.current.surface, AttaccaShapes.medium)
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, fontSize = AttaccaType.heading, fontWeight = FontWeight.Bold)
                Text(
                    summary,
                    fontSize = AttaccaType.secondary,
                    color = LocalAttaccaColors.current.textGrayed,
                    maxLines = if (open) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (open) R.string.device_collapse else R.string.device_expand),
            )
        }
        if (open) content()
    }
}

/** The list a device continues: on connection, and when the player opens with it as output. */
@Composable
private fun ListSetting(
    rule: DeviceRule,
    onUseCurrentList: () -> Unit,
    onClearList: () -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onFollow: (Boolean) -> Unit,
) {
    Text(
        stringResource(R.string.device_list, rule.resumeList?.name ?: stringResource(R.string.device_list_none)),
        fontSize = AttaccaType.body,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUseCurrentList) { Text(stringResource(R.string.device_use_current_list)) }
        if (rule.resumeList != null) {
            OutlinedButton(onClick = onClearList) { Text(stringResource(R.string.device_clear_list)) }
        }
    }
    SwitchRow(stringResource(R.string.device_follow_last_list), rule.followLastList, onFollow)
    // The speaker is never "connected", so there is nothing to start playing on.
    if (rule.resumeList != null && rule.type != OutputType.SPEAKER) {
        SwitchRow(stringResource(R.string.device_autoplay), rule.autoPlay, onAutoPlay)
        if (rule.autoPlay && rule.type == OutputType.BLUETOOTH) BackgroundStartSetting()
    }
}

/**
 * Starting a list when a Bluetooth device connects with the player closed needs the Bluetooth
 * connection broadcast (nearby devices permission), leave to open the app's screen from the
 * background (display over other apps) and to start from the background (no battery
 * optimization).
 */
@Composable
private fun BackgroundStartSetting() {
    val context = LocalContext.current
    var check by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { check++ }
    val requestBluetooth = com.sadurni.attacca.ui.common.rememberPermissionRequest(Manifest.permission.BLUETOOTH_CONNECT) { check++ }
    val bluetoothOk = remember(check) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(check) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val overlayOk = remember(check) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)
    }
    if (bluetoothOk && batteryOk && overlayOk) {
        Hint(stringResource(R.string.device_autoplay_closed_ready))
        return
    }
    Hint(stringResource(R.string.device_autoplay_closed_hint))
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!bluetoothOk) {
            OutlinedButton(onClick = requestBluetooth) {
                Text(stringResource(R.string.device_allow_bluetooth))
            }
        }
        if (!batteryOk) {
            OutlinedButton(onClick = {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                com.sadurni.attacca.ui.common.AppPermissions.openOrAppSettings(context, intent)
            }) { Text(stringResource(R.string.device_allow_background)) }
        }
        if (!overlayOk) {
            OutlinedButton(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                com.sadurni.attacca.ui.common.AppPermissions.openOrAppSettings(context, intent)
            }) { Text(stringResource(R.string.device_allow_overlay)) }
        }
    }
}

/** The inclusion set put in use when this device becomes the output (or left alone). */
@Composable
private fun DeviceInclusionSetting(rule: DeviceRule) {
    val settings = LocalContext.current.app.settings
    val chain by settings.chain.collectAsStateWithLifecycle()
    if (chain.inclusionSets.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val leave = stringResource(R.string.device_inclusion_leave)
    val everything = stringResource(R.string.inclusion_everything)
    val current = when (rule.inclusionSet) {
        null -> leave
        DeviceRule.EVERYTHING -> everything
        else -> com.sadurni.attacca.settings.InclusionSets.nameOf(chain, rule.inclusionSet) ?: leave
    }
    androidx.compose.foundation.layout.Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.device_inclusion), fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
            Text(current, fontSize = AttaccaType.body, color = com.sadurni.attacca.ui.theme.LocalAccent.current)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            val choices = listOf<Pair<String?, String>>(null to leave, DeviceRule.EVERYTHING to everything) + chain.inclusionSets.map { it.id to it.name }
            for ((id, name) in choices) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = {
                    settings.profiles.setInclusionSet(rule.key, id)
                    open = false
                })
            }
        }
    }
    Hint(stringResource(R.string.device_inclusion_hint))
}

/** Fixed media and notification volumes for a device: set each time it becomes the output. */
@Composable
private fun VolumeSetting(rule: DeviceRule, onChange: (Int?) -> Unit) {
    val store = LocalContext.current.app.settings.profiles
    StreamVolumeSetting(
        rule.volumePercent, android.media.AudioManager.STREAM_MUSIC,
        stringResource(R.string.device_fixed_volume), stringResource(R.string.device_volume), stringResource(R.string.device_volume_hint), onChange,
    )
    StreamVolumeSetting(
        rule.notificationPercent, android.media.AudioManager.STREAM_NOTIFICATION,
        stringResource(R.string.device_fixed_notification_volume), stringResource(R.string.device_notification_volume),
        stringResource(R.string.device_notification_volume_hint),
    ) { store.setNotificationVolume(rule.key, it) }
}

@Composable
private fun StreamVolumeSetting(percentSet: Int?, stream: Int, switchLabel: String, sliderLabel: String, hint: String, onChange: (Int?) -> Unit) {
    val context = LocalContext.current
    val steps = remember { DeviceVolume.maxSteps(context, stream) }
    SwitchRow(switchLabel, percentSet != null, { on ->
        onChange(if (on) DeviceVolume.currentPercent(context, stream) else null)
    })
    val percent = percentSet ?: return
    ValueSlider(
        label = sliderLabel,
        value = (percent * steps / 100f).roundToInt().toFloat(),
        range = 0f..steps.toFloat(),
        step = 1f,
        format = { "${(it * 100 / steps).roundToInt()} %" },
        onChange = { index -> onChange((index * 100 / steps).roundToInt()) },
    )
    OutlinedButton(
        onClick = { onChange(DeviceVolume.currentPercent(context, stream)) },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text(stringResource(R.string.device_volume_use_current)) }
    Hint(hint)
}
