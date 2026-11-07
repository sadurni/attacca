package com.sadurni.attacca.library

import java.io.File

/** How the inclusion list is read: only its tracks may be used, or all but its tracks. */
enum class InclusionMode { ALLOW, BLOCK }

/**
 * One inclusion list kept by name, for an occasion ("With the children", "With my parents"): its
 * M3U8 [list] (null until something is added) read as [mode]. Several can be kept; one at a time
 * is in use, or none (everything).
 */
data class InclusionSet(val id: String, val name: String, val list: String?, val mode: InclusionMode) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("id", id).put("name", name)
        .put("list", list ?: org.json.JSONObject.NULL).put("mode", mode.name)

    companion object {
        fun fromJson(o: org.json.JSONObject) = InclusionSet(
            o.getString("id"),
            o.optString("name"),
            o.optString("list").takeIf { !o.isNull("list") && it.isNotEmpty() },
            InclusionMode.entries.firstOrNull { it.name == o.optString("mode") } ?: InclusionMode.ALLOW,
        )

        fun listToJson(sets: List<InclusionSet>): String = org.json.JSONArray(sets.map { it.toJson() }).toString()

        fun listFromJson(text: String?): List<InclusionSet> = runCatching {
            val a = org.json.JSONArray(text ?: "[]")
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        /** The most sets a player library can tell apart. */
        const val MAX_FOR_PLAYERS = 32
    }
}

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
