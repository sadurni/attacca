package com.sadurni.attacca.chain

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVerdictTest {

    private fun t(id: String, folder: String, head: Float?, tail: Float = 0f, title: String = id, album: String = "Album") =
        LiveVerdict.Track(id, folder, title, album, head?.let { Audience(it, tail) })

    @Test
    fun anAudienceAtAnEndMakesATrackLive() {
        val tracks = listOf(t("a", "studio", 0.01f), t("b", "studio", 0.02f, 0.95f), t("c", "studio", 0.0f))
        assertEquals(setOf("b"), LiveVerdict.live(tracks))
    }

    @Test
    fun aConcertAlbumIsLiveWhole() {
        // Songs run together: the audience only between a few of them.
        val concert = listOf(t("1", "c", 0.39f), t("2", "c", 0.32f), t("3", "c", 0.0f), t("4", "c", 0.01f), t("5", "c", 0.0f), t("6", "c", 0.05f))
        assertEquals(setOf("1", "2", "3", "4", "5", "6"), LiveVerdict.live(concert))
    }

    @Test
    fun aStudioAlbumWithLiveExtrasKeepsOnlyThose() {
        val album = (1..12).map { t("s$it", "a", 0.01f) } + listOf(t("x1", "a", 0.9f), t("x2", "a", 0.0f, 0.8f))
        assertEquals(setOf("x1", "x2"), LiveVerdict.live(album))
    }

    @Test
    fun aTaggedLiveAlbumIsLiveUnlessNoAudienceAtAll() {
        // Tagged live, an audience only here and there (or not heard yet): a concert.
        val concert = listOf(t("1", "c", 0.12f).copy(taggedLive = true), t("2", "c", 0.02f).copy(taggedLive = true), t("3", "c", null).copy(taggedLive = true))
        assertEquals(setOf("1", "2", "3"), LiveVerdict.live(concert))
        // Tagged live but heard without any audience: a radio session played in a studio.
        val session = listOf(t("a", "s", 0.01f).copy(taggedLive = true), t("b", "s", 0.03f).copy(taggedLive = true))
        assertEquals(emptySet<String>(), LiveVerdict.live(session))
    }

    @Test
    fun namesSayItOnlyAsAnAddition() {
        val tracks = listOf(
            t("forever", "f", null, title = "Who Wants to Live Forever"),
            t("bracket", "f", null, title = "Love Song (Live)"),
            t("dash", "f", null, title = "Apart - Live at Wembley"),
            t("album", "g", null, album = "Three Sides Live"),
        )
        assertEquals(setOf("bracket", "dash", "album"), LiveVerdict.live(tracks))
    }

    @Test
    fun albumsAreHeardFirstLastMiddleThenTheRest() {
        assertEquals(listOf(0, 9, 5, 1, 2, 3, 4, 6, 7, 8), LiveHearing.order(10))
        assertEquals(listOf(0, 1), LiveHearing.order(2))
        assertEquals(listOf(0), LiveHearing.order(1))
    }

    @Test
    fun theStartIsHeardOnlyWhenTheEndHasNoAudience() {
        assertEquals(false, LiveHearing.needsHead(0.6f))
        assertEquals(true, LiveHearing.needsHead(0.02f))
    }

    @Test
    fun anAlbumIsToldWithoutHearingAllOfIt() {
        fun album(vararg heard: Float?, typed: Boolean = false, tagged: Boolean = false) = heard.mapIndexed { i, a ->
            LiveVerdict.Track("$i", "f", "Song $i", "Album", a?.let { Audience(Audience.NOT_HEARD, it) }, tagged, typed)
        }
        // A concert: two tracks with an audience, most of those heard.
        assertEquals(true, LiveHearing.decided(album(0.8f, 0.6f, null, null, null, null)))
        assertEquals(false, LiveHearing.decided(album(0.8f, 0.0f, 0.0f, null, null, null)))
        // A studio album: four tracks without any audience; two when MusicBrainz says it is not live.
        assertEquals(true, LiveHearing.decided(album(0.0f, 0.01f, 0.02f, 0.0f, null, null)))
        assertEquals(false, LiveHearing.decided(album(0.0f, 0.01f, null, null, null, null)))
        assertEquals(true, LiveHearing.decided(album(0.0f, 0.01f, null, null, null, null, typed = true)))
        // Some audience, not enough to tell: heard on.
        assertEquals(false, LiveHearing.decided(album(0.0f, 0.08f, 0.0f, 0.0f, null, null)))
        // Tagged live: any audience tells it; none at all in two tracks makes it a studio session.
        assertEquals(true, LiveHearing.decided(album(0.12f, null, null, tagged = true)))
        assertEquals(true, LiveHearing.decided(album(0.01f, 0.02f, null, tagged = true)))
        assertEquals(false, LiveHearing.decided(album(0.01f, null, null, tagged = true)))
        // Its name says live: nothing to hear.
        val named = (0 until 5).map { LiveVerdict.Track("$it", "f", "Song", "Live at Home", null) }
        assertEquals(true, LiveHearing.decided(named))
    }

    @Test
    fun tracksNotHeardDoNotCount() {
        // A concert told by its first tracks: the rest were not heard, and are live with it.
        val album = listOf(
            LiveVerdict.Track("1", "c", "A", "Album", Audience(Audience.NOT_HEARD, 0.7f)),
            LiveVerdict.Track("2", "c", "B", "Album", Audience(Audience.NOT_HEARD, 0.5f)),
            LiveVerdict.Track("3", "c", "C", "Album", Audience.SKIPPED),
            LiveVerdict.Track("4", "c", "D", "Album", Audience.SKIPPED),
        )
        assertEquals(setOf("1", "2", "3", "4"), LiveVerdict.live(album))
        assertEquals(false, Audience.SKIPPED.heard)
    }
}
