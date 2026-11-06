package com.sadurni.attacca.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** One hearing of a track: what the statistics and the listening log record. */
data class Listen(val mediaId: String, val startedAtMs: Long, val durationMs: Long)

/**
 * Decides when a track counts as played or skipped, from the time actually heard (seeking ahead
 * does not count, pauses do not count, speed does). Played: half the track or four minutes heard,
 * whichever comes first, counted the moment it is reached. Skipped: the user moved to another
 * track before that, after hearing at least [MIN_HEARD_FOR_SKIP_MS]. Clock-driven and free of the
 * player, so it can be tested on its own; [ListeningTracker] feeds it.
 */
class ListenCounter(
    private val onPlayed: (Listen) -> Unit,
    private val onSkipped: (Listen) -> Unit,
) {
    private var mediaId: String? = null
    private var startedAtMs = 0L
    private var durationMs = C.TIME_UNSET
    private var heardMs = 0.0
    private var playingSince = -1L
    private var speed = 1f
    private var counted = false

    /** A new hearing of [id] begins (not yet sounding). */
    fun start(id: String?, now: Long, wallNow: Long, playing: Boolean) {
        mediaId = id
        startedAtMs = wallNow
        durationMs = C.TIME_UNSET
        heardMs = 0.0
        counted = false
        playingSince = if (playing && id != null) now else -1L
    }

    fun setDuration(ms: Long) {
        if (ms > 0) durationMs = ms
    }

    fun setPlaying(playing: Boolean, now: Long, wallNow: Long) {
        if (playing) {
            if (playingSince < 0) {
                // The first sound of a hearing that was loaded paused (e.g. a resumed list).
                if (heardMs == 0.0) startedAtMs = wallNow
                playingSince = now
            }
        } else {
            accumulate(now)
            playingSince = -1L
        }
    }

    fun setSpeed(newSpeed: Float, now: Long) {
        accumulate(now)
        speed = newSpeed
    }

    /** The current hearing ends because the player moved on; [byUser] when the user chose another track. */
    fun leave(now: Long, byUser: Boolean) {
        accumulate(now)
        check(now)
        val id = mediaId ?: return
        if (byUser && !counted && heardMs >= MIN_HEARD_FOR_SKIP_MS) onSkipped(Listen(id, startedAtMs, durationMs))
        mediaId = null
    }

    /** Counts the play if enough has been heard by [now]. */
    fun check(now: Long) {
        if (counted) return
        val id = mediaId ?: return
        val threshold = thresholdMs() ?: return
        if (heardNow(now) >= threshold) {
            counted = true
            onPlayed(Listen(id, startedAtMs, durationMs))
        }
    }

    /** Milliseconds of real time until the play would count; null when nothing is pending. */
    fun dueInMs(now: Long): Long? {
        if (counted || mediaId == null || playingSince < 0) return null
        val threshold = thresholdMs() ?: return UNKNOWN_DURATION_RETRY_MS
        val left = threshold - heardNow(now)
        return maxOf(0L, kotlin.math.ceil(left / speed.coerceAtLeast(0.1f)).toLong())
    }

    private fun thresholdMs(): Double? {
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return null
        return minOf(durationMs / 2.0, PLAYED_CAP_MS.toDouble())
    }

    private fun heardNow(now: Long): Double = heardMs + if (playingSince >= 0) (now - playingSince) * speed.toDouble() else 0.0

    private fun accumulate(now: Long) {
        if (playingSince >= 0) {
            heardMs += (now - playingSince) * speed.toDouble()
            playingSince = now
        }
    }

    companion object {
        const val PLAYED_CAP_MS = 4 * 60_000L
        const val MIN_HEARD_FOR_SKIP_MS = 1_000.0
        private const val UNKNOWN_DURATION_RETRY_MS = 2_000L
    }
}

/** Feeds a [ListenCounter] from the player's events; one wake-up per track, when the play is due. */
class ListeningTracker(
    private val player: Player,
    private val handler: Handler,
    onPlayed: (Listen) -> Unit,
    onSkipped: (Listen) -> Unit,
) : Player.Listener {

    private val counter = ListenCounter(onPlayed, onSkipped)
    private var currentId: String? = null

    private val due = Runnable { tick() }

    fun start() {
        player.addListener(this)
        currentId = player.currentMediaItem?.mediaId
        counter.start(currentId, SystemClock.elapsedRealtime(), System.currentTimeMillis(), player.isPlaying)
        counter.setSpeed(player.playbackParameters.speed, SystemClock.elapsedRealtime())
        schedule()
    }

    fun stop() {
        handler.removeCallbacks(due)
        counter.check(SystemClock.elapsedRealtime())
        player.removeListener(this)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        counter.setDuration(knownDuration())
        counter.setPlaying(isPlaying, now, System.currentTimeMillis())
        counter.check(now)
        schedule()
    }

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        counter.setSpeed(playbackParameters.speed, SystemClock.elapsedRealtime())
        schedule()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val now = SystemClock.elapsedRealtime()
        val id = mediaItem?.mediaId
        val userChoice = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        // Re-clipping the playing track (a copy put in its place) or the list being reloaded
        // around it: the same hearing goes on.
        if (id != null && id == currentId && userChoice) {
            schedule()
            return
        }
        counter.leave(now, byUser = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        currentId = id
        counter.start(id, now, System.currentTimeMillis(), player.isPlaying)
        schedule()
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        counter.setDuration(knownDuration())
        schedule()
    }

    private fun tick() {
        counter.setDuration(knownDuration())
        counter.check(SystemClock.elapsedRealtime())
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(due)
        counter.setDuration(knownDuration())
        val delay = counter.dueInMs(SystemClock.elapsedRealtime()) ?: return
        handler.postDelayed(due, delay + 50)
    }

    private fun knownDuration(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: C.TIME_UNSET
}
