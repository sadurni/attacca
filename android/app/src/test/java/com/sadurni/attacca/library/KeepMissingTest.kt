package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeepMissingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun here(name: String) = folder.newFile(name).absolutePath
    private fun gone(name: String) = File(folder.root, "elsewhere/$name").absolutePath

    @Test
    fun missingEntriesStayAfterTheTrackTheyFollowed() {
        val a = here("a.mp3")
        val b = here("b.mp3")
        val c = here("c.mp3")
        val x = gone("x.mp3")
        val y = gone("y.mp3")
        val z = gone("z.mp3")
        // In the file: x, a, y, b, c, z. Loaded here as a, b, c; then reordered to c, a and b removed.
        val old = listOf(x, a, y, b, c, z).map(::PlaylistEntry)
        val saved = UserLists.keepingMissing(old, listOf(c, a).map(::PlaylistEntry))
        // y followed a; z followed c; x was first. b was removed by hand and stays out.
        assertEquals(listOf(x, c, z, a, y), saved.map { it.location })
    }

    @Test
    fun nothingMissingKeepsTheListAsSaved() {
        val a = here("a.mp3")
        val b = here("b.mp3")
        val saved = UserLists.keepingMissing(listOf(a, b).map(::PlaylistEntry), listOf(b).map(::PlaylistEntry))
        assertEquals(listOf(b), saved.map { it.location })
    }

    private fun File(parent: java.io.File, child: String) = java.io.File(parent, child)
}
