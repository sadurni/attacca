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
}
