package com.sadurni.attacca.festive

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.sadurni.attacca.R
import com.sadurni.attacca.settings.ThemeSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The festive look of these days on the phone ([Festive]): which festivity, by the app's language
 * and the phone's country, and whether it is on.
 *
 * It comes on by itself when its days begin, unless the theme was ever chosen by hand during
 * festive days, or it was turned off: then it never comes on by itself again (the switch in
 * O'rama, shown only on its days, still turns it on). When its days are over the usual theme is
 * back, and the festive theme is gone from the gallery too.
 */
object FestiveState {
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("festive", Context.MODE_PRIVATE) }

    private val _period = MutableStateFlow<Festive.Period?>(null)

    /** The festive stretch of today (null: none), whether on or off. */
    val period: StateFlow<Festive.Period?> = _period.asStateFlow()

    private val _on = MutableStateFlow(false)

    /** Whether the festive look is on now. */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** The new year's show going on (null: none). */
    val show = MutableStateFlow<Festive.Show?>(null)

    fun init(context: Context) {
        app = context.applicationContext
        refresh()
    }

    /** The app's language as a tag ("es", "pt-BR", "zgh"), or the phone's. */
    fun language(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').ifEmpty { Locale.getDefault().toLanguageTag() }

    /** The phone's country (two letters), for the countries that keep other days. */
    fun country(): String = AppCompatDelegate.getApplicationLocales()[0]?.country?.ifEmpty { null } ?: Locale.getDefault().country

    /** Works out today's festivity and whether it is on (each day, and when the language changes). */
    fun refresh(today: LocalDate = LocalDate.now()) {
        if (!::app.isInitialized) return
        val p = Festive.active(today, language(), country())
        _period.value = p
        _on.value = p != null && when {
            prefs.getBoolean("on_${p.key}", false) -> true
            prefs.getBoolean("off_${p.key}", false) -> false
            else -> !prefs.getBoolean("never", false)
        }
    }

    /** The switch in O'rama: on or off for this festivity; turned off by hand, it never comes on by itself again. */
    fun toggle() {
        val p = _period.value ?: return
        val now = !_on.value
        prefs.edit().apply {
            if (now) {
                putBoolean("on_${p.key}", true).remove("off_${p.key}")
            } else {
                putBoolean("off_${p.key}", true).remove("on_${p.key}").putBoolean("never", true)
            }
        }.apply()
        refresh()
    }

    /** A theme was chosen by hand: during festive days the festive one goes, and never comes on by itself again. */
    fun themeChangedByHand() {
        val p = _period.value ?: return
        prefs.edit().putBoolean("never", true).putBoolean("off_${p.key}", true).remove("on_${p.key}").apply()
        refresh()
    }

    /** The festive theme in use now, its name in the app's language; null when off. */
    fun theme(): ThemeSpec? {
        val p = _period.value ?: return null
        if (!_on.value) return null
        return offeredTheme(p)
    }

    /** The festive theme of [p] as the gallery offers it (copied only on its days). */
    fun offeredTheme(p: Festive.Period = _period.value ?: error("no festivity")): ThemeSpec =
        Festive.theme(p.festivity).copy(name = app.getString(nameOf(p.festivity)))

    fun nameOf(f: Festivity): Int = when (f) {
        Festivity.CHRISTMAS, Festivity.ORTHODOX_CHRISTMAS -> R.string.festive_christmas
        Festivity.DIWALI -> R.string.festive_diwali
        Festivity.EID -> R.string.festive_eid
        Festivity.LUNAR_NEW_YEAR -> R.string.festive_lunar
        Festivity.SHOGATSU -> R.string.festive_shogatsu
    }

    /** The show going on at [now], or null (updates [show]). */
    fun checkShow(now: LocalDateTime = LocalDateTime.now()): Festive.Show? {
        if (!::app.isInitialized) return null
        return Festive.showAt(now, language(), country()).also { show.value = it }
    }

    /** When the next show begins, or null. */
    fun nextShow(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? =
        if (!::app.isInitialized) null else Festive.nextShow(now, language(), country())

    /** The greeting of [s] in the app's language: "Happy New Year 2027!". */
    fun greeting(s: Festive.Show): String = app.getString(
        when (s.newYear) {
            NewYear.GREGORIAN -> R.string.newyear_greeting
            NewYear.LUNAR -> R.string.newyear_greeting_lunar
            NewYear.HIJRI -> R.string.newyear_greeting_hijri
            NewYear.YENNAYER -> R.string.newyear_greeting_yennayer
            NewYear.ENKUTATASH -> R.string.newyear_greeting_enkutatash
            NewYear.VIKRAM -> R.string.newyear_greeting_vikram
        },
        s.label,
    )
}
