package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.AttaccaShapes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import java.util.Locale

/** Languages of the app (each needs its values-… folder). */
private val LANGUAGE_TAGS = listOf(
    "en", "es", "es-419", "ca", "eu", "gl", "pt", "pt-BR", "fr", "it", "de", "nl", "sv", "pl", "ro",
    "el", "ru", "uk", "tr", "ja", "ko", "zh-CN", "zh-TW",
)

/** An emoji flag for each language where one fits; Catalan, Basque and Galician are drawn (see [RegionFlag]). */
private val FLAGS = mapOf(
    "en" to "🇬🇧", "es" to "🇪🇸", "es-419" to "🌎", "pt" to "🇵🇹", "pt-BR" to "🇧🇷", "fr" to "🇫🇷",
    "it" to "🇮🇹", "de" to "🇩🇪", "nl" to "🇳🇱", "sv" to "🇸🇪", "pl" to "🇵🇱", "ro" to "🇷🇴", "el" to "🇬🇷",
    "ru" to "🇷🇺", "uk" to "🇺🇦", "tr" to "🇹🇷", "ja" to "🇯🇵", "ko" to "🇰🇷", "zh-CN" to "🇨🇳", "zh-TW" to "🇹🇼",
)

/** The language chosen in the app ("" for the phone's), matched to one of the app's languages. */
private fun currentLanguage(): String =
    AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').let { current ->
        when {
            current.isEmpty() || current in LANGUAGE_TAGS -> current
            // The closest option: the exact tag (es-419, pt-BR, zh-TW…) or its language alone.
            else -> current.substringBefore('-').takeIf { it in LANGUAGE_TAGS }.orEmpty()
        }
    }

private fun setLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

/** The language's name in itself and in English (readable whatever the current script). */
private fun names(tag: String): Pair<String, String> {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) } to locale.getDisplayName(Locale.ENGLISH)
}

/** The flag of [tag], or its code in a small box when it has none; a globe for the phone's language. */
@Composable
private fun LanguageMark(tag: String, size: Int = 22) {
    val flag = if (tag.isEmpty()) "🌐" else FLAGS[tag]
    val region = RegionFlag.entries.firstOrNull { it.tag == tag }
    if (flag != null) {
        Text(flag, fontSize = size.sp)
    } else if (region != null) {
        RegionFlagImage(region, size)
    } else {
        Box(
            Modifier
                .clip(AttaccaShapes.small)
                .background(LocalAccent.current.copy(alpha = 0.25f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(tag.substringBefore('-').uppercase(), fontSize = (size * 0.55f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The flags of Catalonia, the Basque Country and Galicia, which emoji fonts do not have: the
 * Senyera (four red stripes on yellow), the Ikurriña (green saltire and white cross on red) and the
 * Galician civil flag (blue diagonal on white).
 */
private enum class RegionFlag(val tag: String) { CATALONIA("ca"), BASQUE_COUNTRY("eu"), GALICIA("gl") }

@Composable
private fun RegionFlagImage(flag: RegionFlag, size: Int) {
    // About the size of an emoji flag of the same font size.
    val width = (size * 1.3f).dp
    val height = (size * 0.88f).dp
    Canvas(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(3.dp))
            .semantics { contentDescription = flag.name },
    ) {
        val w = this.size.width
        val h = this.size.height
        when (flag) {
            RegionFlag.CATALONIA -> {
                drawRect(Color(0xFFFCDD09))
                val stripe = h / 9
                for (i in 0 until 4) drawRect(Color(0xFFDA121A), Offset(0f, stripe * (2 * i + 1)), Size(w, stripe))
            }
            RegionFlag.BASQUE_COUNTRY -> {
                drawRect(Color(0xFFD52B1E))
                val thick = h * 0.14f
                drawLine(Color(0xFF009B48), Offset(0f, 0f), Offset(w, h), thick)
                drawLine(Color(0xFF009B48), Offset(w, 0f), Offset(0f, h), thick)
                drawRect(Color.White, Offset(w / 2 - thick / 2, 0f), Size(thick, h))
                drawRect(Color.White, Offset(0f, h / 2 - thick / 2), Size(w, thick))
            }
            RegionFlag.GALICIA -> {
                drawRect(Color.White)
                drawLine(Color(0xFF0099CC), Offset(0f, 0f), Offset(w, h), h * 0.26f)
                // A hairline frame so the white flag stands out on light backgrounds.
                drawRect(Color(0x33000000), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/** Every language with its mark, its own name and, below, its English name. */
@Composable
private fun LanguageOptions(onPicked: () -> Unit) {
    var selected by remember { mutableStateOf(currentLanguage()) }
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    for (tag in listOf("") + LANGUAGE_TAGS) {
        val (own, english) = if (tag.isEmpty()) stringResource(R.string.language_system) to "System default" else names(tag)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    selected = tag
                    setLanguage(tag)
                    onPicked()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == tag, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = accent))
            Box(Modifier.padding(start = 12.dp, end = 12.dp).size(32.dp), contentAlignment = Alignment.Center) { LanguageMark(tag) }
            Column {
                Text(own, fontSize = AttaccaType.body)
                if (english != own) Text(english, fontSize = AttaccaType.caption, color = grayed)
            }
        }
    }
}

/** The chosen language, as the summary of Settings → Language. */
@Composable
fun languageSummary(): String = currentLanguage().let { tag ->
    if (tag.isEmpty()) stringResource(R.string.language_system) else names(tag).let { (own, english) -> if (own == english) own else "$own · $english" }
}

/** Settings → Language. */
@Composable
fun LanguagePage() {
    LanguageOptions(onPicked = {})
}

/**
 * The current language's flag in the side menu: opens the language list, readable in any script
 * thanks to the flags and the English names.
 */
@Composable
fun LanguageButton() {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.section_language)
    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
        LanguageMark(currentLanguage(), size = 20)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = {
                val title = stringResource(R.string.section_language)
                Text(if (title == "Language") title else "$title · Language")
            },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    LanguageOptions(onPicked = { open = false })
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
