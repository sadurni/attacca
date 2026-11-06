package com.sadurni.attaccaplg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(path: String) = Themes::class.java.getResource(path)!!.readBytes()

    @Test
    fun galleryThemesHaveTheirPictures() {
        val aurora = Themes.of("gallery:Aurora")
        assertEquals("gallery:Aurora", aurora.id)
        val player = aurora.background("PLAYER")!!
        assertNotNull(aurora.picture(player.file))
        assertNotNull(Themes.picture(aurora, player.file))
        // Every gallery theme finds every picture it names.
        for (theme in Themes.gallery) for (image in theme.backgrounds.values) {
            for (file in listOfNotNull(image.file, image.fileLight, image.fileWide, image.fileWideLight)) {
                assertNotNull("${theme.id} $file", theme.picture(file))
            }
        }
    }

    @Test
    fun aZipExportedFromThePhoneOpensWithItsPictures() {
        // As the phone exports it: <name>.json and its pictures in <name>/.
        val zip = temp.newFile("Mine.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Mine.json"))
            out.write(resource("/themes/Maritime.json"))
            out.closeEntry()
            for (name in listOf("background-coast-night.jpg", "background-coast-day.jpg", "background-waves-night.jpg",
                "background-waves-day.jpg", "background-coast-wide-night.jpg", "background-coast-wide-day.jpg")) {
                out.putNextEntry(ZipEntry("Mine/$name"))
                out.write(resource("/themes/Maritime/$name"))
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("../evil.txt"))
            out.write("no".toByteArray())
            out.closeEntry()
        }
        val home = temp.newFolder("home")
        val json = Themes.import(zip, home)!!
        assertTrue(json.isFile)
        val theme = Themes.of("file:" + json.path)
        assertEquals("file:" + json.path, theme.id)
        val image = theme.background("PLAYER") ?: theme.background("OTHERS")!!
        assertNotNull(Themes.picture(theme, image.fileFor(dark = true, wide = true)))
        assertNotNull(Themes.picture(theme, image.fileFor(dark = false, wide = false)))
        assertTrue(home.walkTopDown().none { it.name == "evil.txt" && it.parentFile != json.parentFile })
        assertNull(Themes.import(temp.newFile("empty.zip").apply { ZipOutputStream(outputStream()).use { } }, home))
    }

    @Test
    fun editedCopyGoesBackToThePhoneWhole() {
        val home = temp.newFolder("home")
        val aurora = Themes.of("gallery:Aurora")
        val mine = Themes.copy(aurora, "Mine", home)!!
        assertEquals("Aurora", mine.spec.origin)
        // Every picture and icon of the gallery theme came with the copy.
        for (f in aurora.spec.imageFiles) assertNotNull(f, mine.picture(f))
        val layout = com.sadurni.attacca.settings.PlayerLayout(mine.spec.layout.blocks.reversed())
        val edited = Themes.save(mine, mine.spec.copy(corners = 1.5f, textScale = 1.1f, layout = layout).withBothVersions())
        assertEquals(1.5f, edited.spec.corners)
        assertNotNull(edited.spec.alternate)
        val zip = temp.newFile("Mine.zip")
        Themes.exportZip(edited, zip)
        // As the phone reads it: the JSON, the pictures by name.
        val back = Themes.of("file:" + Themes.import(zip, temp.newFolder("other"))!!.path)
        assertEquals(edited.spec.copy(id = back.spec.id), back.spec)
        for (f in edited.spec.imageFiles) assertNotNull(f, back.picture(f))
        assertTrue(Themes.own(home).any { it.name == "Mine" })
        Themes.delete(edited)
        assertTrue(Themes.own(home).none { it.name == "Mine" })
    }
}
