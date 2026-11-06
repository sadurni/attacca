package com.sadurni.attacca.settings

import android.content.Context
import androidx.media3.common.Player
import com.sadurni.attacca.library.SortField
import org.json.JSONObject

enum class ShuffleMode { OFF, TRACKS, ALBUMS }

/**
 * Settings each list keeps for itself, so an albums list and a random-tracks list can behave
 * differently. Null values follow the global setting.
 */
data class ListSettings(
    val shuffle: ShuffleMode = ShuffleMode.OFF,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val replayGainMode: ReplayGainMode? = null,
    val trimEnabled: Boolean? = null,
    /** Crossfade length in ms for this list; 0 = off, null = global setting. */
    val crossfadeMs: Int? = null,
    /** Field the list was last sorted by, when it reads in groups (albums, artists…). */
    val groupBy: SortField? = null,
    /** Goes on with similar tracks when it ends; null = global setting. */
    val endless: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("shuffle", shuffle.name)
        .put("repeat", repeatMode)
        .put("rgMode", replayGainMode?.name ?: JSONObject.NULL)
        .put("trim", trimEnabled ?: JSONObject.NULL)
        .put("crossfade", crossfadeMs ?: JSONObject.NULL)
        .put("groupBy", groupBy?.name ?: JSONObject.NULL)
        .put("endless", endless ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): ListSettings = ListSettings(
            shuffle = ShuffleMode.entries.firstOrNull { it.name == json.optString("shuffle") } ?: ShuffleMode.OFF,
            repeatMode = json.optInt("repeat", Player.REPEAT_MODE_OFF),
            replayGainMode = if (json.isNull("rgMode")) null else ReplayGainMode.entries.firstOrNull { it.name == json.optString("rgMode") },
            trimEnabled = if (json.isNull("trim") || !json.has("trim")) null else json.optBoolean("trim"),
            crossfadeMs = if (json.isNull("crossfade") || !json.has("crossfade")) null else json.optInt("crossfade"),
            groupBy = SortField.entries.firstOrNull { it.name == json.optString("groupBy") },
            endless = if (json.isNull("endless") || !json.has("endless")) null else json.optBoolean("endless"),
        )
    }
}

class ListSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("list_settings", Context.MODE_PRIVATE)

    fun get(listKey: String): ListSettings = runCatching {
        prefs.getString(listKey, null)?.let { ListSettings.fromJson(JSONObject(it)) }
    }.getOrNull() ?: ListSettings()

    fun put(listKey: String, settings: ListSettings) {
        prefs.edit().putString(listKey, settings.toJson().toString()).apply()
    }

    fun remove(listKey: String) {
        prefs.edit().remove(listKey).apply()
    }

    companion object {
        /** Key for lists without a source (a single file opened from another app). */
        const val NO_SOURCE_KEY = "none"
    }
}

