package com.sadurni.attacca.analysis

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import com.sadurni.attacca.dsp.CrossfadePlan
import com.sadurni.attacca.dsp.CrossfadePlanner
import com.sadurni.attacca.dsp.CrossfadeShape
import com.sadurni.attacca.dsp.CrossfadeStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sadurni.attacca.library.AlbumShuffle
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.settings.TrimSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the one-pass analysis for the tracks around the current one and turns the results into
 * leading/trailing silence trims and crossfade clips.
 *
 * Trims are applied as clipping on queue items before they play, which keeps transitions clean.
 * A crossfaded track is also clipped where its crossfade starts; the rest of it is decoded apart
 * (see [com.sadurni.attacca.dsp.CrossfadeTail]).
 * The playing item cannot be re-clipped without interrupting it, so when its analysis arrives
 * late the start is skipped with a seek and the end with a position watcher.
 */
private val NO_CROSSFADE = CrossfadePlan(0, CrossfadeShape.CROSSFADE)

@OptIn(UnstableApi::class)
class AnalysisManager(
    private val player: ExoPlayer,
    private val db: AnalysisDb,
    private val settings: StateFlow<TrimSettings>,
    private val crossfade: StateFlow<CrossfadeRule>,
    private val scope: CoroutineScope,
    /** Published for the UI (waveform). */
    private val published: MutableStateFlow<Map<String, TrackAnalysis>>,
    /** Receives the measured gains of tracks without ReplayGain tags. */
    private val engine: com.sadurni.attacca.dsp.DspEngine,
) {
    /** Loudness and peak of each album (folder) whose tracks are all measured. */
    private val albumLoudness = ConcurrentHashMap<String, Pair<Float, Float>>()
    private val cache = ConcurrentHashMap<String, TrackAnalysis>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Silence bounds alone (from the ends of the files, see [SilenceScanner]) of tracks not decoded
     * whole yet: enough to decide trims and crossfades long before the whole analysis is there.
     * As analyses without waveform or loudness; never published.
     */
    private val bounds = ConcurrentHashMap<String, TrackAnalysis>()

    /** What trims and crossfades are decided with: the whole analysis, else the bounds. */
    private fun known(id: String): TrackAnalysis? = cache[id] ?: bounds[id]

    private fun boundsAnalysis(b: SilenceScanner.Bounds, thresholdDb: Float) =
        TrackAnalysis(b.durationMs, b.audioStartMs, b.audioEndMs, FloatArray(0), thresholdDb)

    /** Loads the stored bounds of [paths] that are not known yet. Blocking. */
    private fun loadBounds(paths: List<String>, thresholdDb: Float) {
        val unknown = paths.filter { !cache.containsKey(it) && !bounds.containsKey(it) }.map(::File)
        if (unknown.isEmpty()) return
        for ((path, b) in runCatching { db.getBounds(unknown, thresholdDb) }.getOrDefault(emptyMap())) {
            if (b != null) bounds[path] = boundsAnalysis(b, thresholdDb)
        }
    }

    @Volatile
    private var wanted: List<String> = emptyList()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private val handler = Handler(Looper.getMainLooper())
    private val applyRunnable = Runnable { applyTrims() }

    /** Shuffle state for threads other than the player's. */
    @Volatile
    private var shuffleOn = false

    /** The crossfade setting changed: the playing item is re-clipped once (a short gap). */
    private var reclipCurrent = false

    /** The playing track was reached by a jump or a new list, not by playing on (see applyTrims). */
    private var reachedByJump = true

    /** File position (ms) at which the current, unclipped item should move on. */
    private var endWatchMs: Long? = null

    /** Polls only while [endWatchMs] is set, which is rare: normal playback needs no polling. */
    private val endWatcher = object : Runnable {
        override fun run() {
            checkEndWatch()
            if (endWatchMs != null) handler.postDelayed(this, END_WATCH_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // On its own the player may be in the middle of a blend into this track: it must not
            // be re-clipped then (that seek would cut the blend).
            reachedByJump = reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            endWatchMs = null
            reschedule()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) reschedule()
        }

        // The next track in play order decides whether a transition crossfades.
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffleOn = shuffleModeEnabled
            // Crossfading only while shuffling: the coming transition changes too.
            if (crossfade.value.onlyShuffle) reclipCurrent = true
            reschedule()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = scheduleApply()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        shuffleOn = player.shuffleModeEnabled
        player.addListener(listener)
        scope.launch {
            var lastThreshold = settings.value.thresholdDb
            settings.collect { s ->
                if (s.thresholdDb != lastThreshold) {
                    lastThreshold = s.thresholdDb
                    cache.clear()
                    bounds.clear()
                    failed.clear()
                    publish()
                    reschedule()
                }
                scheduleApply()
            }
        }
        scope.launch {
            var lastRule = crossfade.value
            crossfade.collect { rule ->
                // A new setting should be heard at the coming transition too, not only after it.
                if (rule != lastRule) {
                    lastRule = rule
                    reclipCurrent = true
                }
                scheduleApply()
            }
        }
        scope.launch(com.sadurni.attacca.Background.prePlay) {
            for (signal in wakeUp) {
                val threshold = settings.value.thresholdDb
                loadBounds(wanted, threshold)
                // The playing and the next track decide the coming crossfade: their ends first.
                for (path in wanted.take(2)) {
                    if (!isActive || cache.containsKey(path) || bounds.containsKey(path) || path in failed) continue
                    val b = SilenceScanner.scan(File(path), threshold)
                    runCatching { db.putBounds(File(path), b, threshold) }
                    if (b != null) bounds[path] = boundsAnalysis(b, threshold)
                    // Decided as soon as each is known: the playing one may still be re-clipped.
                    withContext(Dispatchers.Main) { applyTrims() }
                }
                while (isActive) {
                    val path = wanted.firstOrNull { !cache.containsKey(it) && it !in failed } ?: break
                    analyzeOne(path)
                }
            }
        }
        reschedule()
    }

    fun stop() {
        player.removeListener(listener)
        handler.removeCallbacksAndMessages(null)
        wakeUp.close()
    }

    /**
     * Applies cached trims to items before they reach the player (session callbacks, restored
     * queue), so even the first track starts without its leading silence when it was analyzed
     * before. Blocking database reads; call off the main thread.
     */
    fun withCachedTrims(list: List<MediaItem>): List<MediaItem> {
        // Their tags too, for what the session shows (see MediaItems.withTags).
        val items = MediaItems.withTags(list)
        val s = settings.value
        // One batch of queries for the whole list instead of one per track.
        val unknown = items.mapNotNull(::localFile).filter { !cache.containsKey(it.absolutePath) && it.absolutePath !in failed }
        if (unknown.isNotEmpty()) {
            for ((path, hit) in db.getAll(unknown, s.thresholdDb)) {
                when (hit) {
                    is AnalysisDb.Lookup.Hit -> cache[path] = hit.analysis
                    AnalysisDb.Lookup.Failed -> failed.add(path)
                    AnalysisDb.Lookup.Miss -> Unit
                }
            }
            handler.post { publish() }
        }
        loadBounds(items.mapNotNull { localFile(it)?.absolutePath }, s.thresholdDb)
        val candidates = items.mapIndexed { i, item ->
            TrimCandidate(albumOf(item), localFile(item)?.absolutePath?.let(::known), followsPrevious(items, i))
        }
        return items.mapIndexed { i, item ->
            if (localFile(item) == null || MediaItems.hasExplicitClip(item)) return@mapIndexed item
            val silence = SilenceTrimmer.trimFor(i, candidates, s)
            // The play order is not known yet; assume the list order.
            val next = if (i + 1 < items.size) i + 1 else C.INDEX_UNSET
            val plan = crossfadePlan(item, candidates[i].analysis, silence, next.takeIf { it != C.INDEX_UNSET }?.let { items[it] to candidates[it].analysis })
            MediaItems.withAutoTrim(item, withCrossfade(silence, candidates[i].analysis, plan))
        }
    }

    private fun lookupCached(file: File, thresholdDb: Float): TrackAnalysis? {
        cache[file.absolutePath]?.let { return it }
        return when (val hit = db.get(file, thresholdDb)) {
            is AnalysisDb.Lookup.Hit -> hit.analysis.also {
                cache[file.absolutePath] = it
                handler.post { publish() }
            }
            AnalysisDb.Lookup.Failed -> null.also { failed.add(file.absolutePath) }
            AnalysisDb.Lookup.Miss -> null
        }
    }

    private suspend fun analyzeOne(path: String) {
        val file = File(path)
        val threshold = settings.value.thresholdDb
        val analysis = lookupCached(file, threshold) ?: run {
            if (path in failed) return
            val result = AudioAnalyzer.analyze(file, threshold)
            db.put(file, result, threshold)
            result
        }
        if (analysis == null) {
            failed.add(path)
        } else {
            cache[path] = analysis
            file.parentFile?.let { measureAlbum(it, threshold) }
        }
        withContext(Dispatchers.Main) {
            publish()
            scheduleApply()
        }
    }

    /** Current track first, then the next ones in play order (shuffle included), then the previous one. */
    private fun reschedule() {
        val count = player.mediaItemCount
        if (count == 0) {
            wanted = emptyList()
            return
        }
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val order = buildList {
            add(current)
            var index = timeline.getNextWindowIndex(current, Player.REPEAT_MODE_OFF, shuffle)
            while (index != C.INDEX_UNSET && size <= LOOKAHEAD) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            }
            val previous = timeline.getPreviousWindowIndex(current, Player.REPEAT_MODE_OFF, shuffle)
            if (previous != C.INDEX_UNSET) add(previous)
        }.distinct()
        wanted = order.mapNotNull { localFile(player.getMediaItemAt(it))?.absolutePath }
        wakeUp.trySend(Unit)
        scheduleApply()
    }

    private fun publish() {
        published.value = HashMap(cache)
        publishGains()
    }

    /**
     * The album's loudness once every track of its folder is measured (from the analysis
     * cache; nothing is decoded for it). Until then album gain falls back to track gain.
     */
    private fun measureAlbum(folder: File, thresholdDb: Float) {
        val tracks = AudioFiles.listFolder(folder).filter { it.isFile && AudioFiles.isAudio(it) }
        if (tracks.isEmpty()) return
        val known = db.getAll(tracks, thresholdDb)
        val measured = ArrayList<TrackAnalysis>(tracks.size)
        for (track in tracks) {
            val hit = known[track.absolutePath] as? AnalysisDb.Lookup.Hit ?: return
            measured.add(hit.analysis)
        }
        val lufs = LoudnessMeter.albumLufs(measured.mapNotNull { a -> a.loudnessLufs?.let { l -> l to a.durationMs } }) ?: return
        albumLoudness[folder.absolutePath] = lufs to measured.maxOf { it.samplePeak }
    }

    /**
     * Gains for the tracks around the current one that have no ReplayGain tags, from their
     * measured loudness: the renderer uses them at the track start. The playing track gets its
     * gain right away when it started before its analysis was ready.
     */
    private fun publishGains() {
        val count = player.mediaItemCount
        if (count == 0) return
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val window = androidx.media3.common.Timeline.Window()
        val periods = HashMap<Any, com.sadurni.attacca.dsp.ReplayGainInfo>()
        for (i in upcomingIndices() + player.currentMediaItemIndex) {
            if (i !in 0 until count || i >= timeline.windowCount) continue
            val item = player.getMediaItemAt(i)
            val analysis = cache[item.mediaId] ?: continue
            val lufs = analysis.loudnessLufs ?: continue
            val album = albumLoudness[albumOf(item)]
            val info = com.sadurni.attacca.dsp.ReplayGainInfo(
                trackGainDb = LoudnessMeter.REFERENCE_LUFS - lufs,
                trackPeak = analysis.samplePeak.takeIf { it > 0f },
                albumGainDb = album?.let { LoudnessMeter.REFERENCE_LUFS - it.first },
                albumPeak = album?.second?.takeIf { it > 0f },
            )
            periods[timeline.getUidOfPeriod(timeline.getWindow(i, window).firstPeriodIndex)] = info
        }
        engine.measuredGains.clear()
        engine.measuredGains.putAll(periods)
        val playingPeriod = engine.periodOfGeneration[engine.streamGeneration]
        if (!engine.currentGainFromTags && playingPeriod != null) {
            // The gain stage ramps to the new value within a buffer: no jump.
            periods[playingPeriod]?.let {
                if (engine.currentReplayGain != it) android.util.Log.d("ReplayGain", "measured while playing: $it")
                engine.currentReplayGain = it
            }
        }
    }

    private fun scheduleApply() {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, APPLY_DEBOUNCE_MS)
    }

    private fun applyTrims() {
        val count = player.mediaItemCount
        if (count == 0) return
        val s = settings.value
        val items = List(count) { player.getMediaItemAt(it) }
        val candidates = items.mapIndexed { i, item -> TrimCandidate(albumOf(item), known(item.mediaId), followsPrevious(items, i)) }
        val current = player.currentMediaItemIndex
        val upcoming = upcomingIndices()
        val next = player.currentTimeline.getNextWindowIndex(current, player.repeatMode, player.shuffleModeEnabled)
        // Near the end of the playing track the player is already reading the next one; changing
        // it then makes the player start over from the current position (a cut, and a jump in
        // files that do not seek exactly).
        val nextLocked = player.duration != C.TIME_UNSET && player.duration - player.currentPosition < NEXT_LOCK_MS
        // A track that just started without the crossfade it should have (a list just opened, a
        // jump to it) is re-clipped too: a moment's gap at its very start, not a hard cut at its end.
        // Not while a skip by hand is still blending into it: that seek would cut the blend. Once
        // it is over the track is re-clipped (the blend lasts a few seconds at most).
        if (reachedByJump && engine.skipBlending) {
            handler.removeCallbacks(applyRunnable)
            handler.postDelayed(applyRunnable, SKIP_BLEND_RETRY_MS)
        }
        val earlyWindow = EARLY_RECLIP_MS + if (reachedByJump) MAX_SKIP_BLEND_MS else 0L
        val reclip = reclipCurrent || (reachedByJump && !engine.skipBlending && player.currentPosition < earlyWindow)
        reclipCurrent = false
        for (i in 0 until count) {
            val item = items[i]
            if (localFile(item) == null || MediaItems.hasExplicitClip(item)) continue
            val silence = SilenceTrimmer.trimFor(i, candidates, s)
            if (i == current) {
                if (reclip && reclipCurrent(i, item, silence, items, next)) continue
                handleCurrent(item, silence)
                continue
            }
            if (i == next && nextLocked) continue
            // Crossfades are decided for the tracks coming up; the rest keep theirs until then,
            // so changing a setting does not re-insert a whole long list.
            val plan = if (i in upcoming) {
                val next = player.currentTimeline.getNextWindowIndex(i, player.repeatMode, player.shuffleModeEnabled)
                crossfadePlan(item, known(item.mediaId), silence, next.takeIf { it != C.INDEX_UNSET }?.let { items[it] to known(items[it].mediaId) })
            } else {
                CrossfadePlan(MediaItems.crossfadeMsOf(item), MediaItems.crossfadeShapeOf(item))
            }
            val trim = withCrossfade(silence, known(item.mediaId), plan)
            // Not analyzed yet: keep whatever it has rather than churn the queue.
            if (s.enabled && known(item.mediaId) == null) continue
            if (MediaItems.autoTrimOf(item) != trim) {
                replaceItem(i, MediaItems.withAutoTrim(item, trim))
            }
        }
    }

    /**
     * Gives the playing item the clip of the current crossfade length, continuing where it was.
     * False when it needs no change or it is too late for it.
     */
    private fun reclipCurrent(index: Int, item: MediaItem, silence: Trim, items: List<MediaItem>, next: Int): Boolean {
        if (known(item.mediaId) == null) return false
        val plan = crossfadePlan(item, known(item.mediaId), silence, next.takeIf { it != C.INDEX_UNSET }?.let { items[it] to known(items[it].mediaId) })
        val trim = withCrossfade(silence, known(item.mediaId), plan)
        val applied = MediaItems.autoTrimOf(item)
        if (applied == trim) return false
        // Where in the file it is, and there in the new clip (which may start elsewhere: a track
        // just started gets its leading silence trimmed too).
        val filePosition = (applied.startMs ?: 0L) + player.currentPosition
        val newStart = trim.startMs ?: 0L
        val newEnd = trim.endMs
        if (newEnd != null && filePosition > newEnd - RECLIP_MARGIN_MS) return false
        val position = (filePosition - newStart).coerceAtLeast(0L)
        // The new copy goes after it and takes over before the old one goes, so the player never
        // moves on to the next track meanwhile.
        if (player.shuffleModeEnabled) player.setShuffleOrder(ReplacingShuffleOrder(shuffledIndices(), index))
        player.addMediaItem(index + 1, MediaItems.withAutoTrim(item, trim))
        player.seekTo(index + 1, position)
        player.removeMediaItem(index)
        return true
    }

    /**
     * The crossfade into [next] (item and its analysis): its length (0 when it should not
     * crossfade) and how the levels move, as the settings say. [silence] is this track's trim,
     * whose end is where its sound ends.
     */
    private fun crossfadePlan(item: MediaItem, analysis: TrackAnalysis?, silence: Trim, next: Pair<MediaItem, TrackAnalysis?>?): CrossfadePlan {
        val rule = crossfade.value
        val lengthMs = crossfadeLength(item, analysis, next)
        if (lengthMs <= 0 || analysis == null || next == null) return NO_CROSSFADE
        val shape = when (rule.style) {
            CrossfadeStyle.CROSSFADE -> CrossfadeShape.CROSSFADE
            CrossfadeStyle.MIX -> CrossfadeShape.MIX
            CrossfadeStyle.CUSTOM -> rule.custom
            CrossfadeStyle.AUTOMATIC -> {
                val nextAnalysis = next.second
                return CrossfadePlanner.plan(
                    lengthMs,
                    analysis.waveform,
                    analysis.durationMs,
                    silence.endMs ?: analysis.audioEndMs,
                    nextAnalysis?.waveform ?: FloatArray(0),
                    nextAnalysis?.durationMs ?: 0L,
                    nextAnalysis?.audioStartMs ?: 0L,
                )
            }
        }
        return CrossfadePlan(lengthMs, shape)
    }

    /** Crossfade length into [next] (item and its analysis), or 0 when it should not crossfade. */
    private fun crossfadeLength(item: MediaItem, analysis: TrackAnalysis?, next: Pair<MediaItem, TrackAnalysis?>?): Int {
        val rule = crossfade.value
        if (rule.lengthMs <= 0 || analysis == null || next == null) return 0
        if (rule.onlyShuffle && !shuffleOn) return 0
        if (rule.noBlendAfter != null && item.mediaId == rule.noBlendAfter) return 0
        val (nextItem, nextAnalysis) = next
        // Both durations are needed: this one to place the clip, the next one so the blend ends
        // inside it.
        // The next one's length, from its analysis or else from its tags (known from the library scan).
        val nextDuration = nextAnalysis?.durationMs
            ?: com.sadurni.attacca.library.TrackSort.cachedTags(nextItem.mediaId)?.durationMs?.takeIf { it > 0 }
            ?: return 0
        if (nextDuration < rule.lengthMs + MIN_NEXT_EXTRA_MS) return 0
        if (localFile(nextItem) == null) return 0
        // Gapless only into the album's next track: tracks of one album brought together by a
        // list blend like any others.
        if (rule.skipSameAlbum && AlbumShuffle.followsOnAlbum(item.mediaId, nextItem.mediaId)) return 0
        return rule.lengthMs
    }

    /** Moves the clip end of [silence] back by the length of [plan] when the track is long enough. */
    private fun withCrossfade(silence: Trim, analysis: TrackAnalysis?, plan: CrossfadePlan): Trim {
        val crossfadeMs = plan.lengthMs
        if (crossfadeMs <= 0 || analysis == null) return silence
        val start = silence.startMs ?: 0L
        val end = silence.endMs ?: analysis.durationMs
        if (end - start < crossfadeMs + MIN_PLAYED_MS) return silence
        return Trim(silence.startMs, end - crossfadeMs, crossfadeMs, plan.shape)
    }

    /** The tracks after the current one in play order, as far as the analysis looks ahead. */
    private fun upcomingIndices(): Set<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptySet()
        val result = HashSet<Int>()
        var index = timeline.getNextWindowIndex(player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled)
        while (index != C.INDEX_UNSET && result.size < LOOKAHEAD && result.add(index)) {
            index = timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
        }
        return result
    }

    /**
     * Swaps a queue item for a re-clipped copy.
     *
     * `replaceMediaItem` is not enough: for an item without clipping ExoPlayer updates the source
     * in place and the new clip is ignored, so the item is re-inserted to force a new source.
     * Re-inserting would also move it within the shuffle order, so that order is restored.
     */
    private fun replaceItem(index: Int, item: MediaItem) {
        // An addition lands at a random place of a shuffle order, which for a moment could make
        // it the next track; an order that puts it right where the old one is avoids that.
        if (player.shuffleModeEnabled) player.setShuffleOrder(ReplacingShuffleOrder(shuffledIndices(), index))
        player.addMediaItem(index, item)
        player.removeMediaItem(index + 1)
    }

    private fun shuffledIndices(): IntArray {
        val timeline = player.currentTimeline
        val result = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            result.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return result.toIntArray()
    }

    /**
     * The playing item keeps its clip; emulate the trim with seeks instead. A crossfade cannot be
     * added to it any more, so its transition plays without one.
     */
    private fun handleCurrent(item: MediaItem, trim: Trim) {
        val applied = MediaItems.autoTrimOf(item)
        val clipStart = applied.startMs ?: 0L
        val filePosition = clipStart + player.currentPosition
        val start = trim.startMs
        if (start != null && applied.startMs == null && filePosition < start) {
            player.seekTo(start)
        }
        endWatchMs = if (applied.endMs == null) trim.endMs else null
        handler.removeCallbacks(endWatcher)
        if (endWatchMs != null) handler.post(endWatcher)
    }

    private fun checkEndWatch() {
        val end = endWatchMs ?: return
        if (!player.isPlaying || !player.hasNextMediaItem()) return
        val item = player.currentMediaItem ?: return
        val clipStart = MediaItems.autoTrimOf(item).startMs ?: 0L
        if (clipStart + player.currentPosition >= end) {
            endWatchMs = null
            player.seekToNextMediaItem()
        }
    }

    private fun localFile(item: MediaItem): File? =
        item.mediaId.takeIf { !it.contains("://") }?.let(::File)

    private fun albumOf(item: MediaItem): String = File(item.mediaId).parent.orEmpty()

    /** Whether [items] at [index] follows the one before it on their album (list order). */
    private fun followsPrevious(items: List<MediaItem>, index: Int): Boolean =
        index > 0 && albumOf(items[index - 1]) == albumOf(items[index]) &&
            AlbumShuffle.followsOnAlbum(items[index - 1].mediaId, items[index].mediaId)

    private companion object {
        const val LOOKAHEAD = 10
        const val APPLY_DEBOUNCE_MS = 300L
        const val END_WATCH_INTERVAL_MS = 100L
        const val MIN_PLAYED_MS = 5_000L
        const val MIN_NEXT_EXTRA_MS = 2_000L
        const val NEXT_LOCK_MS = 20_000L
        const val RECLIP_MARGIN_MS = 3_000L

        /** Played less than this, a track missing its crossfade is re-clipped (see applyTrims). */
        const val EARLY_RECLIP_MS = 4_000L
        const val SKIP_BLEND_RETRY_MS = 500L

        /** Longest blend after a skip by hand (the setting's maximum). */
        const val MAX_SKIP_BLEND_MS = 6_000L
    }
}
