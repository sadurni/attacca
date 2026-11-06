package com.sadurni.attacca.library

import android.content.Context
import com.sadurni.attacca.Background
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The music folder as a [MusicLibrary], built from the tag cache when first asked and again after
 * each library scan or when its options change. How it groups is kept between sessions.
 */
class MusicIndex(context: Context, private val tagDb: () -> TagDb?, private val musicFolder: () -> String?) {

    private val prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)

    private val _byTrackArtist = MutableStateFlow(prefs.getBoolean(KEY_TRACK_ARTIST, false))

    /** Artists by each track's own artist instead of the album artist. */
    val byTrackArtist: StateFlow<Boolean> = _byTrackArtist.asStateFlow()

    private val _grid = MutableStateFlow(prefs.getBoolean(KEY_GRID, false))

    /** Albums shown as a grid of covers. */
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    private val _byYear = MutableStateFlow(prefs.getBoolean(KEY_BY_YEAR, false))

    /** Album lists by year (oldest first, those without one at the end) instead of by title. */
    val byYear: StateFlow<Boolean> = _byYear.asStateFlow()

    fun setByYear(value: Boolean) {
        _byYear.value = value
        prefs.edit().putBoolean(KEY_BY_YEAR, value).apply()
    }

    // An artist's albums have their own view and order, apart from the album lists.
    private val _artistGrid = MutableStateFlow(prefs.getBoolean(KEY_ARTIST_GRID, false))
    val artistGrid: StateFlow<Boolean> = _artistGrid.asStateFlow()

    private val _artistByYear = MutableStateFlow(prefs.getBoolean(KEY_ARTIST_BY_YEAR, true))
    val artistByYear: StateFlow<Boolean> = _artistByYear.asStateFlow()

    fun setArtistGrid(value: Boolean) {
        _artistGrid.value = value
        prefs.edit().putBoolean(KEY_ARTIST_GRID, value).apply()
    }

    fun setArtistByYear(value: Boolean) {
        _artistByYear.value = value
        prefs.edit().putBoolean(KEY_ARTIST_BY_YEAR, value).apply()
    }

    fun setByTrackArtist(value: Boolean) {
        _byTrackArtist.value = value
        prefs.edit().putBoolean(KEY_TRACK_ARTIST, value).apply()
    }

    fun setGrid(value: Boolean) {
        _grid.value = value
        prefs.edit().putBoolean(KEY_GRID, value).apply()
    }

    @Volatile
    private var cached: Pair<String, MusicLibrary>? = null

    /** The library for the current options; [scanStamp] is when the last scan finished. */
    suspend fun library(scanStamp: Long, byFolder: Boolean): MusicLibrary? = withContext(Background.dispatcher) {
        val folder = musicFolder()?.takeIf { File(it).isDirectory } ?: return@withContext null
        val key = "$folder|$scanStamp|$byFolder|${_byTrackArtist.value}|${IgnoredPaths.version}"
        cached?.let { (k, lib) -> if (k == key) return@withContext lib }
        val tracks = tagDb()?.tracks(folder).orEmpty()
        MusicLibrary(tracks, byFolder = byFolder, byTrackArtist = _byTrackArtist.value).also { cached = key to it }
    }

    private companion object {
        const val KEY_TRACK_ARTIST = "by_track_artist"
        const val KEY_GRID = "albums_grid"
        const val KEY_BY_YEAR = "albums_by_year"
        const val KEY_ARTIST_GRID = "artist_albums_grid"
        const val KEY_ARTIST_BY_YEAR = "artist_albums_by_year"
    }
}
