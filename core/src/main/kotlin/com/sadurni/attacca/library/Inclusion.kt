package com.sadurni.attacca.library

import java.io.File

/** How the inclusion list is read: only its tracks may be used, or all but its tracks. */
enum class InclusionMode { ALLOW, BLOCK }

/**
 * Which tracks may go into generated lists (and stay in a list asked to keep only those): an
 * M3U8 list read as an allow list or a block list. The list is read when first needed.
 */
class Inclusion(private val mode: InclusionMode, private val read: () -> Collection<String>) {

    constructor(list: File, mode: InclusionMode) : this(mode, { PlaylistFiles.parse(list).map { it.location } })

    private val paths: Set<String> by lazy {
        runCatching { read().mapTo(HashSet(), ::normalize) }.getOrDefault(emptySet())
    }

    /** Whether [mediaId] may be used. Blocking the first time (reads the list). */
    fun includes(mediaId: String): Boolean = (normalize(mediaId) in paths) == (mode == InclusionMode.ALLOW)

    companion object {
        /** The configured inclusion, or null when no list is set or it is gone. */
        fun of(path: String?, mode: InclusionMode): Inclusion? =
            path?.let(::File)?.takeIf { it.isFile }?.let { Inclusion(it, mode) }

        /** The same file under its usual name (the storage is reachable by two paths). */
        fun normalize(path: String): String =
            if (path.startsWith(SDCARD)) STORAGE + path.substring(SDCARD.length) else path

        private const val SDCARD = "/sdcard/"
        private const val STORAGE = "/storage/emulated/0/"
    }
}
