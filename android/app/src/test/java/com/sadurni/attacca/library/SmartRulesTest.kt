package com.sadurni.attacca.library

import com.sadurni.attacca.library.SmartRules.Condition
import com.sadurni.attacca.library.SmartRules.Field
import com.sadurni.attacca.library.SmartRules.Order
import com.sadurni.attacca.library.SmartRules.PlayedOp
import com.sadurni.attacca.library.SmartRules.Rules
import com.sadurni.attacca.library.SmartRules.TextOp
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SmartRulesTest {

    private val day = 86_400_000L
    private val now = 1000 * day

    private fun track(path: String, artist: String, genre: String, year: Int, plays: Int = 0, lastPlayedDaysAgo: Int? = null, mood: String? = null, addedDaysAgo: Int = 400) =
        SmartRules.Track(
            path, SortTags(path.substringAfterLast('/'), artist, "Album $year", 1, 1, year, 240_000, "", genre),
            modifiedMs = now - addedDaysAgo * day, plays = plays,
            lastPlayedMs = lastPlayedDaysAgo?.let { now - it * day } ?: 0, mood = mood,
        )

    private val tracks = listOf(
        track("/m/Rock/a.mp3", "Queen", "Rock", 1991, plays = 5, lastPlayedDaysAgo = 2, mood = "energetic"),
        track("/m/Rock/b.mp3", "Nirvana", "Grunge Rock", 1993, plays = 1, lastPlayedDaysAgo = 90, mood = "tense"),
        track("/m/Jazz/c.mp3", "Miles Davis", "Jazz", 1959, mood = "calm", addedDaysAgo = 5),
        track("/m/Rock/Live/d.mp3", "Queen", "Rock", 1986, plays = 2, lastPlayedDaysAgo = 40),
    )

    @Test
    fun parseAndFormatRoundTrip() {
        val text = "genre contains rock\nyear 1990-1999\nnot artist is Queen\nplayed not-within 30\nmood energetic, calm\norder random\nlimit 150\n"
        val rules = SmartRules.parse(text)
        assertEquals(5, rules.conditions.size)
        assertEquals(Order.RANDOM, rules.order)
        assertEquals(150, rules.limit)
        assertEquals(rules, SmartRules.parse(SmartRules.format(rules)))
    }

    @Test
    fun everyConditionMustBeMet() {
        val rules = SmartRules.parse("genre contains rock\nyear 1990-1999")
        assertEquals(listOf("/m/Rock/a.mp3", "/m/Rock/b.mp3"), SmartRules.evaluate(rules, tracks, now).map { it.path })
    }

    @Test
    fun notTurnsAConditionRound() {
        val rules = Rules(listOf(Condition(Field.ARTIST, negated = true, textOp = TextOp.IS, text = "queen")))
        assertEquals(listOf("/m/Rock/b.mp3", "/m/Jazz/c.mp3"), SmartRules.evaluate(rules, tracks, now).map { it.path })
    }

    @Test
    fun listeningConditions() {
        fun paths(c: Condition) = SmartRules.evaluate(Rules(listOf(c)), tracks, now).map { it.path }
        assertEquals(listOf("/m/Jazz/c.mp3"), paths(Condition(Field.PLAYED, playedOp = PlayedOp.NEVER)))
        assertEquals(listOf("/m/Rock/b.mp3", "/m/Jazz/c.mp3", "/m/Rock/Live/d.mp3"), paths(Condition(Field.PLAYED, playedOp = PlayedOp.NOT_WITHIN, number = 30)))
        assertEquals(listOf("/m/Rock/a.mp3"), paths(Condition(Field.PLAYED, playedOp = PlayedOp.AT_LEAST, number = 3)))
        assertEquals(listOf("/m/Jazz/c.mp3"), paths(Condition(Field.ADDED, number = 30)))
    }

    @Test
    fun folderTakesItsSubfolders() {
        val rules = SmartRules.parse("folder is /m/Rock")
        assertEquals(3, SmartRules.evaluate(rules, tracks, now).size)
    }

    @Test
    fun ordersAndLimit() {
        val byYear = SmartRules.evaluate(SmartRules.parse("order year"), tracks, now).map { it.tags.year }
        assertEquals(listOf(1959, 1986, 1991, 1993), byYear)
        val mostPlayed = SmartRules.evaluate(SmartRules.parse("order most-played\nlimit 2"), tracks, now).map { it.path }
        assertEquals(listOf("/m/Rock/a.mp3", "/m/Rock/Live/d.mp3"), mostPlayed)
        assertEquals(2, SmartRules.evaluate(SmartRules.parse("order random\nlimit 2"), tracks, now, Random(1)).size)
    }

    @Test
    fun unknownAnalysisNeverMatches() {
        val rules = SmartRules.parse("bpm 100-140")
        assertEquals(0, SmartRules.evaluate(rules, tracks, now).size)
        val moods = SmartRules.parse("mood calm, energetic")
        assertEquals(listOf("/m/Rock/a.mp3", "/m/Jazz/c.mp3"), SmartRules.evaluate(moods, tracks, now).map { it.path })
    }
}
