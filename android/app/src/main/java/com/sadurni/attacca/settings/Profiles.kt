package com.sadurni.attacca.settings

import android.content.SharedPreferences
import com.sadurni.attacca.library.ListSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class OutputType { SPEAKER, WIRED, BLUETOOTH, USB, OTHER }

/** An audio output, identified precisely enough to tell two Bluetooth headphones apart. */
data class OutputDevice(val key: String, val name: String, val type: OutputType)

data class DspProfile(val id: String, val name: String, val settings: DspSettings)

/**
 * Whether two device keys ("TYPE|name|address") are the same physical device. Exact keys are not
 * stable: Android gives a Bluetooth address anonymised ("XX:XX:XX:XX:AB:CD") until the nearby
 * devices permission is granted and the full one after, and a USB device's address changes with
 * each plug. So Bluetooth devices are matched by the last two bytes of the address (or the name
 * when there is none) and USB devices by name.
 */
object DeviceIdentity {

    private class Parts(val type: String, val name: String, val address: String)

    private fun parse(key: String): Parts {
        val p = key.split("|", limit = 3)
        return Parts(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
    }

    /** The last two bytes of a MAC address ("AB:CD"), uppercase; null when unusable. */
    fun addressTail(address: String): String? {
        val tail = address.trim().uppercase().takeLast(5)
        return tail.takeIf { it.length == 5 && it[2] == ':' && !it.contains('X') }
    }

    fun sameDevice(a: String, b: String): Boolean {
        if (a == b) return true
        val pa = parse(a)
        val pb = parse(b)
        if (pa.type != pb.type) return false
        return when (pa.type) {
            OutputType.BLUETOOTH.name -> {
                val ta = addressTail(pa.address)
                val tb = addressTail(pb.address)
                if (ta != null && tb != null) ta == tb else pa.name.equals(pb.name, ignoreCase = true)
            }
            OutputType.USB.name, OutputType.OTHER.name -> pa.name.equals(pb.name, ignoreCase = true)
            else -> false
        }
    }

    /** Whether [key] is the Bluetooth device with [address] (full or anonymised). */
    fun matchesAddress(key: String, address: String): Boolean {
        val p = parse(key)
        if (p.type != OutputType.BLUETOOTH.name) return false
        val tail = addressTail(address) ?: return false
        return addressTail(p.address) == tail
    }

    /**
     * [devices] with [device] remembered: duplicates of it (see [sameDevice]) are merged into
     * one rule under the device's current key, keeping the one with the most settings.
     */
    fun remember(devices: List<DeviceRule>, device: OutputDevice): List<DeviceRule> {
        val same = devices.filter { sameDevice(it.key, device.key) }
        if (same.isEmpty()) return devices + DeviceRule(device.key, device.name, device.type)
        val best = same.maxWith(compareBy<DeviceRule>({ configured(it) }, { it.key == device.key }))
        val merged = best.copy(key = device.key, name = device.name)
        if (same.size == 1 && best == merged) return devices
        val first = devices.indexOf(same.first())
        return devices.filterNot { it in same }.toMutableList().apply { add(first.coerceAtMost(size), merged) }
    }

    /** [devices] with the duplicates of each device merged, keeping the most configured rule. */
    fun dedupe(devices: List<DeviceRule>): List<DeviceRule> {
        val result = ArrayList<DeviceRule>()
        for (rule in devices) {
            val i = result.indexOfFirst { sameDevice(it.key, rule.key) }
            if (i < 0) result.add(rule) else if (configured(rule) > configured(result[i])) result[i] = rule
        }
        return result
    }

    private fun configured(rule: DeviceRule): Int =
        listOf(rule.profileId != null, rule.resumeList != null, rule.autoPlay, !rule.followLastList, rule.volumePercent != null, rule.notificationPercent != null, rule.inclusionSet != null)
            .count { it }
}

/** What to do with a specific device: which profile to use and which list to resume. */
data class DeviceRule(
    val key: String,
    val name: String,
    val type: OutputType,
    /** null: use the profile assigned to the device type. */
    val profileId: String? = null,
    /** List to continue when this device connects. */
    val resumeList: ListSource? = null,
    val autoPlay: Boolean = false,
    /** The list to continue follows the last list played on this device. */
    val followLastList: Boolean = true,
    /** Media volume set whenever this device becomes the output, in percent; null leaves it alone. */
    val volumePercent: Int? = null,
    /** Notification volume set whenever this device becomes the output, in percent; null leaves it alone. */
    val notificationPercent: Int? = null,
    /** The inclusion set put in use when this device becomes the output: a set's id, [EVERYTHING], or null to leave it alone. */
    val inclusionSet: String? = null,
) {
    companion object {
        /** [inclusionSet]: no set (everything may be used). */
        const val EVERYTHING = ""
    }
}

data class ProfilesState(
    val profiles: List<DspProfile>,
    /** Every device seen so far, with its rule. */
    val devices: List<DeviceRule>,
    val typeDefaults: Map<OutputType, String>,
) {
    fun profile(id: String?): DspProfile? = profiles.firstOrNull { it.id == id }
    fun rule(key: String?): DeviceRule? =
        key?.let { k -> devices.firstOrNull { it.key == k } ?: devices.firstOrNull { DeviceIdentity.sameDevice(it.key, k) } }

    /** Device rule → device type default → the default profile. */
    fun resolveProfile(device: OutputDevice?): DspProfile {
        val byDevice = profile(rule(device?.key)?.profileId)
        val byType = device?.let { profile(typeDefaults[it.type]) }
        return byDevice ?: byType ?: profile(DEFAULT_PROFILE_ID) ?: profiles.first()
    }

    companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}

/**
 * Named DSP profiles and per-device rules, persisted as one JSON document.
 *
 * The active profile follows the current output device; editing the sound settings edits the
 * active profile.
 */
class ProfileStore(private val prefs: SharedPreferences, legacySettings: () -> DspSettings) {

