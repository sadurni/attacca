package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TagTextTest {
    @Test
    fun mendsDoublyDecodedText() {
        assertEquals("The Dogs D’Amour", TagText.repair("The Dogs Dâ€™Amour"))
        assertEquals("Mala gente con más", TagText.repair("Mala gente con mÃ¡s"))
        assertEquals("Кино", TagText.repair(String("Кино".toByteArray(Charsets.UTF_8), charset("windows-1252"))))
    }

    @Test
    fun keepsRightText() {
        for (text in listOf("The Dogs D’Amour", "Café", "Mala gente con más", "Кино", "Plain", "Ã la carte", "naïve")) {
            assertEquals(text, TagText.repair(text))
        }
    }
}
