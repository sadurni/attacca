package com.sadurni.attacca.chain

import android.content.Context
import com.sadurni.attacca.Background
import com.sadurni.attacca.library.AudioFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Describes the edges of tracks for chaining, on demand for a list or for the whole music folder.
 * Everything runs on the low-priority analysis threads, several files at a time (see
 * [com.sadurni.attacca.settings.ChainSettings.analysisThreads]), so playback keeps priority;
 * results are kept in [ChainDb], so each file is decoded once. While the music folder is analysed
 * the CPU is kept awake, or with the screen off the pass would mostly sleep.
 */
class ChainAnalyzer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Length of the edges described, from the settings. */
    private val edgeSeconds: () -> Int,
    /** How many files are analysed at the same time, from the settings. */
    private val threads: () -> Int,
    /** Where silences and loudness are kept (the one playback uses). */
    private val analysisDb: () -> com.sadurni.attacca.analysis.AnalysisDb,
    /** The silence threshold of the settings, which measured silences belong to. */
    private val silenceThreshold: () -> Float,
    /** Whether the pass over the music folder also measures missing silences, from the settings. */
    private val measureSilences: () -> Boolean,
    /** Whether music is playing now: then the analysis threads give way to it. */
    private val playing: () -> Boolean = { false },
) {

    /** What a pass over the music folder does. */
    enum class Pass {
        /** Chaining analysis (with silences when set). */
        CHAIN,

        /** Silences only, from the ends of the files ([measureSilencesOf]). */
        SILENCES,

        /** The whole analysis played tracks get: silences, waveform, loudness ([measureWholeOf]). */
        WHOLE,
    }

    /** Work in progress: files ready out of all, and the time likely left (once known). */
    data class Progress(val done: Int, val total: Int, val remainingMs: Long? = null, val pass: Pass = Pass.CHAIN)

    /**
     * Builds [Progress] values with an estimate of the time left, from the pace of the files
     * analysed since it was created (files already in the cache are not counted).
     */
    class ProgressTimer {
        private var startDone = -1
        private var startedAt = 0L

        fun of(done: Int, total: Int): Progress {
            val now = android.os.SystemClock.elapsedRealtime()
            if (startDone < 0) {
                startDone = done
                startedAt = now
            }
            val analysed = done - startDone
            val remaining = if (analysed >= MIN_FOR_ESTIMATE) (now - startedAt) * (total - done) / analysed else null
            return Progress(done, total, remaining)
        }

        private companion object {
            const val MIN_FOR_ESTIMATE = 3
        }
    }

    /** The last finished pass over the music folder. */
    data class Result(val analysed: Int, val total: Int, val finishedAt: Long)

    val db = ChainDb(context)
    private val prefs = context.getSharedPreferences("chain", Context.MODE_PRIVATE)

    /**
     * A file is analysed by one request at a time (the library pass and a list being chained may
     * both want it); a few locks shared out by path, so different files rarely wait for each other.
     */
    private val fileLocks = Array(FILE_LOCKS) { Any() }

    private fun lockFor(file: File): Any = fileLocks[Math.floorMod(file.absolutePath.hashCode(), FILE_LOCKS)]

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "attacca:chain-analysis")
        .apply { setReferenceCounted(false) }

    private val _libraryProgress = MutableStateFlow<Progress?>(null)

    /** Progress of the pass over the music folder while it runs. */
    val libraryProgress: StateFlow<Progress?> = _libraryProgress.asStateFlow()

    private val _last = MutableStateFlow(
        prefs.getLong(KEY_FINISHED, 0L).takeIf { it > 0 }
            ?.let { Result(prefs.getInt(KEY_ANALYSED, 0), prefs.getInt(KEY_TOTAL, 0), it) },
    )
    val last: StateFlow<Result?> = _last.asStateFlow()

    private var libraryJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Background.dispatcher.limitedParallelism(1)

    /** Analyses every track below [folder] not analysed yet (new or changed), unless a pass is running. */
    fun analyzeLibrary(folder: File) {
        if (libraryJob?.isActive == true) return
        libraryJob = scope.launch(io) { runPass(folder) }
    }

    /** Forgets all results and analyses every track below [folder] again. */
    fun reanalyzeLibrary(folder: File) {
        val previous = libraryJob
        previous?.cancel()
        libraryJob = scope.launch(io) {
            previous?.join()
            runCatching { db.clear() }
            forgetLastPassResult()
            runPass(folder)
        }
    }

    /**
     * Measures the silences of every track below [folder] still without them, from the ends of
     * the files only (fast), unless a pass is running. They serve skipping silence and the library
     * exchange.
     */
    fun measureSilencesOf(folder: File) = folderPass(folder, Pass.SILENCES)

    /**
     * Gives every track below [folder] not decoded whole yet the analysis played tracks get
     * (silences, waveform, loudness for the measured gain): the whole file is decoded, slow.
     */
    fun measureWholeOf(folder: File) = folderPass(folder, Pass.WHOLE)

    private fun folderPass(folder: File, pass: Pass) {
        if (libraryJob?.isActive == true) return
        libraryJob = scope.launch(io) {
            _libraryProgress.value = Progress(0, 0, pass = pass)
            wakeLock.acquire(WAKE_MS)
            try {
                val files = localFiles(AudioFiles.collectAudio(folder, recent = true, withIgnored = true).map { it.absolutePath })
                val threshold = silenceThreshold()
                val known = runCatching {
                    if (pass == Pass.WHOLE) analysisDb().getAll(files, threshold).keys else analysisDb().getBounds(files, threshold).keys
                }.getOrDefault(emptySet())
                val missing = files.filter { it.absolutePath !in known }
                val timer = ProgressTimer()
                inParallel(missing, files.size - missing.size, { done ->
                    wakeLock.acquire(WAKE_MS)
                    _libraryProgress.value = timer.of(done, files.size).copy(pass = pass)
                }) { file, cancelled ->
                    if (pass == Pass.WHOLE) wholeOf(file, threshold, cancelled) else silenceOf(file, threshold, cancelled)
                }
            } finally {
                _libraryProgress.value = null
                wakeLock.release()
            }
        }
    }

    private suspend fun runPass(folder: File) {
        _libraryProgress.value = Progress(0, 0)
        wakeLock.acquire(WAKE_MS)
        try {
            val ids = AudioFiles.collectAudio(folder, recent = true, withIgnored = true).map { it.absolutePath }
            _libraryProgress.value = Progress(0, ids.size)
            val timer = ProgressTimer()
            val profiles = profilesFor(ids, withSilences = measureSilences()) { done ->
                // Renewed with every file: held while the pass advances, never left on for long.
                wakeLock.acquire(WAKE_MS)
                _libraryProgress.value = timer.of(done, ids.size)
            }
            val analysed = profiles.edges.count { (id, e) -> e?.head != null && profiles.features[id] != null }
            val result = Result(analysed, ids.size, System.currentTimeMillis())
            prefs.edit()
                .putInt(KEY_ANALYSED, result.analysed)
                .putInt(KEY_TOTAL, result.total)
                .putLong(KEY_FINISHED, result.finishedAt)
                .apply()
            _last.value = result
        } finally {
            _libraryProgress.value = null
            wakeLock.release()
        }
    }

    fun cancelLibrary() {
        libraryJob?.cancel()
    }

    /** The edge length changed: the last pass no longer says how much is analysed. */
    fun forgetLastPass() {
        cancelLibrary()
        forgetLastPassResult()
    }

    private fun forgetLastPassResult() {
        prefs.edit().clear().apply()
        _last.value = null
    }

    /**
     * Edges of [mediaIds]: known ones from the cache in a few queries, the rest decoded now.
     * Streams and missing files map to nothing. [onProgress] gets how many are ready. Cancelling
     * the calling coroutine stops the files being decoded. Call from a background dispatcher.
     */
    suspend fun edgesFor(mediaIds: List<String>, onProgress: (Int) -> Unit = {}): Map<String, TrackEdges?> {
        val files = localFiles(mediaIds)
        val seconds = edgeSeconds()
        val known = runCatching { db.getAll(files, seconds) }.getOrDefault(emptyMap())
        val result = ConcurrentHashMap<String, Edges>()
        known.forEach { (path, edges) -> result[path] = Edges(edges) }
        val missing = files.filter { it.absolutePath !in known }
        inParallel(missing, mediaIds.size - missing.size, onProgress) { file, cancelled ->
            OpenFile(file).use { edgesOf(file, seconds, cancelled, it) }?.let { result[file.absolutePath] = it }
        }
        return result.mapValues { it.value.edges }
    }

    /** [file] opened for decoding when first needed, then shared by the analyses that need it. */
    private class OpenFile(private val file: File) : java.io.Closeable {
        private var source: EdgeDecoder.Source? = null
        private var opened = false

        fun get(): EdgeDecoder.Source? {
            if (!opened) {
                opened = true
                source = EdgeDecoder.Source.open(file)
            }
            return source
        }

        override fun close() {
            source?.close()
        }
    }

    /** Results that may be "could not be analysed" (a concurrent map cannot hold nulls). */
    private class Edges(val edges: TrackEdges?)
    private class Features(val features: TrackFeatures?)
    private class Sound(val sound: SoundProfile?)

    /** The sound network, read once from the app's files when first needed; shared by the analysis threads. */
    private val soundNet: SoundNet by lazy { context.assets.open(SOUND_NET_ASSET).use(::SoundNet) }

    /**
     * Edges of [file], from the cache when another request analysed it meanwhile. Null only when
     * cancelled: then nothing is stored, or the file would count as undecodable.
     */
    private fun edgesOf(file: File, seconds: Int, cancelled: () -> Boolean, source: OpenFile): Edges? = synchronized(lockFor(file)) {
        val known = runCatching { db.getAll(listOf(file), seconds) }.getOrDefault(emptyMap())
        if (file.absolutePath in known) return Edges(known[file.absolutePath])
        val edges = source.get()?.let { EdgeDecoder.decode(it, seconds, cancelled) }
        if (cancelled()) return null
        runCatching { db.put(file, edges, seconds) }
        Edges(edges)
    }

    /**
     * Silences of [file] from its ends ([com.sadurni.attacca.analysis.SilenceScanner]), kept,
     * unless known (from either analysis, also when it could not be decoded). Nothing is stored
     * when cancelled.
     */
    private fun silenceOf(file: File, threshold: Float, cancelled: () -> Boolean): Unit = synchronized(lockFor(file)) {
        val db = analysisDb()
        val known = runCatching { db.getBounds(listOf(file), threshold) }.getOrNull() ?: return
        if (file.absolutePath in known) return
        val bounds = com.sadurni.attacca.analysis.SilenceScanner.scan(file, threshold, cancelled)
        if (cancelled()) return
        runCatching { db.putBounds(file, bounds, threshold) }
    }

    /** The whole analysis of [file] (as before playing), kept, unless known. Nothing is stored when cancelled. */
    private fun wholeOf(file: File, threshold: Float, cancelled: () -> Boolean): Unit = synchronized(lockFor(file)) {
        val db = analysisDb()
        val known = runCatching { db.get(file, threshold) }.getOrNull() ?: return
        if (known !is com.sadurni.attacca.analysis.AnalysisDb.Lookup.Miss) return
        val analysis = com.sadurni.attacca.analysis.AudioAnalyzer.analyze(file, threshold, cancelled)
        if (cancelled()) return
        runCatching { db.put(file, analysis, threshold) }
    }

    /** Like [edgesOf], for the whole-track features. */
    private fun featuresOf(file: File, cancelled: () -> Boolean, source: OpenFile): Features? = synchronized(lockFor(file)) {
        val known = runCatching { db.getFeatures(listOf(file)) }.getOrDefault(emptyMap())
        if (file.absolutePath in known) return Features(known[file.absolutePath])
        val features = source.get()?.let { EdgeDecoder.decodeFeatures(it, cancelled) }
        if (cancelled()) return null
        runCatching { db.putFeatures(file, features) }
        Features(features)
    }

    /** Like [edgesOf], for the sound profile. */
    private fun soundOf(file: File, cancelled: () -> Boolean, source: OpenFile): Sound? = synchronized(lockFor(file)) {
        val known = runCatching { db.getSounds(listOf(file)) }.getOrDefault(emptyMap())
        if (file.absolutePath in known) return Sound(known[file.absolutePath])
        val sound = runCatching { source.get()?.let { EdgeDecoder.decodeSound(it, soundNet, cancelled) } }.getOrNull()
        if (cancelled()) return null
        runCatching { db.putSound(file, sound) }
        Sound(sound)
    }

    /**
     * Runs [work] on each of [files] on several analysis threads, each one taking the next file
     * when it finishes one. [onProgress] gets [alreadyDone] plus the files finished, one call at a
     * time and in order. Cancelling the calling coroutine stops the files being decoded.
     */
    private suspend fun inParallel(
        files: List<File>,
        alreadyDone: Int,
        onProgress: (Int) -> Unit,
        work: (File, cancelled: () -> Boolean) -> Unit,
    ) {
        onProgress(alreadyDone)
        if (files.isEmpty()) return
        val next = AtomicInteger()
        var done = alreadyDone
        val progressLock = Any()
        val workers = threads().coerceIn(1, Background.maxAnalysisThreads).coerceAtMost(files.size)
        coroutineScope {
            repeat(workers) {
                launch(Background.analysis) {
                    val job = coroutineContext.job
                    val cancelled = { job.isCancelled }
                    while (true) {
                        ensureActive()
                        val index = next.getAndIncrement()
                        if (index >= files.size) break
                        // Background priority puts a thread in the system's background group,
                        // which many phones keep on their slowest cores and starve with the screen
                        // off: only while music plays, so the audio always comes first.
                        android.os.Process.setThreadPriority(
                            if (playing()) android.os.Process.THREAD_PRIORITY_BACKGROUND else IDLE_PRIORITY,
                        )
                        work(files[index], cancelled)
                        ensureActive()
                        synchronized(progressLock) { onProgress(++done) }
                    }
                }
            }
        }
    }

    /** Edges, whole-track features and sound profiles by path; a null value means the file could not be analysed. */
    class Profiles(
        val edges: Map<String, TrackEdges?>,
        val features: Map<String, TrackFeatures?>,
        val sounds: Map<String, SoundProfile?> = emptyMap(),
    )

    /** Known edges and features of [mediaIds], from the cache only (nothing is decoded). */
    fun knownProfiles(mediaIds: List<String>, withSounds: Boolean = true): Profiles {
        val files = localFiles(mediaIds)
        val seconds = edgeSeconds()
        return Profiles(
            runCatching { db.getAll(files, seconds) }.getOrDefault(emptyMap()),
            runCatching { db.getFeatures(files) }.getOrDefault(emptyMap()),
            if (withSounds) runCatching { db.getSounds(files) }.getOrDefault(emptyMap()) else emptyMap(),
        )
    }

    /** Sound profiles of [mediaIds] already known. */
    fun knownSounds(mediaIds: List<String>): Map<String, SoundProfile?> = runCatching { db.getSounds(localFiles(mediaIds)) }.getOrDefault(emptyMap())

    /** See [ChainDb.soundStamps]. */
    fun soundStamps(mediaIds: List<String>): Map<String, Long> = runCatching { db.soundStamps(localFiles(mediaIds)) }.getOrDefault(emptyMap())

    /**
     * Edges and whole-track features of [mediaIds], analysing what is missing (both from one
     * request per file); [withSilences] also measures silences (from the ends of the file) where
     * they are missing. Like [edgesFor] otherwise.
     */
    suspend fun profilesFor(mediaIds: List<String>, withSilences: Boolean = false, onProgress: (Int) -> Unit = {}): Profiles {
        val files = localFiles(mediaIds)
        val seconds = edgeSeconds()
        val threshold = silenceThreshold()
        val knownEdges = runCatching { db.getAll(files, seconds) }.getOrDefault(emptyMap())
        val knownFeatures = runCatching { db.getFeatures(files) }.getOrDefault(emptyMap())
        val knownSounds = runCatching { db.getSounds(files) }.getOrDefault(emptyMap())
        val knownSilences = if (withSilences) runCatching { analysisDb().getBounds(files, threshold).keys }.getOrDefault(emptySet()) else null
        val edges = ConcurrentHashMap<String, Edges>()
        val features = ConcurrentHashMap<String, Features>()
        knownEdges.forEach { (path, e) -> edges[path] = Edges(e) }
        knownFeatures.forEach { (path, f) -> features[path] = Features(f) }
        val sounds = ConcurrentHashMap<String, Sound>()
        knownSounds.forEach { (path, s) -> sounds[path] = Sound(s) }
        val missing = files.filter {
            it.absolutePath !in knownEdges || it.absolutePath !in knownFeatures || it.absolutePath !in knownSounds ||
                (knownSilences != null && it.absolutePath !in knownSilences)
        }
        inParallel(missing, mediaIds.size - missing.size, onProgress) { file, cancelled ->
            val path = file.absolutePath
            // One opening of the file for all of them.
            OpenFile(file).use { source ->
                if (!edges.containsKey(path)) edgesOf(file, seconds, cancelled, source)?.let { edges[path] = it }
                if (!features.containsKey(path)) featuresOf(file, cancelled, source)?.let { features[path] = it }
                if (!sounds.containsKey(path)) soundOf(file, cancelled, source)?.let { sounds[path] = it }
            }
            if (knownSilences != null && path !in knownSilences) silenceOf(file, threshold, cancelled)
        }
        return Profiles(edges.mapValues { it.value.edges }, features.mapValues { it.value.features }, sounds.mapValues { it.value.sound })
    }

    private fun localFiles(mediaIds: List<String>): List<File> =
        mediaIds.asSequence().filter { !it.contains("://") }.distinct().map(::File).filter { it.isFile }.toList()

    private companion object {
        const val KEY_ANALYSED = "analysed"
        const val KEY_TOTAL = "total"
        const val KEY_FINISHED = "finished"
        const val FILE_LOCKS = 64
        const val SOUND_NET_ASSET = "sound/soundnet.bin"

        /** Longest the CPU stays awake without a file finishing (a stuck decode, a lost release). */
        const val WAKE_MS = 10 * 60 * 1000L

        /** Just short of background priority (below normal, outside the background group): nothing is playing. */
        const val IDLE_PRIORITY = android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE
    }
}
