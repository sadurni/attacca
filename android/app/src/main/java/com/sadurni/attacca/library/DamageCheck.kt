package com.sadurni.attacca.library

import com.sadurni.attacca.playback.FrameRepeats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Checks music files for damage and mends what can be mended.
 *
 * MP3 files are walked frame by frame as the player reads them: frames broken by garbage and
 * stretches written twice are counted ([FrameRepeats]). Other files are read through their
 * container to the end: one that stops long before its own length is cut short, one that cannot
 * be read at all is unreadable. Only MP3 damage can be repaired: the file is written again
 * without the broken and repeated frames.
 */
object DamageCheck {

    class Progress(val done: Int, val total: Int, val found: Int, val remainingMs: Long? = null)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + com.sadurni.attacca.Background.dispatcher)
    private var job: Job? = null

    /**
     * Checks every track under [folder] in the background (one pass at a time), [threads] files
     * at once (the analysis threads set in Chaining).
     */
    fun checkFolder(folder: File, threads: Int = 1, all: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            val db = TrackSort.db
            // Only what was never checked, or changed since, unless everything is asked for: a check
            // stopped halfway goes on from where it was.
            if (all) runCatching { db?.clearDamageChecked() }
            val checked = runCatching { db?.damageChecked() }.getOrNull().orEmpty()
            val files = AudioFiles.collectAudio(folder, recent = true).filter { f ->
                val c = checked[f.absolutePath]
                c == null || c[0] != f.length() || c[1] != f.lastModified()
            }
            val timer = com.sadurni.attacca.chain.ChainAnalyzer.ProgressTimer()
            val done = java.util.concurrent.atomic.AtomicInteger()
            val found = java.util.concurrent.atomic.AtomicInteger()
            val next = java.util.concurrent.atomic.AtomicInteger()
            _progress.value = Progress(0, files.size, 0)
            kotlinx.coroutines.coroutineScope {
                repeat(threads.coerceIn(1, com.sadurni.attacca.Background.maxAnalysisThreads)) {
                    launch(com.sadurni.attacca.Background.analysis) {
                        while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive != false) {
                            val i = next.getAndIncrement()
                            if (i >= files.size) break
                            val file = files[i]
                            val result = runCatching { check(file) }.getOrNull()
                            if (result == null) {
                                DamagedTracks.forget(file.absolutePath)
                            } else {
                                found.incrementAndGet()
                                DamagedTracks.report(file.absolutePath, result.first, result.second)
                            }
                            runCatching { db?.putDamageChecked(file.absolutePath, file.length(), file.lastModified()) }
                            val n = done.incrementAndGet()
                            _progress.value = Progress(n, files.size, found.get(), synchronized(timer) { timer.of(n, files.size).remainingMs })
                        }
                    }
                }
            }
            _progress.value = null
        }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = null
    }

    /** What is wrong with [file] and how much, or null when it is fine. */
    fun check(file: File): Pair<DamagedTracks.Kind, Int>? {
        if (file.extension.equals("mp3", ignoreCase = true)) {
            checkMp3(file)?.let { return it.takeIf { r -> r.second > 0 || r.first == DamagedTracks.Kind.UNREADABLE } }
        }
        return checkContainer(file)
    }

    /** Damage heard in an MP3 ([com.sadurni.attacca.playback.Mp3Damage]); null when it is no stream the walk knows (checked otherwise then). */
    private fun checkMp3(file: File): Pair<DamagedTracks.Kind, Int>? {
        val damage = try {
            com.sadurni.attacca.playback.Mp3Damage.check(file.readBytes())
        } catch (e: com.sadurni.attacca.playback.Mp3Damage.NotMp3) {
            return null
        }
        return when (damage) {
            is com.sadurni.attacca.playback.Mp3Damage.Damage.Repeats -> DamagedTracks.Kind.REPEATS to damage.ms
            is com.sadurni.attacca.playback.Mp3Damage.Damage.Broken -> DamagedTracks.Kind.BROKEN_FRAMES to damage.frames
            null -> DamagedTracks.Kind.BROKEN_FRAMES to 0
        }
    }

    /**
     * Checks again the tracks marked by earlier, stricter rules (once per rules version), so marks
     * that are no damage go away by themselves.
     */
    fun recheckMarked() {
        val db = TrackSort.db ?: return
        if (runCatching { db.fixDone(RULES) }.getOrDefault(true)) return
        val marked = DamagedTracks.all.value.keys.map(::File)
        if (job?.isActive == true) return
        job = scope.launch {
            var found = 0
            _progress.value = Progress(0, marked.size, 0)
            for ((i, file) in marked.withIndex()) {
                if (!isActive) return@launch
                val result = runCatching { check(file) }.getOrNull()
                if (result == null) DamagedTracks.forget(file.absolutePath) else DamagedTracks.report(file.absolutePath, result.first, result.second).also { found++ }
                _progress.value = Progress(i + 1, marked.size, found)
            }
            _progress.value = null
            runCatching { db.markFixDone(RULES) }
        }
    }

    /** The rules' version: a new one checks the marked tracks again. */
    private const val RULES = "damage_rules_2"


    /** A file read through its container to the end: cut short, unreadable, or fine (null). */
    private fun checkContainer(file: File): Pair<DamagedTracks.Kind, Int>? {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DamagedTracks.Kind.UNREADABLE to 0
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) format.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(track)
            val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
            var lastUs = 0L
            var samples = 0
            while (true) {
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                lastUs = maxOf(lastUs, extractor.sampleTime)
                samples++
                if (!extractor.advance()) break
            }
            if (samples == 0) return DamagedTracks.Kind.UNREADABLE to 0
            // Cut short: the sound ends well before the length the file says it has (Ogg files
            // can state a length their pages do not hold: only MP4, FLAC and WAV are trusted here).
            val trusted = file.extension.lowercase() in setOf("m4a", "mp4", "aac", "flac", "wav")
            if (trusted && durationUs > 10_000_000L && lastUs < durationUs * 0.9 - 5_000_000L) {
                return DamagedTracks.Kind.TRUNCATED to ((durationUs - lastUs) / 1000).toInt()
            }
            return null
        } catch (e: Exception) {
            return DamagedTracks.Kind.UNREADABLE to 0
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Writes [file] again without its broken and repeated frames; [keepOriginal] keeps the
     * original beside it with ".bk" added to its name (not taken for music), else it is deleted.
     * True when done.
     */
    fun repairMp3(file: File, keepOriginal: Boolean): Boolean =
        (com.sadurni.attacca.playback.Mp3Damage.repairFile(file, keepOriginal) != null).also { if (it) DamagedTracks.forget(file.absolutePath) }

}
