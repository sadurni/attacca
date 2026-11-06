package com.sadurni.attacca.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Totals of a list: tracks, size on disk and, once the tags are known, playing time. */
data class ListSummary(val count: Int, val bytes: Long, val durationMs: Long?)

object ListStats {

    /**
     * The summary of [mediaIds], first with what is quick (count, size), then with the total
     * time, which may need reading tags of files never seen before. Blocking file access; collect
     * off the main thread.
     */
    fun summary(mediaIds: List<String>): Flow<ListSummary> = flow {
        val bytes = mediaIds.sumOf { if (it.contains("://")) 0L else File(it).length() }
        emit(ListSummary(mediaIds.size, bytes, null))
        TrackSort.warm(mediaIds)
        TrackSort.fillMissingLengths(mediaIds)
        emit(ListSummary(mediaIds.size, bytes, mediaIds.sumOf { TrackSort.tags(it).durationMs }))
    }
}
