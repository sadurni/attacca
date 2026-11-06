package com.sadurni.attacca.library

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object AudioFiles {

    /** Formats Media3 decodes on every supported Android version. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "mp4", "aac",
        "wav", "wave", "aif", "aiff", "mka", "webm", "3gp", "amr",
    )
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls", "cue", "xspf")
    private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart")
    private val COVER_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    fun isAudio(file: File): Boolean = file.extension.lowercase() in AUDIO_EXTENSIONS

    fun isPlaylist(file: File): Boolean = file.extension.lowercase() in PLAYLIST_EXTENSIONS

    /** Finds a cover image (cover.jpg, folder.png…) next to the audio files. */
    /** Cover image of each folder seen ("" for none): one directory listing per album. */
    private val folderCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Looks the cover of [folder] up again next time (a new one was written). */
    fun forgetFolderCover(folder: File) {
        folderCovers.remove(folder.path)
    }

    /**
     * [findFolderCover], remembered per folder for the session. A folder that cannot be read (its
     * memory card not mounted yet after a restart) is not remembered as having none.
     */
    fun cachedFolderCover(folder: File): File? {
        folderCovers[folder.path]?.let { return it.takeIf(String::isNotEmpty)?.let(::File) }
        if (!folder.isDirectory) return null
        return folderCovers.getOrPut(folder.path) { findFolderCover(folder)?.path.orEmpty() }
            .takeIf { it.isNotEmpty() }?.let(::File)
    }

    /**
     * Whether [path] (a file, not a URL) lies on storage that is not there: its folder is missing,
     * as with a memory card not mounted yet after the phone restarts. A file gone from a folder
     * that is there is just missing.
     */
    fun onAbsentStorage(path: String): Boolean {
        if (path.contains("://")) return false
        val parent = File(path).parentFile ?: return false
        return !parent.isDirectory
    }

    /**
     * Waits (up to [timeoutMs]) until none of [paths] lies on absent storage; returns how long it
     * waited, in ms (0: all were there).
     */
    suspend fun awaitStorage(paths: List<String>, timeoutMs: Long = STORAGE_WAIT_MS): Long {
        val start = System.currentTimeMillis()
        while (paths.any(::onAbsentStorage)) {
            val waited = System.currentTimeMillis() - start
            if (waited >= timeoutMs) return waited
            kotlinx.coroutines.delay(STORAGE_POLL_MS)
        }
        return System.currentTimeMillis() - start
    }

    private const val STORAGE_WAIT_MS = 180_000L
    private const val STORAGE_POLL_MS = 1_000L

    fun findFolderCover(folder: File): File? {
        val files = folder.listFiles() ?: return null
        val byName = files.filter { it.isFile }.associateBy { it.name.lowercase() }
        for (name in COVER_NAMES) {
            for (ext in COVER_EXTENSIONS) {
                byName["$name.$ext"]?.let { return it }
            }
        }
        return null
    }

    /** Roots of internal storage and SD cards / USB drives. */
    fun storageRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        @Suppress("DEPRECATION")
        roots.add(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.storageVolumes.mapNotNullTo(roots) { it.directory }
        } else {
            // App-specific dirs are <root>/Android/data/<pkg>/files on every volume.
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                val path = dir.absolutePath
                val index = path.indexOf("/Android/")
                if (index > 0) roots.add(File(path.substring(0, index)))
            }
        }
        return roots.filter { it.canRead() }
    }

    /** Lists a folder: subfolders first, then playlists and audio files, in natural order. */
    fun listFolder(folder: File): List<File> {
        val children = folder.listFiles() ?: return emptyList()
        val visible = children.filter { !it.name.startsWith(".") }
        val folders = visible.filter { it.isDirectory }.sortedWith(NaturalOrder)
        val files = visible.filter { it.isFile && (isAudio(it) || isPlaylist(it)) }.sortedWith(NaturalOrder)
        return folders + files
    }

    /**
     * Folders, playlists and audio files below [roots] whose name contains every word of
     * [query], up to [limit] results. Blocking; call off the main thread.
     */
    fun search(roots: List<File>, query: String, limit: Int, isCancelled: () -> Boolean = { false }): List<File> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val result = ArrayList<File>()
        // Below the indexed folder (the music folder), from memory.
        val indexed = searchIndex
        if (indexed != null && roots.all { indexed.covers(it) }) {
            val prefixes = roots.map { it.absolutePath.trimEnd('/') + "/" }
            for (entry in indexed.entries) {
                if (result.size >= limit || isCancelled()) break
                if (prefixes.none { entry.file.path.startsWith(it) } || !words.all { it in entry.name }) continue
                result.add(entry.file)
            }
            return result
        }
        for (root in roots) {
            walkEntries(root, isCancelled) { entry ->
                if (words.all { it in entry.name }) result.add(entry.file)
                result.size < limit
            }
            if (result.size >= limit) break
        }
        return result
    }

    /** A folder, playlist or audio file found below a folder, with its name in lower case. */
    private class Entry(val file: File, val name: String)

    private class SearchIndex(val root: String, val entries: List<Entry>, val builtAt: Long) {
        fun covers(folder: File): Boolean {
            val path = folder.absolutePath.trimEnd('/')
            return path == root || path.startsWith("$root/")
        }
    }

    @Volatile
    private var searchIndex: SearchIndex? = null

    /**
     * Keeps every folder, playlist and audio file below [folder] (the music folder) in memory, so
     * searches there need no file system access. Rebuilt when older than a few minutes or for
     * another folder; blocking, call off the main thread (the browser does when it opens).
     */
    fun warmSearchIndex(folder: File) {
        val root = folder.absolutePath.trimEnd('/')
        val current = searchIndex
        if (current != null && current.root == root && System.currentTimeMillis() - current.builtAt < INDEX_MAX_AGE_MS) return
        indexLibrary(folder)
    }

    /** The audio files of the last [indexLibrary], for the background jobs that follow a scan. */
    @Volatile
    private var listing: SearchIndex? = null

    /**
     * Walks the music folder once for everything: returns its audio files (in browsing order, as
     * [collectAudio]), keeps them for the background jobs that follow (see [collectAudio] with
     * `recent`) and keeps the search index. Blocking.
     */
    fun indexLibrary(folder: File): List<File> {
        val root = folder.absolutePath.trimEnd('/')
        val entries = ArrayList<Entry>()
        walkEntries(folder, isCancelled = { false }) { entries.add(it); true }
        val index = SearchIndex(root, entries, System.currentTimeMillis())
        searchIndex = index
        listing = index
        // Everything is scanned, the ignored files too (they are only left out where lists are made).
        return entries.filter { isAudio(it.file) }.map { it.file }
    }

    /**
     * Visits the folders, playlists and audio files below [root] in browsing order (each folder's
     * entries, then its subfolders). Names with an audio or playlist extension are taken as files
     * without asking the file system, which is what made searching slow: only the other entries
     * are checked for being folders. [visit] returns false to stop.
     */
    private fun walkEntries(root: File, isCancelled: () -> Boolean, visit: (Entry) -> Boolean) {
        var going = true
        fun walk(dir: File) {
            if (!going || isCancelled()) return
            val names = dir.list() ?: return
            val folders = ArrayList<File>()
            val files = ArrayList<File>()
            for (name in names) {
                if (name.startsWith(".")) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                val child = File(dir, name)
                when {
                    ext in AUDIO_EXTENSIONS || ext in PLAYLIST_EXTENSIONS -> files.add(child)
                    child.isDirectory -> folders.add(child)
                }
            }
            folders.sortWith(NaturalOrder)
            files.sortWith(NaturalOrder)
            for (child in folders + files) {
                if (!visit(Entry(child, child.name.lowercase()))) {
                    going = false
                    return
                }
            }
            for (sub in folders) walk(sub)
        }
        walk(root)
    }

    private const val INDEX_MAX_AGE_MS = 5 * 60_000L

    /**
     * All audio files below [folder], folder by folder in natural order (album order). With
     * [recent], a background job right after a scan takes them from that scan's walk when it
     * covers [folder] (a few minutes old at most) instead of walking the storage again.
     */
    /**
     * The audio files below [folder]. Ignored ones (see [IgnoredPaths]) are left out unless
     * [withIgnored]: the analysis still measures them, so a list can be made from one of them.
     */
    fun collectAudio(folder: File, recent: Boolean = false, withIgnored: Boolean = false): List<File> {
        val known = listing
        if (recent && known != null && known.covers(folder) && System.currentTimeMillis() - known.builtAt < INDEX_MAX_AGE_MS) {
            val prefix = folder.absolutePath.trimEnd('/') + "/"
            return known.entries.asSequence()
                .map { it.file }
                .filter { it.path.startsWith(prefix) && isAudio(it) && (withIgnored || !IgnoredPaths.isIgnored(it.path)) }
                .toList()
        }
        return walkAudio(folder, withIgnored)
    }

    /** The next [collectAudio] walks the folder again (what is ignored changed). */
    fun forgetListing() {
        listing = null
    }

    private fun walkAudio(folder: File, withIgnored: Boolean): List<File> {
        val result = ArrayList<File>()
        walkEntries(folder, isCancelled = { false }) { entry ->
            if (isAudio(entry.file) && (withIgnored || !IgnoredPaths.isIgnored(entry.file.path))) result.add(entry.file)
            true
        }
        return result
    }
}

/** "Track 2" sorts before "Track 10", ignoring case. */
object NaturalOrder : Comparator<File> {
    override fun compare(a: File, b: File): Int = compareNatural(a.name, b.name)

    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
