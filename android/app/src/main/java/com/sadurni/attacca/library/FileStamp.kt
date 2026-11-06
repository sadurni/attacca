package com.sadurni.attacca.library

import java.io.File
import kotlin.math.abs

/**
 * Whether a file is still the one measured: the same size and the same modification time. Memory
 * cards (FAT, exFAT) keep the time as local time, so a change of time zone or of summer/winter
 * time shifts every file by whole hours (or half hours) once the phone restarts: with the same
 * size, such a shift is not an edit, and the whole music folder is not read and analysed again.
 */
object FileStamp {

    private const val HALF_HOUR_MS = 30 * 60 * 1000L

    /** The furthest time zones apart. */
    private const val MAX_SHIFT_MS = 14 * 60 * 60 * 1000L

    /** FAT keeps times to two seconds. */
    private const val SLACK_MS = 2_000L

    fun unchanged(size: Long, mtime: Long, file: File): Boolean = size == file.length() && sameTime(mtime, file.lastModified())

    fun sameTime(stored: Long, now: Long): Boolean {
        if (stored == now) return true
        val diff = abs(now - stored)
        if (diff > MAX_SHIFT_MS + SLACK_MS) return false
        val off = diff % HALF_HOUR_MS
        return diff >= HALF_HOUR_MS - SLACK_MS && (off <= SLACK_MS || HALF_HOUR_MS - off <= SLACK_MS)
    }
}
