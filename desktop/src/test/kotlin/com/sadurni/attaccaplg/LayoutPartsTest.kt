package com.sadurni.attaccaplg

import com.sadurni.attacca.settings.BlockType
import com.sadurni.attacca.settings.LayoutParts
import com.sadurni.attacca.settings.PlayerBlock
import com.sadurni.attacca.settings.PlayerLayout
import com.sadurni.attacca.settings.ThemeImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutPartsTest {
    private val cover = PlayerBlock(BlockType.COVER)
    private val title = PlayerBlock(BlockType.TITLE)
    private val progress = PlayerBlock(BlockType.PROGRESS)
    private val list = PlayerBlock(BlockType.LIST)
    private val box = PlayerBlock(BlockType.BOX, children = listOf(progress))
    private val types = { blocks: List<PlayerBlock> -> blocks.map { b -> if (b.type == BlockType.BOX) "box(" + b.children.joinToString(",") { it.type.name } + ")" else b.type.name } }

    @Test
    fun titleGoesIntoABoxAndOutAgain() {
        val layout = listOf(cover, title, box, list)
        val inside = LayoutParts.move(layout, listOf(1), listOf(2), 1)!!
        assertEquals(listOf("COVER", "box(PROGRESS,TITLE)", "LIST"), types(inside))
        // Out again, right after its box.
        val out = LayoutParts.move(inside, listOf(1, 1), emptyList(), 2)!!
        assertEquals(listOf("COVER", "box(PROGRESS)", "TITLE", "LIST"), types(out))
        // The last part out of a box: the empty box goes.
        val emptied = LayoutParts.move(out, listOf(1, 0), emptyList(), 0)!!
        assertEquals(listOf("PROGRESS", "COVER", "TITLE", "LIST"), types(emptied))
        // Down one place (as the editor's arrow does: index + 2 before the move).
        assertEquals(listOf("COVER", "box(PROGRESS)", "TITLE", "LIST"), types(LayoutParts.move(layout, listOf(1), emptyList(), 3)!!))
        // A box never into itself.
        assertNull(LayoutParts.move(layout, listOf(2), listOf(2), 0))
    }

    @Test
    fun pastingKeepsPartsOnceAndBringsThePictures() {
        val present = PlayerLayout(listOf(cover, title, box, list)).allBlocks.map { it.type }.toSet()
        assertFalse(LayoutParts.fits(title, 0, present))
        assertTrue(LayoutParts.fits(PlayerBlock(BlockType.SPACE), 0, present))
        assertTrue(LayoutParts.fits(title, 1, present - BlockType.TITLE))

        val framed = title.copy(image = ThemeImage("wood.png", fileLight = "wood-light.png"))
        val folder = Files.createTempDirectory("theme").toFile().apply { deleteOnExit() }
        folder.resolve("wood.png").writeBytes(byteArrayOf(9)) // another picture with the same name
        val clip = LayoutParts.Clip(framed, "other") { name -> byteArrayOf(name.length.toByte()) }
        val pasted = LayoutParts.paste(clip, "mine", folder)
        assertEquals("wood (2).png", pasted.image!!.file)
        assertEquals("wood-light.png", pasted.image!!.fileLight)
        assertTrue(folder.resolve("wood (2).png").isFile && folder.resolve("wood-light.png").isFile)
        // In the same theme, as it is.
        assertEquals(framed, LayoutParts.paste(LayoutParts.Clip(framed, "mine") { null }, "mine", folder))
    }
}