    private val _state = MutableStateFlow(load() ?: initialState(legacySettings()))
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    private val _currentDevice = MutableStateFlow<OutputDevice?>(null)
    val currentDevice: StateFlow<OutputDevice?> = _currentDevice.asStateFlow()

    private val _active = MutableStateFlow(_state.value.resolveProfile(null))
    val active: StateFlow<DspProfile> = _active.asStateFlow()

    private val _newDevice = MutableStateFlow<OutputDevice?>(null)

    /** An output seen for the first time (not the phone's speaker), to offer a sound template for. */
    val newDevice: StateFlow<OutputDevice?> = _newDevice.asStateFlow()

    fun dismissNewDevice() {
        _newDevice.value = null
    }

    /**
     * A profile with [template] applied over the active one's settings, named [name] (one of that
     * name already there is replaced), assigned to [deviceKey] or, without one, to [type].
     */
    fun applyTemplate(template: SoundTemplate, name: String, deviceKey: String?, type: OutputType?): DspProfile {
        val settings = template.applyTo(_active.value.settings)
        val existing = _state.value.profiles.firstOrNull { it.name == name && it.id != ProfilesState.DEFAULT_PROFILE_ID }
        val profile = existing?.copy(settings = settings) ?: DspProfile(UUID.randomUUID().toString(), name, settings)
        mutate { s ->
            s.copy(profiles = if (existing != null) s.profiles.map { if (it.id == profile.id) profile else it } else s.profiles + profile)
        }
        when {
            deviceKey != null -> assignProfile(deviceKey, profile.id)
            type != null -> setTypeDefault(type, profile.id)
        }
        return profile
    }

