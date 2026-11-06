package com.sadurni.attacca.playback

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * The media volume ([AudioManager.STREAM_MUSIC], the default) or the notification volume
 * ([AudioManager.STREAM_NOTIFICATION]) of the current output, as a percentage of its steps.
 */
object DeviceVolume {

    /** Bluetooth devices restore their own volume a moment after connecting. */
    const val REAPPLY_DELAY_MS = 2_500L

    fun maxSteps(context: Context, stream: Int = AudioManager.STREAM_MUSIC): Int =
        context.getSystemService(AudioManager::class.java).getStreamMaxVolume(stream).coerceAtLeast(1)

    fun currentPercent(context: Context, stream: Int = AudioManager.STREAM_MUSIC): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.getStreamVolume(stream) * 100 / maxSteps(context, stream)
    }

    fun set(context: Context, percent: Int, stream: Int = AudioManager.STREAM_MUSIC) {
        val audio = context.getSystemService(AudioManager::class.java)
        var index = (percent.coerceIn(0, 100) * maxSteps(context, stream) / 100f).roundToInt()
        // Notifications at 0 would turn the phone silent, which needs Do Not Disturb access: the
        // lowest step instead.
        if (stream != AudioManager.STREAM_MUSIC) index = index.coerceAtLeast(1)
        // Refused in some states (e.g. fixed-volume devices, Do Not Disturb); nothing to do then.
        runCatching { audio.setStreamVolume(stream, index, 0) }
    }
}
