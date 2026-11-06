package com.sadurni.attacca.library

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** The tags the editor shows; empty strings mean "no value". */
data class TagFields(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val track: String = "",
    val disc: String = "",
    val year: String = "",
    val genre: String = "",
    val composer: String = "",
    val comment: String = "",
)

/** Reads and writes file tags with jaudiotagger (MP3, FLAC, Ogg Vorbis, MP4/M4A, WAV, AIFF…). */
object TagEditor {

    private val FIELDS: List<Pair<FieldKey, (TagFields) -> String>> = listOf(
        FieldKey.TITLE to TagFields::title,
        FieldKey.ARTIST to TagFields::artist,
        FieldKey.ALBUM to TagFields::album,
        FieldKey.ALBUM_ARTIST to TagFields::albumArtist,
        FieldKey.TRACK to TagFields::track,
        FieldKey.DISC_NO to TagFields::disc,
        FieldKey.YEAR to TagFields::year,
        FieldKey.GENRE to TagFields::genre,
        FieldKey.COMPOSER to TagFields::composer,
        FieldKey.COMMENT to TagFields::comment,
    )

    init {
        // Avoid desktop-only image classes, and the library's very chatty logging.
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** Blocking; throws when the format is unsupported or the file is unreadable. */
    fun read(file: File): TagFields {
        val tag = AudioFileIO.read(file).tag ?: return TagFields()
        fun get(key: FieldKey) = runCatching { tag.getFirst(key) }.getOrNull().orEmpty()
        return TagFields(
            title = get(FieldKey.TITLE),
            artist = get(FieldKey.ARTIST),
            album = get(FieldKey.ALBUM),
            albumArtist = get(FieldKey.ALBUM_ARTIST),
            track = get(FieldKey.TRACK),
            disc = get(FieldKey.DISC_NO),
            year = get(FieldKey.YEAR),
            genre = get(FieldKey.GENRE),
            composer = get(FieldKey.COMPOSER),
            comment = get(FieldKey.COMMENT),
        )
    }

    /** Writes only the fields that changed, so tags the editor does not show are kept. */
    fun write(file: File, original: TagFields, edited: TagFields) {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        for ((key, getter) in FIELDS) {
            val value = getter(edited).trim()
            if (value == getter(original).trim()) continue
            if (value.isEmpty()) tag.deleteField(key) else tag.setField(key, value)
        }
        audioFile.commit()
    }
}
