package com.sadurni.attacca.library

/**
 * The folders holding audio below a root, in the order Folders shows them (natural order, a
 * folder's subfolders right after it): what "go on with the next folder" follows.
 */
object FolderOrder {

    /** Folders of [tracks] (absolute paths) under [root], in browsing order. */
    fun folders(root: String, tracks: List<String>): List<String> {
        val prefix = root.trimEnd('/') + "/"
        return tracks.asSequence()
            .mapNotNull { path -> path.substringBeforeLast('/', "").ifEmpty { null } }
            .filter { it.startsWith(prefix) || it == root.trimEnd('/') }
            .distinct()
            .sortedWith(::compare)
            .toList()
    }

    /** The folder after [current] in [ordered], or null at the end. */
    fun next(ordered: List<String>, current: String): String? {
        val path = current.trimEnd('/')
        return ordered.firstOrNull { compare(it, path) > 0 }
    }

    /** Compares paths segment by segment in natural order, so "Disc 2" comes before "Disc 10". */
    fun compare(a: String, b: String): Int {
        val sa = a.trimEnd('/').split('/')
        val sb = b.trimEnd('/').split('/')
        for (i in 0 until minOf(sa.size, sb.size)) {
            val c = NaturalOrder.compareNatural(sa[i], sb[i])
            if (c != 0) return c
        }
        return sa.size - sb.size
    }
}
