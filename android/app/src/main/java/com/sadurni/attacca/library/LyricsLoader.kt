// The deprecated flac.VorbisComment base class also matches its vorbis.VorbisComment subclass.
@file:Suppress("DEPRECATION")

package com.sadurni.attacca.library

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.File
import java.nio.charset.Charset

/** Finds lyrics for a track: an .lrc/.txt file next to it first, then embedded tags. */
object LyricsLoader {

    fun load(mediaId: String, metadata: List<Metadata>): Lyrics? =
        fromSidecar(mediaId) ?: metadata.firstNotNullOfOrNull(::fromMetadata)

    fun fromSidecar(mediaId: String): Lyrics? {
        if (mediaId.contains("://")) return null
        val file = File(mediaId)
        val base = file.parentFile ?: return null
        for (ext in listOf("lrc", "LRC", "txt")) {
            val candidate = File(base, "${file.nameWithoutExtension}.$ext")
            if (candidate.isFile) {
                return runCatching { Lyrics.parse(M3uParser.decode(candidate.readBytes())) }.getOrNull()
            }
        }
        return null
    }

    fun fromMetadata(metadata: Metadata): Lyrics? {
        for (i in 0 until metadata.length()) {
            val text = when (val entry = metadata.get(i)) {
                // ID3 USLT is not decoded by Media3; it arrives as raw bytes.
                is BinaryFrame -> if (entry.id == "USLT") decodeUslt(entry.data) else null
                // MP4 "©lyr" is mapped to a USLT text frame.
                is TextInformationFrame -> if (entry.id == "USLT") entry.values.firstOrNull() else null
                is VorbisComment ->
                    if (entry.key.equals("LYRICS", true) || entry.key.equals("UNSYNCEDLYRICS", true)) entry.value else null
                else -> null
            }
            if (!text.isNullOrBlank()) return Lyrics.parse(text)
        }
        return null
    }

    /** USLT: encoding (1 byte), language (3 bytes), descriptor (terminated), lyrics. */
    internal fun decodeUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val charset = when (data[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            else -> Charsets.UTF_8
        }
        val wide = data[0].toInt() == 1 || data[0].toInt() == 2
        // Skip the content descriptor, terminated by 0x00 (or 0x00 0x00 for UTF-16).
        var i = 4
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i >= data.size) return null
        return String(data, i, data.size - i, charset).trimEnd('\u0000')
    }
}
