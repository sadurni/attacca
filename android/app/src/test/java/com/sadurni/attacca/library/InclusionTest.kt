package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Test

class InclusionTest {

    @Test
    fun allowListTakesOnlyItsTracks() {
        val inclusion = Inclusion(InclusionMode.ALLOW) { listOf("/storage/emulated/0/Music/a.mp3", "/sdcard/Music/b.mp3") }
        assertEquals(true, inclusion.includes("/storage/emulated/0/Music/a.mp3"))
        // The same file by its other path.
        assertEquals(true, inclusion.includes("/storage/emulated/0/Music/b.mp3"))
        assertEquals(false, inclusion.includes("/storage/emulated/0/Music/c.mp3"))
    }

    @Test
    fun blockListTakesEverythingElse() {
        val inclusion = Inclusion(InclusionMode.BLOCK) { listOf("/storage/emulated/0/Music/a.mp3") }
        assertEquals(false, inclusion.includes("/sdcard/Music/a.mp3"))
        assertEquals(true, inclusion.includes("/storage/emulated/0/Music/c.mp3"))
    }

    @Test
    fun theSameFileByItsOtherPaths() {
        assertEquals("/storage/emulated/0/Music/a.mp3", Inclusion.normalize("/storage/self/primary/Music/a.mp3"))
        assertEquals(
            listOf("/sdcard/Music/a.mp3", "/storage/self/primary/Music/a.mp3", "/mnt/sdcard/Music/a.mp3"),
            Inclusion.aliases("/storage/emulated/0/Music/a.mp3"),
        )
        assertEquals(true, "/storage/emulated/0/Music/a.mp3" in Inclusion.aliases("/sdcard/Music/a.mp3"))
        assertEquals(emptyList<String>(), Inclusion.aliases("/storage/1234-5678/Music/a.mp3"))
    }
}
