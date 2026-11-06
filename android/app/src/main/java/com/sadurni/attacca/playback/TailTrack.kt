package com.sadurni.attacca.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays a piece of audio on its own output track, apart from the player: the end of the track
 * being skipped, which goes on sounding while the player, which restarts its own output on every
 * skip, gets the next track going.
 */
object TailTrack {

    private const val TAG = "Crossfade"
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Plays [frames] of interleaved float [samples]; false when the output cannot take them.
     * [onLatency] gets, a moment later, how long after the call its first frame was heard (µs).
     */
    fun play(samples: FloatArray, frames: Int, channels: Int, sampleRate: Int, onLatency: (Long) -> Unit = {}): Boolean {
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return false
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * channels * 4)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "no track for the skip tail", e)
            return false
        }
        val done = Runnable { runCatching { track.stop() }; track.release() }
        return try {
            track.write(samples, 0, frames * channels, AudioTrack.WRITE_BLOCKING)
            val asked = System.nanoTime()
            track.play()
            handler.postDelayed({
                runCatching {
                    val ts = android.media.AudioTimestamp()
                    if (track.getTimestamp(ts) && ts.framePosition > 0) {
                        onLatency((ts.nanoTime - ts.framePosition * 1_000_000_000L / sampleRate - asked) / 1000)
                    }
                }
            }, LATENCY_CHECK_MS)
            handler.postDelayed(done, frames * 1000L / sampleRate + RELEASE_AFTER_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "the skip tail could not play", e)
            track.release()
            false
        }
    }

    private const val RELEASE_AFTER_MS = 500L
    private const val LATENCY_CHECK_MS = 300L
}
