package com.sadurni.attacca.library

/**
 * What the list tools do to a list's entries, by a key telling when two entries are the same
 * track (a file, or a part of one). Order is kept: the list's own entries stay where they were and
 * entries taken from another list go after them, in that list's order.
 */
object ListTools {

    enum class Tool { DEDUPE, REMOVE_PLAYED, ADD_OTHER, KEEP_SHARED, REMOVE_OTHER }

    /** Whether [tool] needs a second list. */
    fun needsOther(tool: Tool): Boolean = tool == Tool.ADD_OTHER || tool == Tool.KEEP_SHARED || tool == Tool.REMOVE_OTHER

    /** Each track once, where it first appears. */
    fun <T> dedupe(list: List<T>, key: (T) -> String): List<T> {
        val seen = HashSet<String>()
        return list.filter { seen.add(key(it)) }
    }

    /** The list without the tracks in [played] (by [path], the file of each entry). */
    fun <T> removePlayed(list: List<T>, played: Set<String>, path: (T) -> String): List<T> = list.filter { path(it) !in played }

    /** The list followed by the tracks of [other] it does not hold yet. */
    fun <T> add(list: List<T>, other: List<T>, key: (T) -> String): List<T> {
        val known = list.mapTo(HashSet(), key)
        return list + other.filter { known.add(key(it)) }
    }

    /** The tracks of the list that [other] holds too. */
    fun <T> keepShared(list: List<T>, other: List<T>, key: (T) -> String): List<T> {
        val keys = other.mapTo(HashSet(), key)
        return list.filter { key(it) in keys }
    }

    /** The tracks of the list that [other] does not hold. */
    fun <T> removeOther(list: List<T>, other: List<T>, key: (T) -> String): List<T> {
        val keys = other.mapTo(HashSet(), key)
        return list.filter { key(it) !in keys }
    }
}
