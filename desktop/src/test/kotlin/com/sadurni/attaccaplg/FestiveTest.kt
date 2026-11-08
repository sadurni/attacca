package com.sadurni.attaccaplg

import com.sadurni.attacca.festive.Festive
import com.sadurni.attacca.festive.Festivity
import com.sadurni.attacca.festive.NewYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The festive days and new years by language and country (shared with the phone). */
class FestiveTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun christmasFromTheEighteenthToTheSeventh() {
        assertNull(Festive.active(d("2026-12-17"), "en", "GB"))
        assertEquals(Festivity.CHRISTMAS, Festive.active(d("2026-12-18"), "en", "GB")?.festivity)
        assertEquals(Festivity.CHRISTMAS, Festive.active(d("2027-01-07"), "fr", "FR")?.festivity)
        assertNull(Festive.active(d("2027-01-08"), "en", "GB"))
    }

    @Test
    fun theKingsComeInSpainAfterNewYear() {
        assertTrue(Festive.active(d("2027-01-05"), "es", "ES")!!.kings)
        assertTrue(!Festive.active(d("2026-12-28"), "es", "ES")!!.kings)
        assertTrue(!Festive.active(d("2027-01-05"), "en", "GB")!!.kings)
    }

    @Test
    fun otherFestivitiesWhereChristmasIsNotKept() {
        assertEquals(Festivity.DIWALI, Festive.active(d("2026-11-08"), "hi", "IN")?.festivity)
        assertEquals(Festivity.DIWALI, Festive.active(d("2026-11-05"), "kn", "IN")?.festivity)
        assertNull(Festive.active(d("2026-12-25"), "hi", "IN"))
        assertEquals(Festivity.EID, Festive.active(d("2027-03-09"), "ar", "MA")?.festivity)
        assertEquals(Festivity.LUNAR_NEW_YEAR, Festive.active(d("2027-02-10"), "zh-CN", "CN")?.festivity)
        assertEquals(Festivity.ORTHODOX_CHRISTMAS, Festive.active(d("2027-01-10"), "am", "ET")?.festivity)
        assertEquals(Festivity.SHOGATSU, Festive.active(d("2027-01-02"), "ja", "JP")?.festivity)
        assertEquals(Festivity.CHRISTMAS, Festive.active(d("2026-12-24"), "ja", "JP")?.festivity)
    }

    @Test
    fun theShowLastsFiveMinutesFromMidnight() {
        val s = Festive.showAt(LocalDateTime.parse("2027-01-01T00:03:00"), "es", "ES")
        assertEquals(NewYear.GREGORIAN, s?.newYear)
        assertEquals("2027", s?.label)
        assertNull(Festive.showAt(LocalDateTime.parse("2027-01-01T00:06:00"), "es", "ES"))
        assertEquals(NewYear.LUNAR, Festive.showAt(LocalDateTime.parse("2027-02-06T00:01:00"), "zh-CN", "CN")?.newYear)
        assertEquals("2977", Festive.showAt(LocalDateTime.parse("2027-01-12T00:00:30"), "kab", "DZ")?.label)
        assertEquals(LocalDateTime.parse("2027-01-01T00:00"), Festive.nextShow(LocalDateTime.parse("2026-11-08T12:00"), "en", "GB"))
    }
}
