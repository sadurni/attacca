package com.sadurni.attacca.playback

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Skipping by group (album, or the list's grouping) in play order, shuffle-aware: from the app's
 * buttons and from the car's. [groupOf] gives the group of an entry by its index.
 */
object GroupSkip {

    /** First entry of the next group; null at the last group. */
    fun next(player: Player, groupOf: (Int) -> String): Int? {
        val timeline = player.currentTimeline
        val current = player.currentMediaItemIndex
        val group = groupOf(current)
        var index = current
        while (true) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return null
            if (groupOf(index) != group) return index
        }
    }

    /**
     * Start of the current group, or of the previous one when already at its start (within
     * [restartMs] of the first entry's beginning).
     */
    fun previous(player: Player, groupOf: (Int) -> String, restartMs: Long): Int {
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        fun previous(i: Int) = timeline.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        fun groupStart(from: Int): Int {
            var start = from
            while (true) {
                val p = previous(start)
                if (p == C.INDEX_UNSET || groupOf(p) != groupOf(start)) return start
                start = p
            }
        }
        val current = player.currentMediaItemIndex
        val start = groupStart(current)
        return if (start != current || player.currentPosition > restartMs) {
            start
        } else {
            previous(start).takeIf { it != C.INDEX_UNSET }?.let(::groupStart) ?: start
        }
    }
}
