package com.sadurni.attacca

import android.content.res.Resources

/**
 * "Analysing: 12 / 40 · about 3 min left" ([label] takes done and total), with the percentage
 * after the counts when [percent] is set.
 */
fun progressText(res: Resources, label: Int, done: Int, total: Int, remainingMs: Long?, percent: Boolean = false): String {
    var text = res.getString(label, done, total)
    if (percent && total > 0) text += " · " + (done * 100L / total) + " %"
    if (remainingMs == null || done >= total) return text
    return text + " · " + res.getString(R.string.time_left, durationText(res, remainingMs))
}

/** "2 h 05 min", "3 min" or "40 s", rounded up. */
fun durationText(res: Resources, ms: Long): String {
    val seconds = (ms + 999) / 1000
    return when {
        seconds >= 3600 -> res.getString(R.string.time_hours_minutes, seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> res.getString(R.string.time_minutes, (seconds + 30) / 60)
        else -> res.getString(R.string.time_seconds, seconds)
    }
}
