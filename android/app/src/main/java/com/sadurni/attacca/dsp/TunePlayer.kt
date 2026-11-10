package com.sadurni.attacca.dsp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.sadurni.attacca.tuning.TuneLoop

/**
 * The sound guide's own output: [loop] played to whatever the phone plays to now, on a thread of
 * its own. While it plays it holds the audio focus for a moment, so the music pauses (and, as the
 * user set it, comes back when the guide closes).
 */
class TunePlayer(context: Context) {

    val loop = TuneLoop(RATE)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Whether the sample is heard (false: paused). */
    @Volatile
    var playing = true

    fun start() {
        if (running) return
        running = true
        audio.requestAudioFocus(focus)
        thread = Thread({ run() }, "Attacca sound guide").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun run() {
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, BLOCK * 8 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            android.util.Log.w("SoundGuide", "No output", e)
            return
        }
        val buffer = FloatArray(BLOCK * 2)
        try {
            track.play()
            while (running) {
                if (playing) {
                    loop.read(buffer, BLOCK)
                } else {
                    java.util.Arrays.fill(buffer, 0f)
                }
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
            track.pause()
            track.flush()
        } finally {
            track.release()
        }
    }

    companion object {
        const val RATE = 48000
        private const val BLOCK = 1024
    }
}
