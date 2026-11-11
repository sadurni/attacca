package com.sadurni.attacca

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threads for heavy work that can wait: analysing files, decoding crossfade tails, reading tags.
 *
 * They run at background priority so the audio thread always comes first. With the screen off
 * the system gives the app little CPU, and work at normal priority competing with the audio
 * processing was enough to make the sound stutter now and then.
 */
object Background {

    private val count = AtomicInteger()

    val dispatcher: CoroutineDispatcher = pool(THREADS, "attacca-background")

    /** How many files can be analysed at the same time: one per core. */
    val maxAnalysisThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Threads for analysing many files at once (one per core at most), separate from [dispatcher]
     * so a long analysis never delays a crossfade tail or a tag read.
     */
    val analysis: CoroutineDispatcher = pool(maxAnalysisThreads, "attacca-analysis")

    /**
     * One thread at normal priority for short work the user is waiting for right now (the few
     * seconds decoded for a skip blend): the background threads may all be busy with tags or
     * tails, and their low priority leaves them little CPU while the music plays.
     */
    val urgent: CoroutineDispatcher = pool(1, "attacca-urgent", Process.THREAD_PRIORITY_DEFAULT)

    /**
     * One thread at normal priority for the crossfade tails: a tail missing at its transition
     * loses the end of the track and leaves the next one's first silence bare, so it must never
     * wait behind scans or analysis on the low-priority threads.
     */
    val tails: CoroutineDispatcher = pool(1, "attacca-tails", Process.THREAD_PRIORITY_DEFAULT)

    /**
     * One thread for what the playing and next tracks need before they sound (silences, waveform,
     * gain, the crossfade plan): kept apart from the shared threads, where a library scan or a
     * list being made could keep it waiting until the transition has passed.
     */
    val prePlay: CoroutineDispatcher = pool(1, "attacca-preplay")

    /**
     * One thread walking folders for the browser (the search index, the inclusion marks of
     * folders): a walk of a big music folder takes a while and must not keep the shared threads,
     * which read tags and covers, waiting while the user browses.
     */
    val folders: CoroutineDispatcher = pool(1, "attacca-folders")

    /** One thread decoding the playing track a few seconds ahead, for skip blends. */
    val ahead: CoroutineDispatcher = pool(1, "attacca-ahead")

    private fun pool(threads: Int, name: String, priority: Int = Process.THREAD_PRIORITY_BACKGROUND): CoroutineDispatcher = Executors.newFixedThreadPool(threads) { task ->
        Thread({
            Process.setThreadPriority(priority)
            task.run()
        }, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private const val THREADS = 2
}
