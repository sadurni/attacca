package com.sadurni.attacca.library

import java.net.URLDecoder

/**
 * Finds which track of the library a path from elsewhere means (shared with Attacca Desktop): a playlist written on another
 * computer or the phone, an old beets path, a relative path. The longest matching run of trailing
 * folder and file names wins (case-insensitive); at least the file name must match.
 */
class PathMatcher(libraryPaths: Collection<String>) {

    private val byName: Map<String, List<List<String>>> =
        libraryPaths.map(::components).groupBy { it.last().lowercase() }
    private val original: Map<List<String>, String> = libraryPaths.associateBy(::components)

    /** The library path [path] means, or null. */
    fun match(path: String): String? {
        val parts = components(clean(path))
        if (parts.isEmpty()) return null
        val candidates = byName[parts.last().lowercase()] ?: return null
        var best: List<String>? = null
        var bestRun = 0
        var tie = false
        for (c in candidates) {
            var run = 0
            while (run < c.size && run < parts.size && c[c.size - 1 - run].equals(parts[parts.size - 1 - run], ignoreCase = true)) run++
            when {
                run > bestRun -> {
                    best = c
                    bestRun = run
                    tie = false
                }
                run == bestRun -> tie = true
            }
        }
        // Two tracks as alike (same file name, nothing else to tell them apart): no guess.
        return if (tie && candidates.size > 1 && bestRun < 2) null else best?.let(original::get)
    }

    companion object {
        fun components(path: String): List<String> = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

        /** A `file://` URI as a path; anything else as it is. */
        fun clean(path: String): String {
            val p = path.trim()
            if (!p.startsWith("file:", ignoreCase = true)) return p
            return runCatching { URLDecoder.decode(p.removePrefix("file://").removePrefix("file:").replace("+", "%2B"), "UTF-8") }.getOrDefault(p)
        }
    }
}
