package com.sadurni.attacca.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSpecTest {

    @Test
    fun jsonRoundTripKeepsEveryPart() {
        val spec = ThemeSpec(
            id = "Sea", name = "Sea", dark = true,
            background = 0xFF0A1B2C, surface = 0xFF13293D, text = 0xFFEAF4FF, textSecondary = 0x99EAF4FF,
            accent = 0xFF00B4D8, coverAccent = false, excluded = 0xFFFFC107, danger = 0xFFFF1744,
            corners = 0.5f, textScale = 1.15f, font = ThemeFont.SERIF, playerBackground = PlayerBackground.GRADIENT,
        )
        val back = ThemeSpec.fromJson("Sea", JSONObject(spec.toJson().toString()))
        assertEquals(spec, back)
    }

    @Test
    fun ownFontsTravelWithTheThemeFiles() {
        val spec = ThemeSpec(
            id = "Book", name = "Book", dark = false, background = 0xFFFFFFFF, surface = 0xFFF0F0F0, text = 0xFF000000,
            textSecondary = 0x99000000, accent = 0xFF8B0000, textFont = "font-Lora-123.ttf", titleFont = "font-Playfair-456.otf",
        )
        val back = ThemeSpec.fromJson("Book", JSONObject(spec.toJson().toString()))
        assertEquals("font-Lora-123.ttf", back.textFont)
        assertEquals("font-Playfair-456.otf", back.titleFont)
        // Kept when the theme is saved (unused files of its folder are deleted).
        assertTrue("font-Lora-123.ttf" in back.imageFiles && "font-Playfair-456.otf" in back.imageFiles)
        val none = ThemeSpec.fromJson("x", JSONObject("""{"name": "x"}"""))
        assertEquals(null, none.textFont)
    }

    @Test
    fun missingValuesComeFromTheBuiltInThemeOfTheSameKind() {
        val light = ThemeSpec.fromJson("x", JSONObject("""{"dark": false, "accent": "#123456"}"""))
        assertFalse(light.dark)
        assertEquals(ThemeSpec.WHITE.background, light.background)
        assertEquals(0xFF123456, light.accent)
        assertEquals("x", light.name)
        val dark = ThemeSpec.fromJson("y", JSONObject("""{"name": "Night"}"""))
        assertTrue(dark.dark)
        assertEquals(ThemeSpec.BLACK.surface, dark.surface)
        assertEquals("Night", dark.name)
    }

    @Test
    fun badValuesAreIgnoredOrClamped() {
        val spec = ThemeSpec.fromJson(
            "z",
            JSONObject("""{"text": "nonsense", "corners": 9, "textScale": 0.1, "font": "COMIC", "playerBackground": "?"}"""),
        )
        assertEquals(ThemeSpec.BLACK.text, spec.text)
        assertEquals(ThemeSpec.MAX_CORNERS, spec.corners)
        assertEquals(ThemeSpec.MIN_TEXT, spec.textScale)
        assertEquals(ThemeFont.SYSTEM, spec.font)
        assertEquals(PlayerBackground.PLAIN, spec.playerBackground)
    }

    @Test
    fun switchingKindTurnsLightnessAroundKeepingHueAndOpacity() {
        assertEquals(0xFFFFFFFF, ThemeSpec.invertLightness(0xFF000000))
        assertEquals(0xA6000000, ThemeSpec.invertLightness(0xA6FFFFFF))
        // Dark navy becomes a pale blue of the same hue: blue still the largest channel, red the least.
        val pale = ThemeSpec.invertLightness(0xFF1D2F3C)
        val r = (pale shr 16) and 0xFF
        val g = (pale shr 8) and 0xFF
        val b = pale and 0xFF
        assertTrue(b > g && g > r && r > 0xB0)
        val light = ThemeSpec.BLACK.copy(id = "x", accent = 0xFF00B4D8).converted(toDark = false)
        assertFalse(light.dark)
        assertEquals(0xFFFFFFFF, light.background)
        assertEquals(0xFF000000, light.text)
        assertEquals(0xFF00B4D8, light.accent)
        assertEquals(light, light.converted(toDark = false))
    }

    @Test
    fun bothVersionsFollowThePhoneAndRoundTrip() {
        val both = ThemeSpec.BLACK.copy(id = "b", name = "B", accent = 0xFF00B4D8).withBothVersions()
        assertTrue(both.dark)
        assertEquals(false, both.alternate?.dark)
        val day = both.forSystem(systemDark = false)
        assertFalse(day.dark)
        assertEquals(0xFFFFFFFF, day.background)
        assertEquals(both.palette, day.alternate)
        assertEquals(both, both.forSystem(systemDark = true))
        assertEquals(both, ThemeSpec.fromJson("b", JSONObject(both.toJson().toString())))
        assertEquals(both, day.swapped())
        // A second version of the same kind would never show: dropped.
        val json = both.toJson().put("alternate", both.palette.toJson())
        assertEquals(null, ThemeSpec.fromJson("b", json).alternate)
        // System: White by day, Black by night.
        assertEquals(ThemeSpec.BLACK.background, ThemeSpec.SYSTEM.forSystem(true).background)
        assertEquals(ThemeSpec.WHITE.background, ThemeSpec.SYSTEM.forSystem(false).background)
    }

    @Test
    fun imagesAreReadSafely() {
        val spec = ThemeSpec.WHITE.copy(
            id = "i",
            backgrounds = mapOf(ThemeSection.PLAYER to ThemeImage("bg.jpg", 30), ThemeSection.OTHERS to ThemeImage("o.jpg")),
        )
        val back = ThemeSpec.fromJson("i", JSONObject(spec.toJson().toString()))
        assertEquals(spec.backgrounds, back.backgrounds)
        assertEquals("o.jpg", back.backgroundOf(ThemeSection.FOLDERS)?.file)
        assertEquals(null, ThemeSpec.WHITE.copy(backgrounds = mapOf(ThemeSection.OTHERS to ThemeImage("o.jpg"))).backgroundOf(ThemeSection.PLAYER))
        assertEquals(setOf("bg.jpg", "o.jpg"), back.imageFiles)
        // Names that would leave the theme's folder are ignored; the softening is kept in range.
        assertEquals(null, ThemeImage.fromJson(JSONObject("""{"file":"../x.jpg"}""")))
        assertEquals(null, ThemeImage.fromJson(JSONObject("""{"file":"a/b.jpg"}""")))
        assertEquals(ThemeImage.MAX_DIM, ThemeImage.fromJson(JSONObject("""{"file":"a.jpg","dim":500}"""))?.dim)
    }

    @Test
    fun appIconsAndSofteningByVersionRoundTrip() {
        val spec = ThemeSpec.BLACK.copy(
            id = "v",
            icons = mapOf(AppIcon.FOLDER to ThemeImage("f.png", tint = true)),
            backgrounds = mapOf(ThemeSection.PLAYER to ThemeImage("bg.jpg", dim = 15, dimLight = 80)),
        ).withBothVersions()
        val back = ThemeSpec.fromJson("v", JSONObject(spec.toJson().toString()))
        assertEquals(spec, back)
        assertEquals(setOf("f.png", "bg.jpg"), back.imageFiles)
        val bg = back.backgrounds.getValue(ThemeSection.PLAYER)
        assertEquals(15, bg.dimFor(dark = true))
        assertEquals(80, bg.dimFor(dark = false))
        // Older files without the light softening use the one they have.
        assertEquals(30, ThemeImage.fromJson(JSONObject("""{"file":"a.jpg","dim":30}"""))?.dimFor(dark = false))
    }

    @Test
    fun theSampleThemesRead() {
        for ((name, shown) in listOf("Vinilo" to "Attacca of the 50 Foot Woman", "Aurora" to "Attacca at Dawn")) {
            val file = java.io.File("src/main/assets/themes/$name.json")
            val spec = ThemeSpec.fromJson(name, JSONObject(file.readText()))
            assertEquals(shown, spec.name)
            // Every picture it uses comes with it.
            for (image in spec.imageFiles) assertTrue("$name: $image", java.io.File("src/main/assets/themes/$name/$image").isFile)
        }
    }

    @Test
    fun colorsParseWithAndWithoutAlpha() {
        assertEquals(0xFFAABBCC, ThemeSpec.parseColor("#AABBCC", 0))
        assertEquals(0x80AABBCC, ThemeSpec.parseColor("80aabbcc", 0))
        assertEquals(7L, ThemeSpec.parseColor("#ABC", 7))
        assertEquals("#80AABBCC", ThemeSpec.hex(0x80AABBCC))
    }
}
