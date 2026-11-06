package com.sadurni.attacca.library

import java.io.File

/**
 * Favorites with only the included tracks (Settings → Inclusion list), as a list of its own kept
 * up to date from Favorites, which it never changes. Tracks no longer in Favorites, or no longer
 * included, leave it; new favorites that are included join at its end; the rest keeps the order
 * it was given (chained, sorted…).
 */
object IncludedFavorites {

    const val NAME = "Favorites (only included)"

    val file: File get() = File(UserLists.directory, "$NAME.m3u8")

    /**
     * Brings the list up to date with Favorites and the inclusion list [listPath] read as [mode].
     * Blocking (reads and may write the files), but quick when nothing changed since the last time:
     * the sizes and dates of Favorites, the inclusion list and this list are kept in [stamp].
     * Returns false without an inclusion list: there is no such list to offer then.
     */
    fun sync(listPath: String?, mode: InclusionMode, stamp: File): Boolean {
        val list = listPath?.let(::File)?.takeIf { it.isFile } ?: return false
        val before = stampOf(list, mode)
        if (file.isFile && runCatching { stamp.readText() }.getOrNull() == before) return true
        val inclusion = Inclusion(list, mode)
        val favorites = UserLists.favorites.takeIf { it.isFile }?.let(M3uParser::parse).orEmpty()
            .filter { inclusion.includes(it.location) }
            .distinctBy { it.location }
        val wanted = favorites.mapTo(HashSet()) { it.location }
        val old = file.takeIf { it.isFile }?.let(M3uParser::parse).orEmpty()
        val kept = old.filter { it.location in wanted }.distinctBy { it.location }
        val have = kept.mapTo(HashSet()) { it.location }
        val updated = kept + favorites.filter { it.location !in have }
        if (!file.isFile || updated.map { it.location } != old.map { it.location }) M3uParser.write(file, updated)
        runCatching { stamp.writeText(stampOf(list, mode)) }
        return true
    }

    private fun stampOf(list: File, mode: InclusionMode): String =
        listOf(UserLists.favorites, list, file).joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" } + "|$mode"
}
