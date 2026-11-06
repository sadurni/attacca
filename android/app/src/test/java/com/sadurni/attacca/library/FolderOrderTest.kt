package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderOrderTest {

    private val tracks = listOf(
        "/m/B/Disc 10/1.mp3",
        "/m/A/1.mp3",
        "/m/B/Disc 2/1.mp3",
        "/m/A/Live/1.mp3",
        "/m/B/1.mp3",
        "/other/x.mp3",
    )

    @Test
    fun foldersInBrowsingOrder() {
        assertEquals(listOf("/m/A", "/m/A/Live", "/m/B", "/m/B/Disc 2", "/m/B/Disc 10"), FolderOrder.folders("/m", tracks))
    }

    @Test
    fun nextFolderGoesDownThenOn() {
        val ordered = FolderOrder.folders("/m", tracks)
        assertEquals("/m/A/Live", FolderOrder.next(ordered, "/m/A"))
        assertEquals("/m/B", FolderOrder.next(ordered, "/m/A/Live"))
        assertEquals("/m/B/Disc 10", FolderOrder.next(ordered, "/m/B/Disc 2"))
        assertNull(FolderOrder.next(ordered, "/m/B/Disc 10"))
    }
}
