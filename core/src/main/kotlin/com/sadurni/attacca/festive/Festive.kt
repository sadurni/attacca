package com.sadurni.attacca.festive

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month

/** The festivities that bring a festive look for some days, by the language and country of the app. */
enum class Festivity {
    /** 18 December to 7 January (the 25th in Japan, where the New Year's days follow). */
    CHRISTMAS,

    /** Christmas on 7 January (Orthodox and Ethiopian): from 25 December to 14 January. */
    ORTHODOX_CHRISTMAS,

    /** The festival of lights: from four days before Diwali to two days after. */
    DIWALI,

    /** The end of Ramadan: from three days before Eid al-Fitr to three days after. */
    EID,

    /** From a week before the Lunar New Year to the Lantern Festival, its fifteenth day. */
    LUNAR_NEW_YEAR,

    /** The Japanese New Year's days: 26 December to 7 January. */
    SHOGATSU,
}

/** The new years welcomed at midnight with the big show in O'rama. */
enum class NewYear {
    GREGORIAN,
    /** Chinese and Korean (Seollal). */
    LUNAR,
    /** The Islamic year (1 Muharram). */
    HIJRI,
    /** The Amazigh year (Yennayer). */
    YENNAYER,
    /** The Ethiopian year (Enkutatash, 11 September). */
    ENKUTATASH,
    /** The Hindu year (Vikram Samvat), the day after Diwali. */
    VIKRAM,
}

/**
 * When the festive look is on and which new years get their show, from tables up to 2040 (no
 * network needed). The lunar and Islamic dates are those usually announced; a day either side is
 * possible where the moon is watched.
 */
object Festive {

    /** A festive stretch: its festivity and first and last day; [kings] while the Three Kings come (1 to 6 January). */
    data class Period(val festivity: Festivity, val start: LocalDate, val end: LocalDate, val kings: Boolean = false) {
        /** The same festivity of the same year: what the "never again" and the toggle keep to. */
        val key: String get() = "${festivity.name}-${start.year}"
    }

    /** A new year's show: which one, its year as shown (number or sign), and when it began. */
    data class Show(val newYear: NewYear, val label: String, val startedAt: LocalDateTime)

    /** How long the show lasts. */
    const val SHOW_MINUTES = 5L

    private val LUNAR = mapOf(
        2025 to "01-29", 2026 to "02-17", 2027 to "02-06", 2028 to "01-26", 2029 to "02-13", 2030 to "02-03",
        2031 to "01-23", 2032 to "02-11", 2033 to "01-31", 2034 to "02-19", 2035 to "02-08", 2036 to "01-28",
        2037 to "02-15", 2038 to "02-04", 2039 to "01-24", 2040 to "02-12",
    )
    private val DIWALI_DATES = mapOf(
        2025 to "10-21", 2026 to "11-08", 2027 to "10-29", 2028 to "10-17", 2029 to "11-05", 2030 to "10-26",
        2031 to "11-14", 2032 to "11-02", 2033 to "10-22", 2034 to "11-10", 2035 to "10-30", 2036 to "10-19",
        2037 to "11-07", 2038 to "10-27", 2039 to "10-17", 2040 to "11-04",
    )

    /** Eid al-Fitr (1 Shawwal); two in 2033. */
    private val EID_DATES = listOf(
        "2025-03-30", "2026-03-20", "2027-03-10", "2028-02-27", "2029-02-15", "2030-02-05", "2031-01-25",
        "2032-01-14", "2033-01-03", "2033-12-23", "2034-12-12", "2035-12-01", "2036-11-20", "2037-11-09",
        "2038-10-30", "2039-10-19", "2040-10-08",
    ).map(LocalDate::parse)

    /** 1 Muharram, with its Hijri year. */
    private val HIJRI_DATES = listOf(
        "2025-06-26" to 1447, "2026-06-16" to 1448, "2027-06-06" to 1449, "2028-05-25" to 1450, "2029-05-14" to 1451,
        "2030-05-04" to 1452, "2031-04-23" to 1453, "2032-04-11" to 1454, "2033-04-01" to 1455, "2034-03-21" to 1456,
        "2035-03-10" to 1457, "2036-02-28" to 1458, "2037-02-16" to 1459, "2038-02-05" to 1460, "2039-01-26" to 1461,
        "2040-01-15" to 1462,
    ).map { (d, y) -> LocalDate.parse(d) to y }

