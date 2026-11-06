package com.sadurni.attacca.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log

/**
 * Sound processing outside Attacca that can change what it plays.
 *
 * - **Equalizer apps** attach effects to a player's audio session. Attacca never invites them (it
 *   does not announce its session), and [SessionEffectGuard] takes control of the standard effects
 *   on its session and keeps them off, so an app that finds the session anyway cannot use them.
 * - **Effects of the phone or of apps that work on the whole output** (Dolby and similar, or apps
 *   that capture and replay all audio) act after Attacca and cannot be switched off from here;
 *   they are only detected so the user can turn them off.
 */
object SystemEffects {

    /** An installed app that applies effects to other apps' sound. */
    data class EffectApp(val packageName: String, val name: String)

    /** Known equalizer and sound-effect apps (the manifest declares them in `<queries>`). */
    private val KNOWN_APPS = listOf(
        "org.lineageos.audiofx",
        "com.pittvandewitt.wavelet",
        "me.timschneeberger.rootlessjamesdsp",
        "james.dsp",
        "com.maxmpz.equalizer",
        "com.devdnua.equalizer.free",
        "com.devdnua.equalizer",
        "com.smartandroidapps.equalizer",
        "com.jazibkhan.equalizer",
        "com.dolby.daxappui",
        "com.dolby.dax2appUI",
        "com.motorola.dolby.dolbyui",
        "com.sec.android.app.soundalive",
        "com.sonyericsson.soundenhancement",
        "com.miui.misound",
        "com.oneplus.sound.tuner",
    )

    /** Enabled apps of [KNOWN_APPS] installed on this phone, by name. */
    fun installedApps(context: Context): List<EffectApp> {
        val pm = context.packageManager
        return KNOWN_APPS.mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            if (!info.enabled) return@mapNotNull null
            EffectApp(pkg, pm.getApplicationLabel(info).toString())
        }.sortedBy { it.name.lowercase() }
    }

    /** Opens [app] so its effects can be turned off; false when it has no screen of its own. */
    fun open(context: Context, app: EffectApp): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", app.packageName, null))
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /**
     * Sound processing built into the phone (for example Dolby) beyond the standard Android
     * effects: it can act on everything that plays, Attacca included.
     */
    fun builtInProcessing(): List<String> {
        val descriptors = runCatching { AudioEffect.queryEffects() }.getOrNull() ?: return emptyList()
        return descriptors
            .filter { d -> STANDARD_IMPLEMENTORS.none { d.implementor.orEmpty().contains(it, ignoreCase = true) } }
            .filter { d -> PROCESSING_WORDS.any { d.name.orEmpty().contains(it, ignoreCase = true) } }
            .map { "${it.name} (${it.implementor})" }
            .distinct()
    }

    private val STANDARD_IMPLEMENTORS = listOf("The Android Open Source Project", "NXP Software", "Google", "AOSP")
    private val PROCESSING_WORDS = listOf("dolby", "dax", "atmos", "dts", "dirac", "sound", "audio", "virtualizer", "equalizer", "bass", "surround", "spatial")
}

/**
 * Holds the standard Android effects of one audio session at the highest priority, disabled: an
 * app that attaches the same effect to the session gets a shared instance it does not control,
 * and cannot switch it on. Release it with the player.
 */
class SessionEffectGuard {

    private val effects = ArrayList<AudioEffect>()
    private var session = 0

    fun attach(audioSessionId: Int) {
        if (audioSessionId == session) return
        release()
        if (audioSessionId <= 0) return
        session = audioSessionId
        val makers = buildList<() -> AudioEffect> {
            add { Equalizer(Int.MAX_VALUE, audioSessionId) }
            add { BassBoost(Int.MAX_VALUE, audioSessionId) }
            add { Virtualizer(Int.MAX_VALUE, audioSessionId) }
            add { LoudnessEnhancer(audioSessionId) }
            if (Build.VERSION.SDK_INT >= 28) add { DynamicsProcessing(Int.MAX_VALUE, audioSessionId, null) }
        }
        for (make in makers) {
            // Not every phone has every effect; a missing one just stays unguarded.
            val effect = runCatching { make() }.getOrNull() ?: continue
            runCatching { effect.enabled = false }
            effect.setControlStatusListener { _, controlGranted ->
                // Whoever comes later with a higher priority wins; keep the effect off while we can.
                if (controlGranted) runCatching { effect.enabled = false }
            }
            effect.setEnableStatusListener { e, enabled ->
                if (enabled && e.hasControl()) runCatching { e.enabled = false }
            }
            effects.add(effect)
        }
        Log.d(TAG, "guarding ${effects.size} effects on session $audioSessionId")
    }

    fun release() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        session = 0
    }

    private companion object {
        const val TAG = "SystemEffects"
    }
}
