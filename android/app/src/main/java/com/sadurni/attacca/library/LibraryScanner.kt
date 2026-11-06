package com.sadurni.attacca.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reads the tags of every track below the music folder into the tag cache, so sorting, grouping
 * and album shuffle of any list are instant. Only new or changed files are read again; files
 * already known cost a database lookup.
 */
class LibraryScanner(context: Context, private val scope: CoroutineScope) {

    /** A scan in progress: tracks with tags ready out of all found, and the time likely left. */
    data class Progress(val done: Int, val total: Int, val remainingMs: Long? = null)

    /** The last finished scan. */
    data class Result(val tracks: Int, val finishedAt: Long)

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _last = MutableStateFlow(
        prefs.getLong(KEY_FINISHED, 0L).takeIf { it > 0 }?.let { Result(prefs.getInt(KEY_TRACKS, 0), it) },
    )
    val last: StateFlow<Result?> = _last.asStateFlow()

    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = com.sadurni.attacca.Background.dispatcher.limitedParallelism(1)

    /** Scans [folder] unless a scan is already running. */
    fun scan(folder: File) {
        if (job?.isActive == true) return
        job = scope.launch(io) {
            _progress.value = Progress(0, 0)
            try {
                // The one walk of the music folder: also kept for the jobs that follow and for searching.
                val ids = AudioFiles.indexLibrary(folder).map { it.absolutePath }
                // Files deleted (or never copied to this device, after a restore) are missing: out of Music and of the lists made from the tags.
                if (folder.isDirectory) runCatching { TrackSort.db?.markMissing(folder.absolutePath, ids.toHashSet()) }
                _progress.value = Progress(0, ids.size)
                val timer = com.sadurni.attacca.chain.ChainAnalyzer.ProgressTimer()
                TrackSort.warm(ids) { done ->
                    _progress.value = Progress(done, ids.size, timer.of(done, ids.size).remainingMs)
                }
                val result = Result(ids.size, System.currentTimeMillis())
                prefs.edit().putInt(KEY_TRACKS, result.tracks).putLong(KEY_FINISHED, result.finishedAt).apply()
                _last.value = result
            } finally {
                _progress.value = null
            }
        }
    }

    private companion object {
        const val KEY_TRACKS = "tracks"
        const val KEY_FINISHED = "finished"
    }
}
