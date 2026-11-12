package com.sadurni.attacca.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.MediaItems
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The queue as saved to disk so playback resumes where it was left. */
data class SavedQueue(
    val listName: String,
    val source: ListSource?,
    /** File paths or URLs. */
    val locations: List<String>,
    /** Per-entry #EXTINF title and trim points, null when absent. */
    val titles: List<String?>,
    val startsMs: List<Long?>,
    val endsMs: List<Long?>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    /** The output it was playing on when saved (null in queues saved before this was kept). */
    val device: String? = null,
) {
    fun toMediaItems(): List<MediaItem> =
        locations.mapIndexed { i, location ->
            MediaItems.fromLocation(location, titles.getOrNull(i), startsMs.getOrNull(i), endsMs.getOrNull(i))
        }
}

class QueueStore(context: Context) {

    private val file = File(context.filesDir, "queue.json")

    fun save(player: Player, listName: String, source: ListSource?, device: String? = null) {
        // A finished list resumes from the top rather than at the very end of its last track.
        val ended = player.playbackState == Player.STATE_ENDED
        val locations = JSONArray()
        val titles = JSONArray()
        val starts = JSONArray()
        val ends = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            locations.put(item.mediaId)
            titles.put(item.mediaMetadata.extras?.getString(MediaItems.EXTRA_PLAYLIST_TITLE) ?: JSONObject.NULL)
            starts.put(MediaItems.explicitClipStartMs(item) ?: JSONObject.NULL)
            ends.put(MediaItems.explicitClipEndMs(item) ?: JSONObject.NULL)
        }
        val json = JSONObject()
            .put("listName", listName)
            .put("source", source?.toJson() ?: JSONObject.NULL)
            .put("locations", locations)
            .put("titles", titles)
            .put("startsMs", starts)
            .put("endsMs", ends)
            .put("index", if (ended) 0 else player.currentMediaItemIndex)
            .put("positionMs", if (ended) 0L else player.currentPosition)
            .put("shuffle", player.shuffleModeEnabled)
            .put("repeatMode", player.repeatMode)
            .put("device", device ?: JSONObject.NULL)
        // Write-then-rename so a crash mid-write never loses the queue.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    fun load(): SavedQueue? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val locations = json.getJSONArray("locations")
        val count = locations.length()
        val titles = json.optJSONArray("titles")
        val starts = json.optJSONArray("startsMs")
        val ends = json.optJSONArray("endsMs")
        SavedQueue(
            listName = json.optString("listName"),
            source = ListSource.fromJson(json.optJSONObject("source")),
            locations = List(count) { locations.getString(it) },
            titles = List(count) { i -> titles?.takeIf { !it.isNull(i) }?.optString(i) },
            startsMs = List(count) { i -> starts?.takeIf { !it.isNull(i) }?.optLong(i) },
            endsMs = List(count) { i -> ends?.takeIf { !it.isNull(i) }?.optLong(i) },
            index = json.optInt("index"),
            positionMs = json.optLong("positionMs"),
            shuffle = json.optBoolean("shuffle"),
            repeatMode = json.optInt("repeatMode", Player.REPEAT_MODE_OFF),
            device = json.optString("device").takeIf { it.isNotEmpty() && !json.isNull("device") },
        )
    }.getOrNull()

    /** The saved queue belonged to a list that was deleted: it stays, as an unsaved list. */
    fun forgetSource(old: ListSource) {
        runCatching {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            if (ListSource.fromJson(json.optJSONObject("source"))?.key != old.key) return
            json.remove("source")
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }
    }

    /** The saved queue belongs to a list that was renamed (while the player was closed). */
    fun renameSource(old: ListSource, renamed: ListSource) {
        runCatching {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            if (ListSource.fromJson(json.optJSONObject("source"))?.key != old.key) return
            json.put("source", renamed.toJson()).put("listName", renamed.name)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }
    }
}
