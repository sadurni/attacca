package com.sadurni.attacca.library

import java.io.File

/** Lists made from the listening statistics. */
enum class ListeningList {
    /** Played most often. */
    MOST_PLAYED,

    /** Played last, newest first. */
    RECENTLY_PLAYED,

    /** Played before, but not for the longest time. */
    FORGOTTEN,

    /** In the music folder and never played, in random order. */
    NEVER_PLAYED,

    /** Skipped most often. */
    MOST_SKIPPED,
    ;

    /**
     * Up to [count] existing tracks of this list. [library] is the music folder's tracks (only
     * needed by [NEVER_PLAYED]). Blocking: checks the files exist.
     */
    fun tracks(stats: Map<String, TrackListening>, library: () -> List<String>, count: Int): List<String> {
        val played = stats.filterValues { it.plays > 0 }
        val ordered: Sequence<String> = when (this) {
            MOST_PLAYED -> played.entries.sortedWith(compareByDescending<Map.Entry<String, TrackListening>> { it.value.plays }.thenByDescending { it.value.lastPlayedMs }).asSequence().map { it.key }
            RECENTLY_PLAYED -> played.entries.sortedByDescending { it.value.lastPlayedMs }.asSequence().map { it.key }
            FORGOTTEN -> played.entries.sortedWith(compareBy<Map.Entry<String, TrackListening>> { it.value.lastPlayedMs }.thenByDescending { it.value.plays }).asSequence().map { it.key }
            NEVER_PLAYED -> library().filter { stats[it]?.plays ?: 0 == 0 }.shuffled().asSequence()
            MOST_SKIPPED -> stats.entries.filter { it.value.skips > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, TrackListening>> { it.value.skips - it.value.plays }.thenByDescending { it.value.skips })
                .asSequence().map { it.key }
        }
        return ordered.filter { File(it).isFile }.take(count).toList()
    }
}
