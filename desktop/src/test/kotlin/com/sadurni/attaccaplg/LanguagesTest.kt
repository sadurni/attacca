package com.sadurni.attaccaplg

import com.sadurni.attaccaplg.ui.Lang
import com.sadurni.attaccaplg.ui.tr
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanguagesTest {
    @AfterTest
    fun back() = Lang.apply("en")

    @Test
    fun everyLanguageHasItsTexts() {
        for (code in Lang.CHOICES.keys - setOf("en", "es")) {
            Lang.apply(code)
            assertEquals(code, Lang.code)
            assertNotEquals("Settings", tr("Settings", "Ajustes"), code)
            // A text with a value in it keeps the value.
            val n = 1234
            val text = tr("$n tracks", "$n pistas")
            assertTrue("1234" in text && text != "$n tracks", "$code: $text")
        }
        Lang.apply("es")
        assertEquals("Ajustes", tr("Settings", "Ajustes"))
        Lang.apply("en")
        assertEquals("Settings", tr("Settings", "Ajustes"))
    }
}
