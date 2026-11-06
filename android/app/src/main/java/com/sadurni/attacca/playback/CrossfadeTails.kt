package com.sadurni.attacca.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.sadurni.attacca.analysis.TailDecoder
import com.sadurni.attacca.dsp.CrossfadeTail
import com.sadurni.attacca.dsp.DspEngine
import com.sadurni.attacca.library.MediaItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the ends of the current and next crossfaded tracks decoded ahead of time and published
 * in [DspEngine.tails], where the crossfade stage picks them up at the transition.
 */
@OptIn(UnstableApi::class)
class CrossfadeTails(
    private val player: ExoPlayer,
    private val engine: DspEngine,
    private val scope: CoroutineScope,
) {
    private data class Key(val path: String, val clipEndMs: Long, val crossfadeMs: Int, val shape: com.sadurni.attacca.dsp.CrossfadeShape)

    // Main thread only.
    private val decoded = HashMap<Key, CrossfadeTail>()
    private val decoding = HashSet<Key>()
    private val failed = HashSet<Key>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                refresh()
            }
        }
    }

    fun start() {
        player.addListener(listener)
        refresh()
    }

    fun stop() {
        player.removeListener(listener)
        engine.tails.clear()
        engine.blendingPeriods.clear()
        decoded.clear()
    }

    private fun refresh() {
        val timeline = player.currentTimeline
        val wanted = HashMap<Any, Key>()
        if (!timeline.isEmpty) {
            val current = player.currentMediaItemIndex
            val next = timeline.getNextWindowIndex(current, player.repeatMode, player.shuffleModeEnabled)
            for (index in listOf(current, next)) {
                if (index == C.INDEX_UNSET) continue
                val key = keyOf(player.getMediaItemAt(index)) ?: continue
                wanted[periodUid(timeline, index)] = key
            }
        }

        engine.tails.keys.retainAll(wanted.keys)
        engine.blendingPeriods.keys.retainAll(wanted.keys)
        for ((uid, key) in wanted) engine.blendingPeriods[uid] = key.path
        decoded.keys.retainAll(wanted.values.toSet())
        for ((uid, key) in wanted) {
            val tail = decoded[key]
            if (tail != null) {
                engine.tails[uid] = tail
            } else if (key !in failed && decoding.add(key)) {
                scope.launch {
                    val started = System.nanoTime()
                    val result = withContext(com.sadurni.attacca.Background.tails) {
                        TailDecoder.decode(File(key.path), key.clipEndMs * 1000, key.crossfadeMs * 1000L)
                    }
                    decoding.remove(key)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    if (result == null) Log.w(TAG, "could not decode the tail of ${key.path}")
                    else Log.d(TAG, "tail of ${File(key.path).name} ready in $ms ms: ${result.frames} frames, ${result.channels}ch ${result.sampleRate} Hz")
                    if (result != null) {
                        result.shape = key.shape
                        result.source = key.path
                        decoded[key] = result
                        refresh()
                    } else {
                        failed.add(key)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Crossfade"
    }

    private fun keyOf(item: MediaItem): Key? {
        val crossfadeMs = MediaItems.crossfadeMsOf(item)
        if (crossfadeMs <= 0 || MediaItems.hasExplicitClip(item)) return null
        val end = MediaItems.clipEndMs(item) ?: return null
        if (item.mediaId.contains("://")) return null
        return Key(item.mediaId, end, crossfadeMs, MediaItems.crossfadeShapeOf(item))
    }

    /** The playlist period uid, as the renderer sees it. */
    private fun periodUid(timeline: Timeline, windowIndex: Int): Any =
        timeline.getUidOfPeriod(timeline.getWindow(windowIndex, Timeline.Window()).firstPeriodIndex)
}
