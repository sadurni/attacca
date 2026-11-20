package com.sadurni.attacca.art

import java.util.zip.CRC32

/**
 * Words kept inside a PNG file (its text chunks, which picture viewers and tools read): who made a
 * painting, of which song, and when.
 */
object PngWords {
    /** [png] with [words] (keyword → text, any language: written as UTF-8 text chunks) added after its header. */
    fun with(png: ByteArray, words: Map<String, String>): ByteArray {
        // Signature (8 bytes), then IHDR: length (4) + type (4) + 13 bytes + CRC (4).
        if (png.size < 33 || png[12] != 'I'.code.toByte() || png[13] != 'H'.code.toByte()) return png
        val after = 8 + 4 + 4 + 13 + 4
        val out = java.io.ByteArrayOutputStream(png.size + 256)
        out.write(png, 0, after)
        for ((key, text) in words) {
            if (text.isBlank()) continue
            // iTXt: keyword, 0, not compressed (0), method 0, no language tag (0), no translated keyword (0), text.
            val body = java.io.ByteArrayOutputStream()
            body.write(key.take(79).toByteArray(Charsets.ISO_8859_1))
            body.write(byteArrayOf(0, 0, 0, 0, 0))
            body.write(text.toByteArray(Charsets.UTF_8))
            chunk(out, "iTXt", body.toByteArray())
        }
        out.write(png, after, png.size - after)
        return out.toByteArray()
    }

    /** The words Art-tacca keeps in a painting of [title] by [artist], made at [atMs]. */
    fun forPainting(title: String, artist: String?, album: String?, atMs: Long): Map<String, String> {
        val year = java.util.Calendar.getInstance().apply { timeInMillis = atMs }.get(java.util.Calendar.YEAR)
        val of = listOfNotNull(title.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val time = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).format(java.util.Date(atMs))
        return linkedMapOf(
            "Title" to title,
            "Author" to "Art-tacca (Attacca)",
            "Description" to "Created by Art-tacca (Attacca) $year" + if (of.isNotEmpty()) ", painting $of" else "",
            "Software" to "Art-tacca (Attacca)",
            "Creation Time" to time,
        )
    }

    private fun chunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        val c = crc.value
        out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
    }
}
