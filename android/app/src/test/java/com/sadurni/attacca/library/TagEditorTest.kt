package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TagEditorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A minimal 16-bit stereo WAV with one second of silence. */
    private fun wav(): File {
        val frames = 44100
        val data = frames * 4
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(44100).putInt(44100 * 4)
            .putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(data)
        return temp.newFile("test.wav").apply { writeBytes(buffer.array()) }
    }

    @Test
    fun writesChangedFieldsAndReadsThemBack() {
        val file = wav()
        val original = TagEditor.read(file)
        val edited = original.copy(title = "Moonage Daydream", artist = "David Bowie", track = "5", year = "1972")
        TagEditor.write(file, original, edited)

        val reread = TagEditor.read(file)
        assertEquals("Moonage Daydream", reread.title)
        assertEquals("David Bowie", reread.artist)
        assertEquals("5", reread.track)
        assertEquals("1972", reread.year)
    }

    @Test
    fun clearingAFieldRemovesIt() {
        val file = wav()
        val first = TagEditor.read(file)
        TagEditor.write(file, first, first.copy(title = "Temp", genre = "Glam"))
        val withTitle = TagEditor.read(file)
        TagEditor.write(file, withTitle, withTitle.copy(title = ""))
        val reread = TagEditor.read(file)
        assertEquals("", reread.title)
        assertEquals("Glam", reread.genre)
    }
}