/** Fades and other playback behavior. */
data class PlaybackSettings(
    /** Volume fade when pausing, resuming and skipping; 0 disables it. */
    val fadeMs: Int = 250,
    /** Fade-out length when the sleep timer ends. */
    val sleepFadeMs: Int = 8_000,
    /** Crossfade between tracks; 0 disables it. */
    val crossfadeMs: Int = 0,
    /** Keep album transitions gapless: no crossfade between tracks of the same album. */
    val crossfadeSkipSameAlbum: Boolean = true,
    /** How the levels move in a crossfade. */
    val crossfadeStyle: com.sadurni.attacca.dsp.CrossfadeStyle = com.sadurni.attacca.dsp.CrossfadeStyle.CROSSFADE,
    /** The shape of [com.sadurni.attacca.dsp.CrossfadeStyle.CUSTOM]. */
    val crossfadeCustom: com.sadurni.attacca.dsp.CrossfadeShape = DEFAULT_CUSTOM_SHAPE,
    /** Crossfade only while shuffling (tracks or albums); in order, tracks follow on gapless. */
    val crossfadeOnlyShuffle: Boolean = false,
    /** A short crossfade when skipping by hand (next, previous, another track); 0 = a quick fade only. */
    val skipCrossfadeMs: Int = 0,
    /**
     * Headset/car play buttons start Attacca when it is closed, and the system keeps its player
     * in Quick Settings after closing. Off: closing removes everything.
     */
    val resumeWhenClosed: Boolean = false,
    /** Sleep timer option used last, offered again. */
    val lastSleep: SleepChoice = SleepChoice.Minutes(30),
    /**
     * Audio output buffer in ms; [AUTO_BUFFER] lets [AudioTuning] learn it. A longer buffer
     * survives longer stalls of the processing (screen off, busy phone) but sound settings take
     * that much longer to be heard.
     */
    val audioBufferMs: Int = AUTO_BUFFER,
    /**
     * The playback thread sleeps until the output buffer needs more audio instead of waking every
     * 10 ms: fewer wake-ups with the screen off, and fewer dropouts on phones that stalled with
     * hard-panned recordings. Applies the next time the player opens.
     */
    val efficientScheduling: Boolean = true,
    /**
     * Takes control of the standard effects (equalizer, bass boost, virtualizer, loudness,
     * dynamics) on Attacca's own audio and keeps them off, so equalizer apps that attach to it
     * cannot change the sound. Applies the next time the player opens.
     */
    val guardEffects: Boolean = true,
    /** After a pause at least this long (seconds), resuming first goes back [rewindSeconds]; 0 disables it. */
    val rewindAfterPauseS: Int = 15,
    val rewindSeconds: Int = 10,
    /** Previous/next group and favorite buttons in the car (and the phone's media controls). */
    val carButtons: Boolean = true,
    /**
     * Album shuffle takes each folder as an album (the discs of a double album or of a box apart)
     * instead of the album tag (one album, all discs together).
     */
    val albumsByFolder: Boolean = false,
    /** Appends every track played to a log file in the standard scrobbling format. */
    val listenLog: Boolean = false,
    /** When a folder played as a list ends: stop, or go on with the next or a random folder. */
    val folderChange: FolderChange = FolderChange.OFF,
    /** A notification or another app's short sound. */
    val focusDuck: FocusDuck = FocusDuck.LOWER,
    /** After a call or another short interruption, play on. */
    val focusResume: Boolean = true,
    /** "Go to the end" lands this many seconds before the end of the track. */
    val toEndSeconds: Int = 10,
    /** When a list is about to end, similar tracks chained from its last one are added (each list can say otherwise). */
    val endless: Boolean = false,
) {
    companion object {
        const val AUTO_BUFFER = 0
        val DEFAULT_CUSTOM_SHAPE = com.sadurni.attacca.dsp.CrossfadeShape(0.3f, 1f, 0.6f)
    }
}

/**
 * What automatic tuning learned on this phone: dropouts heard as output buffer underruns while
 * playing, and the buffer length grown after each one.
 */
data class AudioTuning(
    val learnedBufferMs: Int = START_BUFFER_MS,
    val dropouts: Int = 0,
) {
    fun bufferMs(playback: PlaybackSettings): Int =
        if (playback.audioBufferMs == PlaybackSettings.AUTO_BUFFER) learnedBufferMs else playback.audioBufferMs

    /** After a dropout: one second more, up to [MAX_BUFFER_MS]. */
    fun afterDropout(): AudioTuning =
        copy(learnedBufferMs = (learnedBufferMs + STEP_MS).coerceAtMost(MAX_BUFFER_MS), dropouts = dropouts + 1)

    companion object {
        const val START_BUFFER_MS = 2_000
        const val STEP_MS = 1_000
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 5_000
    }
}

/** What comes after a folder played as a list ends (in the music folder, in Folders order). */
enum class FolderChange { OFF, NEXT, RANDOM }

/** A short sound of another app while playing: lower the volume, pause, or play on as it is. */
enum class FocusDuck { LOWER, PAUSE, IGNORE }

/** A sleep timer option. */
sealed interface SleepChoice {
    data class Minutes(val minutes: Int) : SleepChoice
    data class At(val hour: Int, val minute: Int) : SleepChoice
    data object EndOfTrack : SleepChoice
    data object EndOfAlbum : SleepChoice
    data object EndOfArtist : SleepChoice
    data object EndOfList : SleepChoice

    fun encode(): String = when (this) {
        is Minutes -> "minutes:$minutes"
        is At -> "at:$hour:$minute"
        EndOfTrack -> "end"
        EndOfAlbum -> "end_album"
        EndOfArtist -> "end_artist"
        EndOfList -> "end_list"
    }

    companion object {
        fun decode(value: String?): SleepChoice? {
            val parts = value?.split(':') ?: return null
            return when (parts.firstOrNull()) {
                "minutes" -> parts.getOrNull(1)?.toIntOrNull()?.let(::Minutes)
                "at" -> {
                    val hour = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val minute = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    At(hour, minute)
                }
                "end" -> EndOfTrack
                "end_album" -> EndOfAlbum
                "end_artist" -> EndOfArtist
                "end_list" -> EndOfList
                else -> null
            }
        }
    }
}
