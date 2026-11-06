package com.sadurni.attaccaplg.ui

import com.sadurni.attaccaplg.generate.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedListsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun madeListsAreKeptAndReplacedByName() {
        val home = temp.newFolder("Lists")
        val root = temp.newFolder("Music")
        val lists = SavedLists(home)
        lists.saveMade(listOf(MadeList(Playlist("calm: evening", listOf("A/1.mp3", "A/2.mp3")), 0.5, 1000)), root)
        assertEquals(1, lists.entries.filter { !it.favorites }.size)
        val e = lists.entries.filter { !it.favorites }.single()
        assertTrue(e.generated)
        assertEquals("calm_ evening", e.name)
        assertEquals(listOf(File(root, "A/1.mp3").path, File(root, "A/2.mp3").path), e.paths)
        lists.remember(e.file, e.paths[1], 42_000)
        // Made again with the same name: replaced, and its old place forgotten.
        lists.saveMade(listOf(MadeList(Playlist("calm: evening", listOf("B/9.mp3")), 0.5, 1000)), root)
        assertEquals(listOf(File(root, "B/9.mp3").path), lists.entries.filter { !it.favorites }.single().paths)
        assertNull(lists.entries.filter { !it.favorites }.single().position)
    }

    @Test
    fun ownListsGetFreeNamesKeepTheirPlaceAndGoAway() {
        val home = temp.newFolder("Lists")
        val lists = SavedLists(home)
        val a = lists.saveAs("Road", listOf("C:/m/1.flac"))
        val b = lists.saveAs("Road", listOf("C:/m/2.flac"))
        assertEquals("Road", a.nameWithoutExtension)
        assertEquals("Road 2", b.nameWithoutExtension)
        lists.addTo(a, listOf("C:/m/3.flac"))
        assertEquals(2, lists.entries.filter { !it.favorites }.first { it.file == a }.paths.size)
        lists.remember(a, File("C:/m/3.flac").path, 61_000)
        // Another start of the app finds where it was left.
        val again = SavedLists(home)
        val entry = again.entries.filter { !it.favorites }.first { it.name == "Road" }
        assertEquals(61_000L, entry.position!!.ms)
        // Renamed: the place goes with it; a taken name is refused.
        assertFalse(again.rename(entry, "Road 2"))
        assertTrue(again.rename(entry, "Trip"))
        val trip = again.entries.filter { !it.favorites }.first { it.name == "Trip" }
        assertEquals(61_000L, trip.position!!.ms)
        again.delete(trip)
        assertFalse(trip.file.exists())
        assertEquals(listOf("Road 2"), again.entries.filter { !it.favorites }.map { it.name })
    }

    @Test
    fun fullPathsWithTheirDriveAreKeptAsTheyAre() {
        // As the lists are written here: "C:\…" (the phone's reader would take the drive off).
        val song = temp.newFile("song.mp3")
        val home = temp.newFolder("Lists")
        val file = SavedLists(home).saveAs("Mine", listOf(song.absolutePath))
        val read = SavedLists(home).read(file)
        assertEquals(listOf(song.absolutePath), read)
        assertTrue(File(read.single()).isFile)
    }

    @Test
    fun relativePathsInAListAreReadFromItsFolder() {
        val home = temp.newFolder("Lists")
        val file = File(home, "Mine/x.m3u8").apply { parentFile.mkdirs(); writeText("#EXTM3U\n#EXTINF:1,a\n../../Music/a.mp3\n\n") }
        assertEquals(listOf(File(file.parentFile, "../../Music/a.mp3").path), SavedLists(home).read(file))
    }
}
