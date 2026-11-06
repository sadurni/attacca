package com.sadurni.attacca.dsp

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * How each change of track went, one line each, in `Music/Attacca/attacca-transitions.txt`: the
 * track that ended, how it blended into the next (shape and length), whether its end was ready
 * and how well it lined up with what had played, and dropouts. For finding a fault the user heard
 * at a given time. Written on its own low-priority thread: the audio thread only hands the line over.
 */
object TransitionLog {

    /** Where the lines go; nothing is written until it is set. */
    @Volatile
    var file: File? = null

    private const val MAX_BYTES = 256 * 1024

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "transition-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun note(text: String) {
        val target = file ?: return
        val at = System.currentTimeMillis()
        writer.execute {
            runCatching {
                target.parentFile?.mkdirs()
                // Only the latest: the oldest half goes once it grows too big.
                if (target.length() > MAX_BYTES) target.writeText(target.readText().takeLast(MAX_BYTES / 2).substringAfter('\n'))
                target.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))}  $text\n")
            }
        }
    }

    /** The name of a file without its folders, for the lines. */
    fun nameOf(path: String?): String = path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "?"

    /** A blend's shape in words. */
    fun shapeName(shape: CrossfadeShape): String = when (shape) {
        CrossfadeShape.CROSSFADE -> "crossfade"
        CrossfadeShape.MIX -> "mix"
        CrossfadeShape.OVERLAY -> "overlay"
        CrossfadeShape.FADE_OUT_ONLY -> "fade out only"
        CrossfadeShape.SKIP -> "skip"
        else -> "custom ${shape.outFrom}/${shape.outTo}/${shape.inTo}"
    }
}
