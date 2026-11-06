package com.sadurni.attaccaplg.library

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** What the tags say about a track; empty strings when absent. */
data class Tags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
)

/** Reads tags with the same library as Attacca. */
object TagReader {

    init {
        // The tag library is very talkative.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun read(file: File): Tags {
        val tag = runCatching { AudioFileIO.read(file).tag }.getOrNull() ?: return Tags(title = file.nameWithoutExtension)
        fun field(key: FieldKey) = runCatching { tag.getFirst(key) }.getOrNull()?.trim().orEmpty()
        return Tags(
            title = field(FieldKey.TITLE).ifEmpty { file.nameWithoutExtension },
            artist = field(FieldKey.ARTIST),
            album = field(FieldKey.ALBUM),
            albumArtist = field(FieldKey.ALBUM_ARTIST),
            genre = field(FieldKey.GENRE),
            year = Regex("\\d{4}").find(field(FieldKey.YEAR))?.value?.toInt(),
            track = field(FieldKey.TRACK).substringBefore('/').trim().toIntOrNull(),
            disc = field(FieldKey.DISC_NO).substringBefore('/').trim().toIntOrNull(),
        )
    }
}