    /** The signs of the lunar years, from the Rat; 2020 was a Rat year. */
    private val SIGNS = listOf("🐀", "🐂", "🐅", "🐇", "🐉", "🐍", "🐎", "🐐", "🐒", "🐓", "🐕", "🐖")

    fun lunarNewYear(year: Int): LocalDate? = LUNAR[year]?.let { LocalDate.parse("$year-$it") }
    fun diwali(year: Int): LocalDate? = DIWALI_DATES[year]?.let { LocalDate.parse("$year-$it") }

    private fun base(language: String) = language.lowercase().substringBefore('-').substringBefore('_')

    /** The festivities of [language] (the app's, like "es", "pt-BR", "zgh") in [country] (two letters, may be empty). */
    fun festivitiesOf(language: String, country: String?): List<Festivity> {
        val c = country.orEmpty().uppercase()
        return when (base(language)) {
            "zh" -> listOf(Festivity.LUNAR_NEW_YEAR)
            "ko" -> listOf(Festivity.CHRISTMAS, Festivity.LUNAR_NEW_YEAR)
            "ja" -> listOf(Festivity.CHRISTMAS, Festivity.SHOGATSU)
            "hi", "kn" -> listOf(Festivity.DIWALI)
            "ar", "zgh", "kab", "ha" -> if (c in setOf("LB")) listOf(Festivity.CHRISTMAS, Festivity.EID) else listOf(Festivity.EID)
            "sw", "yo" -> listOf(Festivity.CHRISTMAS, Festivity.EID)
            "am" -> listOf(Festivity.ORTHODOX_CHRISTMAS)
            "ru" -> if (c in setOf("", "RU", "BY", "KZ")) listOf(Festivity.ORTHODOX_CHRISTMAS) else listOf(Festivity.CHRISTMAS)
            else -> listOf(Festivity.CHRISTMAS)
        }
    }

    /** Where the Three Kings come on 6 January (with their star, after New Year's Day). */
    private fun kingsCountry(language: String, country: String?): Boolean {
        val c = country.orEmpty().uppercase()
        return base(language) in setOf("es", "ca", "gl", "eu", "pt", "pl", "it") && c != "BR" ||
            c in setOf("ES", "MX", "AR", "CO", "PE", "VE", "PR", "UY", "PY", "DO", "PT", "PL", "AD")
    }

    /** The festive stretches of [f] around [year] (that year's and the one before for those crossing New Year). */
    private fun periodsOf(f: Festivity, year: Int, kings: Boolean, language: String): List<Period> = when (f) {
        Festivity.CHRISTMAS -> listOf(year - 1, year).map { y ->
            val end = if (base(language) == "ja") LocalDate.of(y, Month.DECEMBER, 25) else LocalDate.of(y + 1, Month.JANUARY, 7)
            Period(f, LocalDate.of(y, Month.DECEMBER, 18), end, kings)
        }
        Festivity.ORTHODOX_CHRISTMAS -> listOf(year - 1, year).map { y ->
            Period(f, LocalDate.of(y, Month.DECEMBER, 25), LocalDate.of(y + 1, Month.JANUARY, 14))
        }
        Festivity.SHOGATSU -> listOf(year - 1, year).map { y ->
            Period(f, LocalDate.of(y, Month.DECEMBER, 26), LocalDate.of(y + 1, Month.JANUARY, 7))
        }
        Festivity.DIWALI -> listOfNotNull(diwali(year)?.let { Period(f, it.minusDays(4), it.plusDays(2)) })
        Festivity.EID -> EID_DATES.filter { it.year == year || it.year == year + 1 }.map { Period(f, it.minusDays(3), it.plusDays(3)) }
        Festivity.LUNAR_NEW_YEAR -> listOfNotNull(lunarNewYear(year)?.let { Period(f, it.minusDays(7), it.plusDays(14)) })
    }

    /** The festive stretch [date] is in for [language] and [country], or null. */
    fun active(date: LocalDate, language: String, country: String?): Period? {
        val kings = kingsCountry(language, country)
        for (f in festivitiesOf(language, country)) {
            val p = periodsOf(f, date.year, kings, language).firstOrNull { !date.isBefore(it.start) && !date.isAfter(it.end) } ?: continue
            val withKings = p.kings && date.month == Month.JANUARY && date.dayOfMonth in 1..6
            return p.copy(kings = withKings)
        }
        return null
    }

