package com.sadurni.attacca.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutTest {

    private val values = mapOf("codec" to "FLAC", "samplerate" to "44.1 kHz", "bits" to "16 bit", "year" to "1999", "tag:MOOD" to "Calm")

    private fun render(template: String) = InfoTemplate.render(template) { values[it] }

    @Test
    fun fieldsAreFilledAndEmptyOptionalPartsDropped() {
        assertEquals("FLAC · 44.1 kHz · 16 bit", render("{codec}[ · {bitrate}][ · {samplerate}][ · {bits}]"))
        assertEquals("FLAC", render("{codec}[ · {bitrate} kbps]"))
    }

    @Test
    fun missingFieldOutsideBracketsIsJustEmpty() {
        assertEquals("Year 1999, by", render("Year {year}, by {composer}"))
        assertEquals("", render("{composer}"))
    }

    @Test
    fun rawTagsAndBrokenTemplates() {
        assertEquals("Mood: Calm", render("Mood: {tag:MOOD}"))
        assertEquals("FLAC ·", render("{codec} · [{bitrate}"))
        assertEquals("{codec", render("{codec"))
    }

    @Test
    fun layoutRoundTrip() {
        val layout = PlayerLayout(
            listOf(
                PlayerBlock(BlockType.TEXT, template = "{codec}[ · {bits}]", size = TextSize.LARGE, boxed = true),
                PlayerBlock(BlockType.COVER),
                PlayerBlock(BlockType.SPACE),
                PlayerBlock(BlockType.BUTTONS, actions = listOf(PlayerAction.SEEK_BACK, PlayerAction.PLAY_PAUSE, PlayerAction.SEEK_FORWARD)),
            ),
        )
        assertEquals(layout, PlayerLayout.fromJson(JSONArray(layout.toJson().toString())))
        val spec = ThemeSpec.BLACK.copy(id = "x", layout = layout)
        assertEquals(spec, ThemeSpec.fromJson("x", JSONObject(spec.toJson().toString())))
    }

    @Test
    fun unknownPartsDuplicatesAndEmptyRowsAreDropped() {
        val json = JSONArray(
            """[{"type":"COVER"},{"type":"HOLOGRAM"},{"type":"COVER"},{"type":"BUTTONS","buttons":["NOPE"]},
               {"type":"BUTTONS","buttons":["NEXT","NEXT","PLAY_PAUSE"]}]""",
        )
        val layout = PlayerLayout.fromJson(json)
        assertEquals(listOf(BlockType.COVER, BlockType.BUTTONS), layout.blocks.map { it.type })
        assertEquals(listOf(PlayerAction.NEXT, PlayerAction.PLAY_PAUSE), layout.blocks[1].actions)
        assertTrue(layout.hasPlayButton)
    }

    @Test
    fun boxLinesRoundTripAndOldBoxesKeepTheirLines() {
        val title = PlayerBlock(
            BlockType.TITLE,
            lines = listOf(BoxLine(LineKind.TITLE), BoxLine(LineKind.TEMPLATE, "{composer}", TextSize.HUGE), BoxLine(LineKind.LIST_NAME)),
        )
        val layout = PlayerLayout(listOf(title, PlayerBlock(BlockType.LIST, lines = listOf(BoxLine(LineKind.LIST_DETAILS)))))
        assertEquals(layout, PlayerLayout.fromJson(JSONArray(layout.toJson().toString())))
        val old = PlayerLayout.fromJson(JSONArray("""[{"type":"TITLE"},{"type":"LIST","lines":[{"kind":"WHAT"}]}]"""))
        assertEquals(BoxLine.TITLE_BOX, old.blocks[0].lines)
        assertEquals(BoxLine.LIST_BOX, old.blocks[1].lines)
    }

    @Test
    fun boxesNestSideBySideWithWidths() {
        val inner = PlayerBlock(BlockType.BOX, children = listOf(PlayerBlock(BlockType.TEXT, template = "{year}")), framed = false)
        val row = PlayerBlock(
            BlockType.BOX,
            horizontal = true,
            children = listOf(PlayerBlock(BlockType.COVER, width = 40), inner.copy(width = 60)),
        )
        val layout = PlayerLayout(listOf(row, PlayerBlock(BlockType.PROGRESS)))
        assertEquals(layout, PlayerLayout.fromJson(JSONArray(layout.toJson().toString())))
        assertEquals(5, layout.allBlocks.size)
    }

    @Test
    fun singlePartsOnceInTheWholeTreeAndDepthIsLimited() {
        val json = JSONArray(
            """[{"type":"COVER"},{"type":"BOX","children":[{"type":"COVER"},{"type":"TEXT","template":"x"}]},
               {"type":"BOX","children":[{"type":"BOX","children":[{"type":"BOX","children":[{"type":"BOX","children":
               [{"type":"BOX","children":[{"type":"SPACE"}]}]}]}]}]},{"type":"BOX","children":[]}]""",
        )
        val layout = PlayerLayout.fromJson(json)
        assertEquals(1, layout.allBlocks.count { it.type == BlockType.COVER })
        // The fifth box down is too deep: its parent ends up empty and goes too, up to the fourth level.
        assertTrue(layout.allBlocks.none { it.type == BlockType.SPACE })
        assertEquals(2, layout.blocks.size)
    }

    @Test
    fun landscapeStartsAsTwoColumnsAndIsKeptApart() {
        val wide = PlayerLayout.DEFAULT.toLandscape()
        val row = wide.blocks.single()
        assertTrue(row.horizontal)
        assertFalse(row.framed)
        assertEquals(listOf(42, 58), row.children.map { it.width })
        assertEquals(listOf(BlockType.COVER, BlockType.LIST), row.children[0].children.map { it.type })
        assertEquals(listOf(BlockType.TITLE, BlockType.PROGRESS, BlockType.BUTTONS, BlockType.BUTTONS), row.children[1].children.map { it.type })
        // Without a cover there is nothing to put aside.
        val noCover = PlayerLayout(listOf(PlayerBlock(BlockType.TITLE), PlayerBlock(BlockType.PROGRESS)))
        assertEquals(noCover, noCover.toLandscape())
        val spec = ThemeSpec.BLACK.copy(id = "w", landscape = wide)
        assertEquals(spec, ThemeSpec.fromJson("w", JSONObject(spec.toJson().toString())))
        assertEquals(null, ThemeSpec.fromJson("w", JSONObject(ThemeSpec.BLACK.toJson().toString())).landscape)
    }

    @Test
    fun oldThemesGetTheUsualLayout() {
        assertEquals(PlayerLayout.DEFAULT, ThemeSpec.fromJson("old", JSONObject("""{"name":"Old"}""")).layout)
        assertEquals(PlayerLayout.DEFAULT, PlayerLayout.fromJson(JSONArray("[]")))
        assertFalse(PlayerLayout(listOf(PlayerBlock(BlockType.COVER))).hasPlayButton)
    }
}