    /** [template] applied over the settings of the profile [id] itself. */
    fun applyTemplateToProfile(id: String, template: SoundTemplate) =
        mutate { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(settings = template.applyTo(it.settings)) else it }) }

    fun setCurrentDevice(device: OutputDevice?) {
        _currentDevice.value = device
        if (device != null) rememberDevice(device)
        recompute()
    }

    fun updateActiveSettings(transform: (DspSettings) -> DspSettings) {
        val id = _active.value.id
        mutate { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(settings = transform(it.settings)) else it }) }
    }

    /** Creates a profile copying [from] and assigns it to the current device. */
    fun createProfile(name: String, from: DspSettings): DspProfile {
        val profile = DspProfile(UUID.randomUUID().toString(), name, from)
        mutate { it.copy(profiles = it.profiles + profile) }
        _currentDevice.value?.let { assignProfile(it.key, profile.id) }
        return profile
    }

    fun renameProfile(id: String, name: String) =
        mutate { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = name) else it }) }

    fun deleteProfile(id: String) {
        if (id == ProfilesState.DEFAULT_PROFILE_ID) return
        mutate { s ->
            s.copy(
                profiles = s.profiles.filter { it.id != id },
                devices = s.devices.map { if (it.profileId == id) it.copy(profileId = null) else it },
                typeDefaults = s.typeDefaults.filterValues { it != id },
            )
        }
    }

    /** [profileId] null: fall back to the device type's profile. */
    fun assignProfile(deviceKey: String, profileId: String?) =
        mutate { s -> s.copy(devices = s.devices.map { if (it.key == deviceKey) it.copy(profileId = profileId) else it }) }

    fun setTypeDefault(type: OutputType, profileId: String?) =
        mutate { s ->
            val defaults = s.typeDefaults.toMutableMap()
            if (profileId == null) defaults.remove(type) else defaults[type] = profileId
            s.copy(typeDefaults = defaults)
        }

    fun setResumeList(deviceKey: String, list: ListSource?, autoPlay: Boolean) =
        mutate { s ->
            s.copy(devices = s.devices.map { if (it.key == deviceKey) it.copy(resumeList = list, autoPlay = autoPlay) else it })
        }

    fun setFollowLastList(deviceKey: String, follow: Boolean) =
        mutate { s -> s.copy(devices = s.devices.map { if (it.key == deviceKey) it.copy(followLastList = follow) else it }) }

    fun setVolume(deviceKey: String, percent: Int?) =
        mutate { s -> s.copy(devices = s.devices.map { if (it.key == deviceKey) it.copy(volumePercent = percent) else it }) }

    fun setNotificationVolume(deviceKey: String, percent: Int?) =
        mutate { s -> s.copy(devices = s.devices.map { if (it.key == deviceKey) it.copy(notificationPercent = percent) else it }) }

    fun setInclusionSet(deviceKey: String, set: String?) =
        mutate { s -> s.copy(devices = s.devices.map { if (it.key == deviceKey) it.copy(inclusionSet = set) else it }) }

    fun forgetDevice(deviceKey: String) = mutate { s -> s.copy(devices = s.devices.filter { it.key != deviceKey }) }

    private fun rememberDevice(device: OutputDevice) {
        val devices = _state.value.devices
        val updated = DeviceIdentity.remember(devices, device)
        val firstTime = devices.none { DeviceIdentity.sameDevice(it.key, device.key) }
        if (updated != devices) mutate { it.copy(devices = updated) }
        if (firstTime && device.type != OutputType.SPEAKER) _newDevice.value = device
    }

    private fun mutate(transform: (ProfilesState) -> ProfilesState) {
        _state.value = transform(_state.value)
        save(_state.value)
        recompute()
    }

    private fun recompute() {
        _active.value = _state.value.resolveProfile(_currentDevice.value)
    }

    private fun initialState(legacy: DspSettings) = ProfilesState(
        profiles = listOf(DspProfile(ProfilesState.DEFAULT_PROFILE_ID, DEFAULT_PROFILE_NAME, legacy)),
        devices = emptyList(),
        typeDefaults = emptyMap(),
    )

    private fun save(state: ProfilesState) {
        prefs.edit().putString(KEY, toJson(state).toString()).apply()
    }

    private fun load(): ProfilesState? = runCatching {
        prefs.getString(KEY, null)?.let { fromJson(JSONObject(it)) }
            // Earlier versions could save the same device twice under different keys.
            ?.let { it.copy(devices = DeviceIdentity.dedupe(it.devices)) }
    }.getOrNull()

    companion object {
        private const val KEY = "profiles_v1"

        /** Shown translated in the UI; stored as a plain name. */
        const val DEFAULT_PROFILE_NAME = "Default"

        fun toJson(state: ProfilesState): JSONObject {
            val profiles = JSONArray()
            state.profiles.forEach {
                profiles.put(JSONObject().put("id", it.id).put("name", it.name).put("settings", it.settings.toJson()))
            }
            val devices = JSONArray()
            state.devices.forEach {
                devices.put(
                    JSONObject()
                        .put("key", it.key)
                        .put("name", it.name)
                        .put("type", it.type.name)
                        .put("profileId", it.profileId ?: JSONObject.NULL)
                        .put("resumeList", it.resumeList?.toJson() ?: JSONObject.NULL)
                        .put("autoPlay", it.autoPlay)
                        .put("followLastList", it.followLastList)
                        .put("volumePercent", it.volumePercent ?: JSONObject.NULL)
                        .put("notificationPercent", it.notificationPercent ?: JSONObject.NULL)
                        .put("inclusionSet", it.inclusionSet ?: JSONObject.NULL),
                )
            }
            val defaults = JSONObject()
            state.typeDefaults.forEach { (type, id) -> defaults.put(type.name, id) }
            return JSONObject().put("profiles", profiles).put("devices", devices).put("typeDefaults", defaults)
        }

        fun fromJson(json: JSONObject): ProfilesState {
            val profilesJson = json.getJSONArray("profiles")
            val profiles = List(profilesJson.length()) { i ->
                val p = profilesJson.getJSONObject(i)
                DspProfile(p.getString("id"), p.getString("name"), DspSettings.fromJson(p.getJSONObject("settings")))
            }
            val devicesJson = json.optJSONArray("devices") ?: JSONArray()
            val devices = List(devicesJson.length()) { i ->
                val d = devicesJson.getJSONObject(i)
                DeviceRule(
                    key = d.getString("key"),
                    name = d.optString("name"),
                    type = OutputType.entries.firstOrNull { it.name == d.optString("type") } ?: OutputType.OTHER,
                    profileId = d.optString("profileId").takeIf { !d.isNull("profileId") && it.isNotEmpty() },
                    resumeList = ListSource.fromJson(d.optJSONObject("resumeList")),
                    autoPlay = d.optBoolean("autoPlay"),
                    followLastList = d.optBoolean("followLastList", true),
                    volumePercent = if (d.isNull("volumePercent") || !d.has("volumePercent")) null else d.optInt("volumePercent"),
                    notificationPercent = if (d.isNull("notificationPercent") || !d.has("notificationPercent")) null else d.optInt("notificationPercent"),
                    inclusionSet = if (d.isNull("inclusionSet") || !d.has("inclusionSet")) null else d.optString("inclusionSet"),
                )
            }
            val defaultsJson = json.optJSONObject("typeDefaults") ?: JSONObject()
            val typeDefaults = OutputType.entries
                .mapNotNull { type -> defaultsJson.optString(type.name).takeIf { it.isNotEmpty() }?.let { type to it } }
                .toMap()
            return ProfilesState(profiles, devices, typeDefaults)
        }
    }
}
