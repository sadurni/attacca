package com.sadurni.attacca.library

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Tag text that some system readers hand back decoded twice: UTF-8 bytes taken as Windows-1252
 * ("D’Amour" arrives as "Dâ€™Amour"). Such text is turned back into bytes and read as UTF-8;
 * anything that is not a clean UTF-8 sequence that way is left as it was.
 */
object TagText {
    private val windows1252 = charset("windows-1252")

    fun repair(text: String): String {
        if (text.none { it.code >= 0x80 }) return text
        val encoder = windows1252.newEncoder()
        val bytes = ByteArrayOutputStream(text.length)
        for (ch in text) {
            when {
                ch.code < 0x80 -> bytes.write(ch.code)
                encoder.canEncode(ch) -> bytes.write(encoder.encode(java.nio.CharBuffer.wrap(charArrayOf(ch))).get().toInt() and 0xFF)
                // Bytes 81, 8D, 8F, 90 and 9D have no Windows-1252 character; readers pass them as C1 controls.
                ch.code in 0x80..0x9F -> bytes.write(ch.code)
                else -> return text
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrDefault(text)
    }

    /** True when [repair] would change the text. */
    fun needsRepair(text: String?): Boolean = text != null && repair(text) != text
}
