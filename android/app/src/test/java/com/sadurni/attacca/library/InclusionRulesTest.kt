package com.sadurni.attacca.library

import com.sadurni.attacca.library.InclusionRules.Kind
import com.sadurni.attacca.library.InclusionRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

class InclusionRulesTest {

    private val m = "/storage/emulated/0/Music"

    private val tags = object : InclusionRules.Tags {
        override fun pathsOfArtist(artist: String) =
            if (artist == "AC/DC") listOf("$m/acdc/1.mp3", "$m/acdc/2.mp3", "$m/live/acdc.mp3") else emptyList()

        override fun pathsOfAlbum(artist: String, album: String) =
            if (artist == "AC/DC" && album == "Back in Black") listOf("$m/acdc/1.mp3", "$m/acdc/2.mp3") else emptyList()
    }

    private val folders = mapOf("$m/jazz" to listOf("$m/jazz/a.mp3", "$m/jazz/sub/b.mp3"))

    private fun compute(rules: List<Rule>) = InclusionRules.compute(rules, tags) { folders[it].orEmpty() }

    @Test
    fun parsesAndWritesRules() {
        val text = """
            # a comment
            + folder $m/jazz
            + album AC/DC :: Back in Black
            - track /sdcard/Music/jazz/a.mp3
            nonsense line
        """.trimIndent()
        val rules = InclusionRules.parse(text)
        assertEquals(
            listOf(
                Rule(true, Kind.FOLDER, "$m/jazz"),
                Rule(true, Kind.ALBUM, "AC/DC :: Back in Black"),
                Rule(false, Kind.TRACK, "/sdcard/Music/jazz/a.mp3"),
            ),
            rules,
        )
        assertEquals(rules, InclusionRules.parse(InclusionRules.format(rules)))
    }

    @Test
    fun takenOutWinsOverAdded() {
        val rules = listOf(
            Rule(true, Kind.FOLDER, "$m/jazz"),
            Rule(true, Kind.ARTIST, "AC/DC"),
            Rule(false, Kind.TRACK, "/sdcard/Music/jazz/a.mp3"),
            Rule(false, Kind.ALBUM, "AC/DC :: Back in Black"),
        )
        assertEquals(listOf("$m/jazz/sub/b.mp3", "$m/live/acdc.mp3"), compute(rules))
    }

    @Test
    fun takingOutWhatWasAddedDropsItsRule() {
        val rules = listOf(Rule(true, Kind.FOLDER, "$m/jazz"))
        val changed = InclusionRules.change(rules, Rule(true, Kind.FOLDER, "$m/jazz"), add = false) { remaining ->
            compute(remaining).isNotEmpty()
        }
        assertEquals(emptyList<Rule>(), changed)
    }

    @Test
    fun takingOutPartOfSomethingAddedLeavesAnExclusion() {
        val rules = listOf(Rule(true, Kind.ARTIST, "AC/DC"))
        val target = Rule(true, Kind.ALBUM, "AC/DC :: Back in Black")
        val wanted = compute(listOf(target)).toSet()
        val changed = InclusionRules.change(rules, target, add = false) { remaining -> compute(remaining).any { it in wanted } }
        assertEquals(listOf(Rule(true, Kind.ARTIST, "AC/DC"), Rule(false, Kind.ALBUM, "AC/DC :: Back in Black")), changed)
        assertEquals(listOf("$m/live/acdc.mp3"), compute(changed))
        // Adding it again replaces the exclusion.
        val again = InclusionRules.change(changed, target, add = true) { false }
        assertEquals(listOf(Rule(true, Kind.ARTIST, "AC/DC"), Rule(true, Kind.ALBUM, "AC/DC :: Back in Black")), again)
    }

    @Test
    fun takingOutSeveralTracksAtOnce() {
        val rules = listOf(Rule(true, Kind.FOLDER, "$m/jazz"), Rule(true, Kind.TRACK, "$m/other/x.mp3"))
        val targets = listOf(
            Rule(true, Kind.TRACK, "$m/jazz/a.mp3"),
            Rule(true, Kind.TRACK, "$m/other/x.mp3"),
            Rule(true, Kind.TRACK, "$m/jazz/a.mp3"),
        )
        val changed = InclusionRules.changeAll(rules, targets, add = false, ::compute)
        // The track added as such loses its rule; the one the folder takes in gets a "-" once.
        assertEquals(listOf(Rule(true, Kind.FOLDER, "$m/jazz"), Rule(false, Kind.TRACK, "$m/jazz/a.mp3")), changed)
        assertEquals(listOf("$m/jazz/sub/b.mp3"), compute(changed))
    }
}
