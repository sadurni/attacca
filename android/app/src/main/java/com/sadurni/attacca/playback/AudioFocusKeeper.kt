package com.sadurni.attacca.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.sadurni.attacca.settings.FocusDuck

/**
 * Audio focus as the user wants it, in place of the player's own handling (which cannot be set):
 * a short sound of another app (a notification, a navigation prompt) lowers the volume, pauses or
 * is ignored ([duck]); after a call or another short interruption the music comes back or stays
 * paused ([resume]); another app starting to play always pauses it.
 */
class AudioFocusKeeper(
    context: Context,
    private val player: Player,
    private val duck: () -> FocusDuck,
    private val resume: () -> Boolean,
) : Player.Listener {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var requestedDuck: FocusDuck? = null

    /** Paused by a short loss of focus, to play again when it comes back. */
    private var pausedByLoss = false

    /** Focus is being given up or changed by us: its own callbacks are not losses. */
    private var holding = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change -> handler.post { onChange(change) } }

    fun start() {
        player.addListener(this)
        if (player.playWhenReady) take()
    }

    fun stop() {
        player.removeListener(this)
        give()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            pausedByLoss = false
            take()
        } else if (!pausedByLoss) {
            give()
        }
    }

    private fun take() {
        val mode = duck()
        if (holding && requestedDuck == mode) return
        if (holding) give()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // Lowering the volume is left to the system; pausing or ignoring needs the callback.
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(mode != FocusDuck.LOWER)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        request = r
        requestedDuck = mode
        holding = true
        if (audio.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            // A call is on: do not play over it.
            holding = false
            player.playWhenReady = false
        }
    }

    private fun give() {
        request?.let(audio::abandonAudioFocusRequest)
        request = null
        holding = false
    }

    private fun onChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app plays: stop for good (the user starts again).
                pausedByLoss = false
                player.playWhenReady = false
                give()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseForAWhile()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> when (duck()) {
                FocusDuck.PAUSE -> pauseForAWhile()
                // LOWER: the system lowers the volume; IGNORE: it plays on as it is.
                else -> Unit
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByLoss) {
                    pausedByLoss = false
                    if (resume()) player.playWhenReady = true else give()
                }
            }
        }
    }

    private fun pauseForAWhile() {
        if (!player.playWhenReady) return
        pausedByLoss = true
        player.playWhenReady = false
    }
}
