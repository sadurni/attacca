package com.sadurni.attaccaplg.bench

import com.sadurni.attaccaplg.audio.SoundSink
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.player.PlaybackEngine
import com.sadurni.attaccaplg.player.PlaybackSettings
import com.sadurni.attaccaplg.ui.PlayerState
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Development: plays tracks of a library through the player's engine into a file instead of the
 * sound card (raw float stereo at 44.1 kHz), from [START_BEFORE_END_MS] before the end of each but
 * the last, for checking joins (gaps, jumps) in Python. Also writes where each track began.
 * Args: library file, output file, then paths within the music folder.
 * Run with `gradlew playerCheck -PbenchArgs="db|out.f32|a.mp3|b.mp3|…"`.
 */
fun main(args: Array<String>) {
    val db = LibraryDb(File(args[0]))
    val root = db.musicRoot!!
    val all = db.all().associateBy { it.path }
    val items = args.drop(2).map { PlayerState.itemOf(root, all.getValue(it)) }
    val out = DataOutputStream(File(args[1]).outputStream().buffered())
    var frames = 0L
    val marks = StringBuilder()
    val sink = object : SoundSink {
        override val pending = 0L
        override fun write(samples: FloatArray, frames: Int, volume: Float) {
            val b = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * 2) b.putFloat(samples[i] * volume)
            out.write(b.array())
            written(frames)
            // Ten times real time: fast, and slow enough to follow the track changes.
            Thread.sleep(frames * 1000L / 44_100 / 10)
        }
        private fun written(n: Int) { frames += n }
        override fun flush() {}
        override fun pause() {}
        override fun resume() {}
        override fun drain() {}
        override fun close() {}
    }
    // Each track must open (else the engine skips it silently).
    for (item in items) runCatching { com.sadurni.attaccaplg.player.TrackReader(item, 0, null, 0f).close() }
        .onFailure { println("cannot open ${item.path}: $it") }
    // With a bit of everything on, as a hard case for the effects.
    val dsp = com.sadurni.attacca.settings.DspSettings(eqEnabled = true, eqGains = listOf(6f, 4f, 2f, 0f, 0f, 0f, 1f, 3f, 4f, 2f), bassEnabled = true, crossfeedEnabled = true, compressorEnabled = true, widthEnabled = true, widthPercent = 130)
    val engine = PlaybackEngine({ PlaybackSettings(crossfadeMs = 10_000) }, { dsp }, { sink })
    var lastIndex = -2
    engine.play(items, 0, (items[0].durationMs - START_BEFORE_END_MS).coerceAtLeast(0))
    val started = System.currentTimeMillis()
    while (System.currentTimeMillis() - started < 120_000) {
        val s = engine.snapshot
        if (s.index != lastIndex) {
            marks.append("track ${s.index} at frame $frames (${items.getOrNull(s.index)?.title})\n")
            lastIndex = s.index
            // Each later track: jump near its end too, so the check stays short.
            if (s.index in 1 until items.lastIndex) {
                Thread.sleep(300)
                engine.seek((items[s.index].durationMs - START_BEFORE_END_MS).coerceAtLeast(0))
            }
        }
        if (!s.playing && s.index == items.lastIndex) break
        Thread.sleep(20)
    }
    engine.close()
    out.close()
    File(args[1] + ".txt").writeText(marks.toString() + "end at frame $frames\n")
    print(marks)
    println("end at frame $frames")
}

private const val START_BEFORE_END_MS = 25_000L