    /** The new years [language] in [country] welcomes. */
    fun newYearsOf(language: String, country: String?): List<NewYear> = listOf(NewYear.GREGORIAN) + when (base(language)) {
        "zh", "ko" -> listOf(NewYear.LUNAR)
        "ar", "ha" -> listOf(NewYear.HIJRI)
        "zgh", "kab" -> listOf(NewYear.YENNAYER)
        "am" -> listOf(NewYear.ENKUTATASH)
        "hi", "kn" -> listOf(NewYear.VIKRAM)
        else -> emptyList()
    }

    /** The first day of [kind]'s year falling in [year] (Gregorian), with how the year is shown; null when unknown. */
    fun newYearDay(kind: NewYear, year: Int, language: String): Pair<LocalDate, String>? = when (kind) {
        NewYear.GREGORIAN -> LocalDate.of(year, 1, 1) to "$year"
        NewYear.LUNAR -> lunarNewYear(year)?.let { it to SIGNS[Math.floorMod(year - 2020, 12)] }
        NewYear.HIJRI -> HIJRI_DATES.firstOrNull { it.first.year == year }?.let { it.first to "${it.second}" }
        // Morocco keeps 14 January, Algeria 12 January.
        NewYear.YENNAYER -> LocalDate.of(year, 1, if (base(language) == "zgh") 14 else 12) to "${year + 950}"
        NewYear.ENKUTATASH -> LocalDate.of(year, 9, 11) to "${year - 7}"
        NewYear.VIKRAM -> diwali(year)?.let { it.plusDays(1) to "${year + 57}" }
    }

    /** The show going on at [now] (it lasts [SHOW_MINUTES] from midnight), or null. */
    fun showAt(now: LocalDateTime, language: String, country: String?): Show? {
        val today = now.toLocalDate()
        if (now.isAfter(today.atStartOfDay().plusMinutes(SHOW_MINUTES))) return null
        for (kind in newYearsOf(language, country)) {
            val (day, label) = newYearDay(kind, today.year, language) ?: continue
            if (day == today) return Show(kind, label, today.atStartOfDay())
        }
        return null
    }

    /** Id prefix of the festive themes (shown, used and copied only while their festivity lasts). */
    const val THEME_PREFIX = "festive:"

    /** The festive theme of [f]: its colours, the accent its own (not the cover's). */
    fun theme(f: Festivity): com.sadurni.attacca.settings.ThemeSpec {
        val id = THEME_PREFIX + f.name.lowercase()
        fun dark(name: String, background: Long, surface: Long, accent: Long) = com.sadurni.attacca.settings.ThemeSpec(
            id, name, dark = true, background = background, surface = surface,
            text = 0xFFFFFFFF, textSecondary = 0xB3FFFFFF, accent = accent, coverAccent = false,
            layout = com.sadurni.attacca.settings.ThemeSpec.MOOD_LAYOUT,
        )
        return when (f) {
            Festivity.CHRISTMAS -> dark("Christmas", 0xFF0F2A1D, 0xFF1C3D2C, 0xFFE53935)
            Festivity.ORTHODOX_CHRISTMAS -> dark("Christmas", 0xFF101C33, 0xFF1D2C4B, 0xFFE8B84A)
            Festivity.DIWALI -> dark("Diwali", 0xFF2A1035, 0xFF3E1C4D, 0xFFFF9F1C)
            Festivity.EID -> dark("Eid", 0xFF0E2A33, 0xFF193F4A, 0xFFD4AF37)
            Festivity.LUNAR_NEW_YEAR -> dark("Lunar New Year", 0xFF4A0C10, 0xFF6E1A1E, 0xFFFFC94A)
            Festivity.SHOGATSU -> com.sadurni.attacca.settings.ThemeSpec(
                id, "New Year", dark = false, background = 0xFFFFF8F0, surface = 0xFFF3E6D8,
                text = 0xFF2B1B17, textSecondary = 0xB32B1B17, accent = 0xFFD7263D, coverAccent = false,
                layout = com.sadurni.attacca.settings.ThemeSpec.MOOD_LAYOUT,
            )
        }
    }

    /** The next show after [now] (to wait for it), or null. */
    fun nextShow(now: LocalDateTime, language: String, country: String?): LocalDateTime? =
        newYearsOf(language, country).flatMap { kind ->
            listOf(now.year, now.year + 1).mapNotNull { y -> newYearDay(kind, y, language)?.first?.atStartOfDay() }
        }.filter { it.isAfter(now) }.minOrNull()
}
