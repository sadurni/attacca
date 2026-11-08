package com.sadurni.attaccaplg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sadurni.attacca.festive.Festive
import com.sadurni.attacca.festive.Festivity
import com.sadurni.attacca.festive.NewYear
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The festive look of these days on the desktop, as on the phone ([Festive]): by the window's
 * language and the computer's country. On by itself when its days begin, unless the theme was
 * ever chosen by hand on festive days or it was turned off (the switch in O'rama, shown only on
 * its days); then the usual theme, and the festive one gone from the gallery once its days end.
 */
object FestiveDesk {
    private val prefs = java.util.prefs.Preferences.userRoot().node("attacca/festive")

    var period by mutableStateOf<Festive.Period?>(null)
        private set
    var on by mutableStateOf(false)
        private set

    /** The new year's show going on (null: none). */
    var show by mutableStateOf<Festive.Show?>(null)
        private set

    private fun country(): String = Locale.getDefault().country

    fun refresh(today: LocalDate = LocalDate.now()) {
        val p = Festive.active(today, Lang.code, country())
        period = p
        on = p != null && when {
            prefs.getBoolean("on_${p.key}", false) -> true
            prefs.getBoolean("off_${p.key}", false) -> false
            else -> !prefs.getBoolean("never", false)
        }
    }

    fun toggle() {
        val p = period ?: return
        if (!on) {
            prefs.putBoolean("on_${p.key}", true); prefs.remove("off_${p.key}")
        } else {
            prefs.putBoolean("off_${p.key}", true); prefs.remove("on_${p.key}"); prefs.putBoolean("never", true)
        }
        refresh()
    }

    /** A theme chosen by hand: the festive one goes, and never comes on by itself again. */
    fun themeChangedByHand() {
        val p = period ?: return
        prefs.putBoolean("never", true); prefs.putBoolean("off_${p.key}", true); prefs.remove("on_${p.key}")
        refresh()
    }

    fun name(f: Festivity): String = when (f) {
        Festivity.CHRISTMAS, Festivity.ORTHODOX_CHRISTMAS -> tr("Christmas", "Navidad")
        Festivity.DIWALI -> tr("Diwali", "Diwali")
        Festivity.EID -> tr("Eid", "Eid")
        Festivity.LUNAR_NEW_YEAR -> tr("Lunar New Year", "Año nuevo lunar")
        Festivity.SHOGATSU -> tr("New Year's days", "Días de año nuevo")
    }

    /** The festive theme of these days (for the gallery: copied only on its days), or null. */
    fun offered(): Theme? = period?.let { p ->
        val spec = Festive.theme(p.festivity)
        Theme(spec.id, name(p.festivity), spec.copy(name = name(p.festivity)))
    }

    /** The theme to show for [chosen]: the festive one while on. */
    fun effective(chosen: String): String = if (on) period?.let { Festive.theme(it.festivity).id } ?: chosen else chosen

    fun checkShow(now: LocalDateTime = LocalDateTime.now()): Festive.Show? = Festive.showAt(now, Lang.code, country()).also { show = it }

    fun nextShow(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? = Festive.nextShow(now, Lang.code, country())

    fun greeting(s: Festive.Show): String = when (s.newYear) {
        NewYear.GREGORIAN, NewYear.VIKRAM -> tr("Happy New Year ${s.label}!", "¡Feliz año nuevo ${s.label}!")
        NewYear.LUNAR -> tr("Happy Lunar New Year ${s.label}!", "¡Feliz año nuevo lunar ${s.label}!")
        NewYear.HIJRI -> tr("Happy Hijri New Year ${s.label}!", "¡Feliz año nuevo hégira ${s.label}!")
        NewYear.YENNAYER -> tr("Happy Yennayer ${s.label}!", "¡Feliz Yennayer ${s.label}!")
        NewYear.ENKUTATASH -> tr("Happy Enkutatash ${s.label}!", "¡Feliz Enkutatash ${s.label}!")
    }
}
