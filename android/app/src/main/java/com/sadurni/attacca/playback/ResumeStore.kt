package com.sadurni.attacca.playback

import android.content.Context
import com.sadurni.attacca.library.ListSource
import org.json.JSONObject

/** Where each list was left, so any list can be continued later (e.g. when a device connects). */
data class ResumePoint(val mediaId: String, val index: Int, val positionMs: Long)

class ResumeStore(context: Context) {

    private val prefs = context.getSharedPreferences("resume_points", Context.MODE_PRIVATE)

    fun get(source: ListSource): ResumePoint? = runCatching {
        prefs.getString(source.key, null)?.let { raw ->
            val json = JSONObject(raw)
            ResumePoint(json.getString("mediaId"), json.optInt("index"), json.optLong("positionMs"))
        }
    }.getOrNull()

    fun put(source: ListSource, point: ResumePoint) {
        val json = JSONObject()
            .put("mediaId", point.mediaId)
            .put("index", point.index)
            .put("positionMs", point.positionMs)
        prefs.edit().putString(source.key, json.toString()).apply()
    }

    fun remove(source: ListSource) {
        prefs.edit().remove(source.key).apply()
    }

    /** Index to start at: the same file if it is still in the list, else the same position. */
    fun startIndex(point: ResumePoint?, mediaIds: List<String>): Int {
        if (point == null || mediaIds.isEmpty()) return 0
        val byId = mediaIds.indexOf(point.mediaId)
        return if (byId >= 0) byId else point.index.coerceIn(0, mediaIds.lastIndex)
    }
}
