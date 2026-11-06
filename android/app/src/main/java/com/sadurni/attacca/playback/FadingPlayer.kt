package com.sadurni.attacca.playback

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps the player handed to the media session so pause, resume and skips fade the volume
 * instead of cutting, whoever triggers them (app, notification, headset, car); with [skipCrossfade]
 * a skip to another track blends into it instead (see [SkipCrossfade]). After a long pause
 * it also goes back a few seconds before resuming ([rewind]: after how many seconds of pause, how
 * many seconds back), to pick up the thread.
 */
@OptIn(UnstableApi::class)
class FadingPlayer(
    private val player: ExoPlayer,
    private val fadeMs: () -> Int,
    private val rewind: () -> Pair<Int, Int> = { 0 to 0 },
    /** Keeps when playback stopped, across restarts of the app. */
    private val pauseStore: SharedPreferences? = null,
    private val skipCrossfade: SkipCrossfade? = null,
) : ForwardingPlayer(player) {

    private val handler = Handler(Looper.getMainLooper())
    private var ramp: Runnable? = null

    /** Playback wanted and not held back (by a call, another app's sound…). */
    private fun playing() = player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var wasPlaying = playing()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                val now = playing()
                if (now != wasPlaying) {
                    wasPlaying = now
                    // Resumed without play() (after a call, when the sound comes back): go back now.
                    if (now) rewindAfterLongPause() else markPause()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Moved somewhere else while paused: resume there, as chosen.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !playing()) clearPause()
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !playing()) clearPause()
            }
        })
    }

    private fun markPause() {
        if (player.playbackState == Player.STATE_ENDED) return
        pauseStore?.edit()?.putLong(KEY_PAUSED_AT, System.currentTimeMillis())?.apply()
    }

    private fun clearPause() {
        pauseStore?.edit()?.remove(KEY_PAUSED_AT)?.apply()
    }

    /** Goes back when the pause was long enough (once per pause). */
    private fun rewindAfterLongPause() {
        val since = pauseStore?.getLong(KEY_PAUSED_AT, 0L) ?: 0L
        if (since == 0L) return
        clearPause()
        val (afterS, backS) = rewind()
        if (afterS <= 0 || backS <= 0) return
        if (System.currentTimeMillis() - since < afterS * 1000L) return
        val position = player.currentPosition
        if (position > 0) player.seekTo((position - backS * 1000L).coerceAtLeast(0L))
    }

    override fun play() {
        // Before sounding, so nothing of the old position is heard.
        if (!player.isPlaying) rewindAfterLongPause()
        val duration = fadeMs()
        cancelRamp()
        if (duration <= 0 || player.isPlaying) {
            player.volume = 1f
            player.play()
            return
        }
        player.volume = 0f
        player.play()
        rampTo(1f, duration) {}
    }

    override fun pause() {
        val duration = fadeMs()
        if (duration <= 0 || !player.isPlaying) {
            cancelRamp()
            player.pause()
            player.volume = 1f
            return
        }
        rampTo(0f, duration) {
            player.pause()
            player.volume = 1f
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun seekToNext() = fadedSkip(player.hasNextMediaItem()) { super.seekToNext() }

    override fun seekToNextMediaItem() = fadedSkip(player.hasNextMediaItem()) { super.seekToNextMediaItem() }

    override fun seekToPrevious() =
        // Past the first seconds, previous goes back to the start of the same track.
        fadedSkip(player.hasPreviousMediaItem() && player.currentPosition <= player.maxSeekToPreviousPosition) { super.seekToPrevious() }

    override fun seekToPreviousMediaItem() = fadedSkip(player.hasPreviousMediaItem()) { super.seekToPreviousMediaItem() }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        // Another track of the list chosen while playing: a skip like the buttons.
        if (mediaItemIndex != player.currentMediaItemIndex && skipCrossfade != null && player.isPlaying) {
            fadedSkip(true, onlyBlend = true) { super.seekTo(mediaItemIndex, positionMs) }
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    /** Fades out over [durationMs] and pauses (sleep timer). */
    fun fadeOutAndPause(durationMs: Int) {
        if (!player.isPlaying) return
        rampTo(0f, durationMs) {
            player.pause()
            player.volume = 1f
        }
    }

    /** A skip waiting for its blend to be ready, done at once if another one comes first. */
    private var pendingSkip: (() -> Unit)? = null

    /**
     * Skips with a blend when [toAnotherTrack] and a skip crossfade is set, else with a quick fade
     * (or, with [onlyBlend], as it is).
     */
    private fun fadedSkip(toAnotherTrack: Boolean, onlyBlend: Boolean = false, skip: () -> Unit) {
        pendingSkip?.let {
            pendingSkip = null
            it()
        }
        val crossfade = skipCrossfade
        if (crossfade != null && toAnotherTrack && player.isPlaying && crossfade.lengthMs() > 0) {
            cancelRamp()
            player.volume = 1f
            var done = false
            lateinit var run: () -> Unit
            run = {
                if (!done) {
                    done = true
                    if (pendingSkip === run) pendingSkip = null
                    skip()
                }
            }
            pendingSkip = run
            crossfade.prepare(SKIP_DIP_MS, cancelled = { done }) { ready ->
                if (done) return@prepare
                if (!ready) {
                    done = true
                    if (pendingSkip === run) pendingSkip = null
                    if (onlyBlend) skip() else plainFadedSkip(skip)
                    return@prepare
                }
                // The end of this track on an output of its own: once it sounds, the player fades
                // over to it (the same music, lined up), then skips.
                val lead = crossfade.startOwnTail(OWN_CROSS_MS)
                if (lead != null) {
                    handler.postDelayed({
                        if (!done) rampTo(0f, OWN_CROSS_MS) {
                            run()
                            rampTo(1f, SKIP_DIP_MS) {}
                        }
                    }, lead)
                    return@prepare
                }
                // A dip of a few milliseconds hides the seam where the blend takes over.
                rampTo(0f, SKIP_DIP_MS) {
                    run()
                    rampTo(1f, SKIP_DIP_MS) {}
                }
            }
            return
        }
        if (onlyBlend) skip() else plainFadedSkip(skip)
    }

    private fun plainFadedSkip(skip: () -> Unit) {
        val duration = fadeMs() / 2
        if (duration <= 0 || !player.isPlaying) {
            skip()
            return
        }
        rampTo(0f, duration) {
            skip()
            rampTo(1f, duration) {}
        }
    }

    private fun rampTo(target: Float, durationMs: Int, then: () -> Unit) {
        cancelRamp()
        val from = player.volume
        val start = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                player.volume = from + (target - from) * t
                if (t < 1f) {
                    handler.postDelayed(this, FRAME_MS)
                } else {
                    ramp = null
                    then()
                }
            }
        }
        ramp = step
        handler.post(step)
    }

    private fun cancelRamp() {
        ramp?.let(handler::removeCallbacks)
        ramp = null
    }

    private companion object {
        const val FRAME_MS = 16L
        const val SKIP_DIP_MS = 30

        /** Crossover from the player to the own track of a skip blend. */
        const val OWN_CROSS_MS = 60
        const val KEY_PAUSED_AT = "paused_at"
    }
}
