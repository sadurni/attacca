package com.sadurni.attacca.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.BuildConfig
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** The notes of one version: its code, its name and what changed. */
class VersionNotes(val code: Int, val name: String, val text: String)

/**
 * The notes of every version, newest first, in [locale]'s language when written in it (else in
 * English). They are the files F-Droid shows (fastlane/metadata/android/<locale>/changelogs/
 * <versionCode>.txt), copied into the app at build time.
 */
object Changelog {

    private const val DIR = "changelog"
    private const val FALLBACK = "en-US"

    /** 1.2.3 from 123: version codes are major × 100 + minor × 10 + patch. */
    fun nameOf(code: Int): String = "${code / 100}.${code / 10 % 10}.${code % 10}"

    fun load(context: Context, locale: Locale): List<VersionNotes> {
        val assets = context.assets
        val folders = assets.list(DIR).orEmpty().toList()
        val tag = locale.toLanguageTag()
        // The exact language and region, then the language alone (e.g. Spanish from Mexico reads
        // the Latin American notes, Portuguese the European ones), then English.
        val folder = folders.firstOrNull { it.equals(tag, ignoreCase = true) }
            ?: folders.firstOrNull { it.equals("${locale.language}-${locale.country}", ignoreCase = true) }
            ?: folders.filter { it.substringBefore('-').equals(locale.language, ignoreCase = true) }.minByOrNull { it.length }
            ?: FALLBACK
        fun read(from: String): List<VersionNotes> = assets.list("$DIR/$from/changelogs").orEmpty().mapNotNull { file ->
            val code = file.removeSuffix(".txt").toIntOrNull() ?: return@mapNotNull null
            val text = runCatching { assets.open("$DIR/$from/changelogs/$file").bufferedReader().use { it.readText().trim() } }.getOrNull()
            text?.let { VersionNotes(code, nameOf(code), it) }
        }
        // A version written only in English still shows.
        val own = read(folder).associateBy { it.code }
        val english = if (folder == FALLBACK) emptyMap() else read(FALLBACK).associateBy { it.code }
        return (english + own).values.sortedByDescending { it.code }
    }
}

/** What changed in each version, newest first; this version marked. */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val notes by produceState<List<VersionNotes>?>(null, locale) {
        value = withContext(Dispatchers.IO) { runCatching { Changelog.load(context, locale) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.changelog_title), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val list = notes ?: return@Column
            if (list.isEmpty()) Hint(stringResource(R.string.changelog_empty))
            for (version in list) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.changelog_version, version.name),
                        fontSize = AttaccaType.heading,
                        fontWeight = FontWeight.Bold,
                        color = LocalAccent.current,
                        modifier = Modifier.weight(1f),
                    )
                    if (version.code == BuildConfig.VERSION_CODE) {
                        Text(stringResource(R.string.changelog_this_version), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                    }
                }
                Text(version.text, fontSize = AttaccaType.body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
