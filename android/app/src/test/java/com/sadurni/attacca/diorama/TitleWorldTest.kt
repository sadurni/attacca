package com.sadurni.attacca.diorama

import com.sadurni.attacca.chain.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleWorldTest {

    @Test
    fun quietSongNamedForTheSeaGoesUnderIt() {
        assertEquals(World.REEF, Looks.titleWorld("Siren Song", Mood.CALM, 0.3f))
        assertEquals(World.REEF, Looks.titleWorld("La sirena", null, 0.2f))
    }

    @Test
    fun theSoundMustAgree() {
        // A loud, tense song named for the sea keeps its own world.
        assertNull(Looks.titleWorld("Sea of Madness", Mood.TENSE, 0.9f))
        assertEquals(World.VOLCANO, Looks.titleWorld("Fire", Mood.ENERGETIC, 0.8f))
        assertNull(Looks.titleWorld("Fire", Mood.CALM, 0.2f))
    }

    @Test
    fun wholeWordsOnly() {
        // "Start" is not a star, "season" not the sea.
        assertNull(Looks.titleWorld("Start Me Up", Mood.CALM, 0.2f))
        assertNull(Looks.titleWorld("Season of the Witch", Mood.CALM, 0.2f))
        assertEquals(World.SPACE, Looks.titleWorld("Stars", Mood.CALM, 0.2f))
        assertEquals(World.WALL, Looks.titleWorld("The Great Wall", null, 0.5f))
    }
}
