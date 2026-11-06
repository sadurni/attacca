package com.sadurni.attacca.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sadurni.attacca.settings.OutputDevice
import com.sadurni.attacca.settings.OutputType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks which output media is routed to, and reports devices that get connected (used to pick
 * the DSP profile and to resume a list per Bluetooth device).
 */
class OutputDeviceMonitor(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _current = MutableStateFlow<OutputDevice?>(null)
    val current: StateFlow<OutputDevice?> = _current.asStateFlow()

    private val _connected = MutableSharedFlow<OutputDevice>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Devices plugged in or paired while the app runs (not the ones present at start). */
    val connected: SharedFlow<OutputDevice> = _connected.asSharedFlow()

    private var initialized = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (initialized) {
                addedDevices.mapNotNull(::toOutputDevice)
                    .filter { it.type != OutputType.SPEAKER }
                    .distinctBy { it.key }
                    .forEach { _connected.tryEmit(it) }
            }
            initialized = true
            refreshNowAndLater()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshNowAndLater()
    }

    /** Routing can settle a moment after the device list changes (Bluetooth especially). */
    private fun refreshNowAndLater() {
        refresh()
        handler.postDelayed(::refresh, ROUTING_SETTLE_MS)
    }

    fun start() {
        // Registering delivers the devices already present as an initial "added" batch.
        audioManager.registerAudioDeviceCallback(callback, handler)
        refresh()
    }

    fun refresh() {
        _current.value = currentOutput()
    }

    private fun currentOutput(): OutputDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioManager.getAudioDevicesForAttributes(attributes).firstNotNullOfOrNull(::toOutputDevice)?.let {
                return it
            }
        }
        // Older versions: media goes to the most recently "external" device.
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull(::toOutputDevice)
            .maxByOrNull { PRIORITY.indexOf(it.type) }
    }

    private fun toOutputDevice(info: AudioDeviceInfo): OutputDevice? {
        val type = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputType.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_AUX_LINE,
            -> OutputType.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER,
            -> OutputType.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> OutputType.USB
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_DOCK,
            -> OutputType.OTHER
            // Earpiece, call (SCO), telephony, remote submix…: not media outputs.
            else -> return null
        }
        if (type == OutputType.SPEAKER) return OutputDevice(SPEAKER_KEY, info.productName.toString(), type)
        val name = info.productName?.toString().orEmpty().ifBlank { type.name }
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address.orEmpty() else ""
        // Wired jacks report a generic name; one rule for all of them is what users expect.
        val key = when (type) {
            OutputType.WIRED -> "WIRED"
            else -> "${type.name}|$name|$address"
        }
        return OutputDevice(key, name, type)
    }

    private companion object {
        const val SPEAKER_KEY = "SPEAKER"
        const val ROUTING_SETTLE_MS = 800L

        // Constants added in API 31; the values are stable.
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27

        val PRIORITY = listOf(OutputType.SPEAKER, OutputType.OTHER, OutputType.WIRED, OutputType.USB, OutputType.BLUETOOTH)
    }
}
