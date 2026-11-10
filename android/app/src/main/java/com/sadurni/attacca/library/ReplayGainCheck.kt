package com.sadurni.attacca.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Which tracks carry ReplayGain tags, kept in the tag database (with each file's size and date,
 * so a changed file is read again). The library scan's own reader does not see those tags: the
 * files are read once more for them, after each scan only the new and changed ones.
 */
object ReplayGainCheck {

    class Progress(val done: Int, val total: Int)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Bumped when a pass ends, for what shows the counts. */
    private val _passes = MutableStateFlow(0)
    val passes: StateFlow<Int> = _passes.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + com.sadurni.attacca.Background.dispatcher)
    private var job: Job? = null

    /** Whether [file] carries ReplayGain tags (track or album gain, in its format's own names for them). */
    fun has(file: File): Boolean = runCatching {
        val tags = TrackInfo.read(file, withCovers = false).tags
            .associate { (k, v) -> k.substringAfterLast(':').uppercase() to v }
        !com.sadurni.attacca.dsp.ReplayGainInfo.fromTags(tags).isEmpty
    }.getOrDefault(false)

    /** The tracks under [folder] never read for it (or changed since): read in the background, one pass at a time. */
    fun checkFolder(folder: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            val db = TrackSort.db ?: return@launch
            val known = runCatching { db.replayGainKnown() }.getOrDefault(emptyMap())
            val files = db.tracks(folder).map { File(it.path) }.filter { f ->
                val k = known[f.absolutePath]
                k == null || !FileStamp.unchanged(k[0], k[1], f)
            }
            if (files.isEmpty()) return@launch
            _progress.value = Progress(0, files.size)
            val batch = ArrayList<Triple<File, Boolean, LongArray>>()
            for ((i, f) in files.withIndex()) {
                if (job?.isActive == false) break
                batch += Triple(f, has(f), longArrayOf(f.length(), f.lastModified()))
                if (batch.size >= 100 || i == files.size - 1) {
                    runCatching { db.putReplayGain(batch) }
                    batch.clear()
                }
                _progress.value = Progress(i + 1, files.size)
            }
            _progress.value = null
            _passes.value++
        }
    }
}
