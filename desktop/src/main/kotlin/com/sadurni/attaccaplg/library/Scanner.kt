package com.sadurni.attaccaplg.library

import com.sadurni.attaccaplg.analysis.TrackAnalyzer
import com.sadurni.attaccaplg.audio.Decoder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a scan found and did. */
class ScanResult(val total: Int, val analysed: Int, val failed: Int, val removed: Int, val unchanged: Int, val millis: Long)

/**
 * Walks the music folder and brings the library cache up to date: new and changed files are read
 * (tags) and measured (analysis) in parallel; files gone are removed. Incremental: unchanged files
 * (same size and date, same analysis version) are left alone.
 */
class Scanner(private val db: LibraryDb, private val jobs: Int = defaultJobs()) {

    /** Audio files under [root], as paths relative to it ('/' separators). */
    fun audioFiles(root: File): Map<String, File> {
        val result = sortedMapOf<String, File>()
        root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() in AUDIO }
            .forEach { result[relative(root, it)] = it }
        return result
    }

    /**
     * Brings the cache up to date with [root]; [progress] is told after each file (done, to do,
     * path). When [cancelled] turns true, the files not started yet are left for the next scan.
     */
    fun scan(
        root: File,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, todo: Int, path: String) -> Unit = { _, _, _ -> },
    ): ScanResult {
        val started = System.currentTimeMillis()
        db.setMeta("music_root", root.absolutePath)
        val files = audioFiles(root)
        val stamps = db.stamps()
        val gone = stamps.keys - files.keys
        db.remove(gone)
        db.commit()
        val edgeSeconds = db.edgeSeconds
        // Tracks imported without their music (no date) are this folder's when path and size match:
        // they keep what was measured.
        for ((path, file) in files) {
            val stamp = stamps[path] ?: continue
            if (stamp.second == 0L && stamp.first == file.length() && stamp.third == VERSION) db.setMtime(path, file.lastModified())
        }
        db.commit()
        val adopted = db.stamps()
        val todo = files.filter { (path, file) ->
            val stamp = adopted[path]
            stamp == null || stamp.first != file.length() || stamp.second != file.lastModified() || stamp.third != VERSION
        }
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(jobs)
        for ((path, file) in todo) {
            pool.execute {
                if (cancelled()) return@execute
                val track = measure(path, file, edgeSeconds)
                if (track.error != null) failed.incrementAndGet()
                db.put(track, VERSION)
                val n = done.incrementAndGet()
                if (n % COMMIT_EVERY == 0) db.commit()
                progress(n, todo.size, path)
            }
        }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
        db.commit()
        return ScanResult(files.size, done.get() - failed.get(), failed.get(), gone.size, files.size - todo.size, System.currentTimeMillis() - started)
    }

    /** Tags and analysis of one file; on failure, the tags and why. */
    /**
     * Measures only the silences of the tracks without them (brought from a phone that had not
     * measured them), in parallel; their edges, features and the rest stay. Tracks whose file is
     * missing or has another size are left for a scan. Returns how many were measured.
     */
    fun measureSilences(
        root: File,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, todo: Int, path: String) -> Unit = { _, _, _ -> },
    ): Int {
        val todo = db.all().filter { it.error == null && it.audioEndMs <= 0 }
            .mapNotNull { t -> File(root, t.path).takeIf { it.isFile && it.length() == t.size }?.let { t.path to it } }
        val done = AtomicInteger()
        val measured = AtomicInteger()
        val pool = Executors.newFixedThreadPool(jobs)
        for ((path, file) in todo) {
            pool.execute {
                if (cancelled()) return@execute
                com.sadurni.attaccaplg.analysis.SilenceMeter.measure(file)?.let {
                    db.setSilence(path, it.durationMs, it.audioStartMs, it.audioEndMs)
                    measured.incrementAndGet()
                }
                val n = done.incrementAndGet()
                if (n % COMMIT_EVERY == 0) db.commit()
                progress(n, todo.size, path)
            }
        }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
        db.commit()
        return measured.get()
    }

    fun measure(path: String, file: File, edgeSeconds: Int = db.edgeSeconds): StoredTrack {
        val tags = TagReader.read(file)
        val analysed = runCatching { TrackAnalyzer.analyze(file, edgeSeconds) }
        val track = analysed.getOrNull()
        val error = when {
            analysed.isFailure -> analysed.exceptionOrNull().let { it?.message ?: it?.javaClass?.simpleName }
            track == null && !Decoder.canDecode(file) -> "no decoder for .${file.extension} (install ffmpeg)"
            track == null -> "could not be decoded"
            else -> null
        }
        val analysis = track?.analysis
        return StoredTrack(
            path = path,
            size = file.length(),
            mtime = file.lastModified(),
            tags = tags,
            durationMs = analysis?.durationMs ?: 0,
            audioStartMs = analysis?.audioStartMs ?: 0,
            audioEndMs = analysis?.audioEndMs ?: 0,
            lufs = analysis?.loudnessLufs,
            peak = analysis?.samplePeak ?: 0f,
            head = track?.head,
            tail = track?.tail,
            features = track?.features,
            error = error,
            waveform = analysis?.waveform,
            sound = track?.sound,
        )
    }

    companion object {
        /** Bumped when the analysis changes: every file is measured again (3: the sound profile). */
        const val VERSION = 3

        private const val COMMIT_EVERY = 50

        /** As Attacca's AudioFiles. */
        val AUDIO = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "aif", "aiff", "ape", "wv", "mpc", "alac", "mka")

        fun relative(root: File, file: File): String = file.relativeTo(root).invariantSeparatorsPath

        /** Decoding is CPU work: one job per core, leaving one free. */
        fun defaultJobs(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
    }
}
