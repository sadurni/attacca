package com.sadurni.attacca.playback

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sadurni.attacca.app
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Browses Attacca as a car does (Android Auto uses a media browser like this one) and plays a
 * track chosen in it. Needs audio files on the device: a folder [MUSIC] with at least one
 * subfolder holding audio files.
 */
@RunWith(AndroidJUnit4::class)
class CarLibraryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.app.settings.musicFolder = MUSIC }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaBrowser.Builder(context, token).buildAsync()
        browser = future.get(20, TimeUnit.SECONDS)
    }

    @After
    fun disconnect() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { browser.release() }
    }

    // The browser lives on the main thread; its answers are awaited here.
    private fun children(id: String): List<MediaItem> =
        onMain { browser.getChildren(id, 0, 500, null) }.get(20, TimeUnit.SECONDS).value!!.toList()

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun browsesDownToTracksAndPlaysTheChosenOne() {
        val root = onMain { browser.getLibraryRoot(null) }.get(20, TimeUnit.SECONDS).value!!
        assertTrue(root.mediaMetadata.isBrowsable == true)

        val sections = children(root.mediaId)
        val titles = sections.map { it.mediaMetadata.title.toString() }
        assertTrue("sections: $titles", sections.any { it.mediaId == "D|$MUSIC" })
        assertTrue("sections: $titles", sections.any { it.mediaId == "mine" } && sections.any { it.mediaId == "recent" })

        // Music folder → first subfolder holding tracks → its tracks.
        var folder = sections.first { it.mediaId == "D|$MUSIC" }
        var entries = children(folder.mediaId)
        var guard = 0
        while (entries.none { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false } && guard++ < 5) {
            folder = entries.first { it.mediaMetadata.isBrowsable == true && it.mediaId.startsWith("D|") }
            entries = children(folder.mediaId)
        }
        val tracks = entries.filter { it.mediaId.startsWith("T|D|") }
        assertTrue("tracks in ${folder.mediaId}", tracks.size >= 2)

        // Choosing the second track plays its folder from it.
        val chosen = tracks[1]
        onMain {
            browser.setMediaItem(chosen)
            browser.prepare()
        }
        val expectedFolder = File(folder.mediaId.removePrefix("D|"))
        var playing: String? = null
        repeat(50) {
            Thread.sleep(200)
            playing = onMain { browser.currentMediaItem?.mediaId }
            if (playing != null && !playing!!.startsWith("T|")) return@repeat
        }
        val count = onMain { browser.mediaItemCount }
        val index = onMain { browser.currentMediaItemIndex }
        assertEquals(1, index)
        assertTrue("whole folder loaded: $count", count == tracks.size)
        assertEquals(expectedFolder, File(playing!!).parentFile)
        // The list change reached the app, as from its own screens.
        assertEquals(expectedFolder.path, onMain { (context.app.currentListSource.value as? com.sadurni.attacca.library.ListSource.Folder)?.path })
    }

    @Test
    fun carButtonsSkipToTheNextAlbumAndMarkFavorites() {
        // The whole music folder: several albums, one after the other.
        val sections = children(onMain { browser.getLibraryRoot(null) }.get(20, TimeUnit.SECONDS).value!!.mediaId)
        val music = sections.first { it.mediaId == "D|$MUSIC" }
        onMain {
            browser.setMediaItem(music)
            browser.prepare()
        }
        repeat(50) {
            Thread.sleep(200)
            val id = onMain { browser.currentMediaItem?.mediaId }
            if (id != null && !id.startsWith("D|")) return@repeat
        }
        onMain { browser.seekTo(0, 0) }
        Thread.sleep(500)

        // The car sees the extra buttons.
        val buttons = onMain { browser.mediaButtonPreferences }.mapNotNull { it.sessionCommand?.customAction }
        assertTrue("buttons: $buttons", PlaybackService.ACTION_NEXT_GROUP in buttons && PlaybackService.ACTION_FAVORITE in buttons)

        // Next album: the first track of the next folder.
        val before = File(onMain { browser.currentMediaItem!!.mediaId }).parentFile
        onMain { browser.sendCustomCommand(PlaybackService.NEXT_GROUP_COMMAND, android.os.Bundle.EMPTY) }.get(20, TimeUnit.SECONDS)
        var after: File? = before
        repeat(50) {
            Thread.sleep(200)
            after = File(onMain { browser.currentMediaItem!!.mediaId }).parentFile
            if (after != before) return@repeat
        }
        val index = onMain { browser.currentMediaItemIndex }
        assertTrue("moved from $before to $after", after != before)
        val previousEntry = File(onMain { browser.getMediaItemAt(index - 1).mediaId }).parentFile
        assertEquals("first of its album", before, previousEntry)

        // Favorite: the track goes into the favorites and the button shows it.
        val track = onMain { browser.currentMediaItem!!.mediaId }
        val wasFavorite = com.sadurni.attacca.library.UserLists.contains(com.sadurni.attacca.library.UserLists.favorites, track)
        onMain { browser.sendCustomCommand(PlaybackService.FAVORITE_COMMAND, android.os.Bundle.EMPTY) }.get(20, TimeUnit.SECONDS)
        Thread.sleep(1000)
        assertEquals(!wasFavorite, com.sadurni.attacca.library.UserLists.contains(com.sadurni.attacca.library.UserLists.favorites, track))
        val heart = onMain { browser.mediaButtonPreferences }.first { it.sessionCommand?.customAction == PlaybackService.ACTION_FAVORITE }.icon
        assertEquals(if (wasFavorite) androidx.media3.session.CommandButton.ICON_HEART_UNFILLED else androidx.media3.session.CommandButton.ICON_HEART_FILLED, heart)
        // Leave it as it was.
        onMain { browser.sendCustomCommand(PlaybackService.FAVORITE_COMMAND, android.os.Bundle.EMPTY) }.get(20, TimeUnit.SECONDS)
        Thread.sleep(500)
    }

    private companion object {
        const val MUSIC = "/storage/emulated/0/Music"
    }
}
