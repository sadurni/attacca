package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadurni.attaccaplg.library.LibraryDb
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import java.util.Properties
import javax.swing.JFileChooser

// ---------- Language ----------

/**
 * The window's language, chosen in Settings (or the system's): the app's languages. English and
 * Spanish are written beside each text ([tr]); the others come from `/i18n/<code>.json`, the
 * English text to its translation ("{0}", "{1}"… where the English has a value in it).
 */
object Lang {
    /** The languages by code, each in its own words (as the phone app has them). */
    val CHOICES = linkedMapOf(
        "en" to "English", "es" to "Español", "ca" to "Català", "gl" to "Galego", "eu" to "Euskara",
        "pt" to "Português", "pt-BR" to "Português (Brasil)", "fr" to "Français", "it" to "Italiano",
        "de" to "Deutsch", "nl" to "Nederlands", "sv" to "Svenska", "pl" to "Polski", "ro" to "Română",
        "ru" to "Русский", "uk" to "Українська", "el" to "Ελληνικά", "tr" to "Türkçe", "ja" to "日本語",
        "ko" to "한국어", "zh-CN" to "简体中文", "zh-TW" to "繁體中文",
    )

    var code by mutableStateOf(system())
        private set

    val spanish: Boolean get() = code == "es"

    /** The language of numbers and dates. */
    val locale: Locale get() = Locale.forLanguageTag(code)

    fun apply(choice: String) {
        code = if (choice in CHOICES) choice else system()
    }

    /** The system's language when the window has it, else English. */
    private fun system(): String {
        val l = Locale.getDefault()
        val tag = when {
            l.language == "pt" && l.country == "BR" -> "pt-BR"
            l.language == "zh" && (l.country == "TW" || l.country == "HK" || l.script == "Hant") -> "zh-TW"
            l.language == "zh" -> "zh-CN"
            else -> l.language
        }
        return if (tag in CHOICES) tag else "en"
    }

    private val tables = HashMap<String, Table>()

    fun table(code: String): Table = tables.getOrPut(code) { Table.load(code) }

    /** One language's texts: plain ones by their English, and those with values in them by pattern. */
    class Table(private val plain: Map<String, String>, private val patterns: List<Pair<Regex, String>>) {
        fun of(en: String): String? {
            plain[en]?.let { return it }
            for ((pattern, text) in patterns) {
                val m = pattern.matchEntire(en) ?: continue
                var out = text
                m.groupValues.drop(1).forEachIndexed { i, v -> out = out.replace("{$i}", v) }
                return out
            }
            return null
        }

        companion object {
            fun load(code: String): Table {
                val text = Lang::class.java.getResource("/i18n/$code.json")?.readText() ?: return Table(emptyMap(), emptyList())
                val json = org.json.JSONObject(text)
                val plain = HashMap<String, String>()
                val patterns = ArrayList<Pair<Regex, String>>()
                for (key in json.keys()) {
                    val value = json.getString(key)
                    if (Regex("[{][0-9]+[}]").containsMatchIn(key)) {
                        val parts = key.split(Regex("[{][0-9]+[}]"))
                        patterns += Regex(parts.joinToString("(.*?)") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL) to value
                    } else {
                        plain[key] = value
                    }
                }
                return Table(plain, patterns)
            }
        }
    }
}

/** The text in the window's language: English, Spanish, or the English's translation. */
fun tr(en: String, es: String): String = when (Lang.code) {
    "en" -> en
    "es" -> es
    else -> Lang.table(Lang.code).of(en) ?: en
}

/** A number the way the window's language writes it. */
fun num(value: Double, decimals: Int = 1): String =
    String.format(Lang.locale, "%.${decimals}f", value)

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ---------- Preferences ----------

/** What the window remembers between runs, in %APPDATA%/AttaccaPLG/settings.properties. */
class Prefs(private val file: File = File(LibraryDb.HOME, "settings.properties")) {
    private val props = Properties().apply { runCatching { file.inputStream().use(::load) } }

    operator fun get(key: String): String? = props.getProperty(key)?.takeIf { it.isNotEmpty() }

    operator fun set(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        runCatching {
            file.parentFile.mkdirs()
            file.outputStream().use { props.store(it, "Attacca Desktop") }
        }
    }
}

// ---------- Dialogs ----------

/** The system's file dialogs (folders through Swing, which looks like the system's). */
object Dialogs {
    fun folder(title: String, start: String?): File? {
        val chooser = JFileChooser(start?.let(::File)?.takeIf { it.isDirectory })
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    /** [pattern]: e.g. "*.attlib" or "*.m3u8;*.m3u;*.xspf" (Windows reads it as a filter). */
    fun open(title: String, start: String?, pattern: String): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        start?.let(::File)?.let { dialog.directory = (if (it.isDirectory) it else it.parentFile)?.path }
        dialog.file = pattern
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }?.takeIf { it.isFile }
    }

    /** Several files at once (audio files or playlists). */
    fun openMany(title: String, start: String?, pattern: String): List<File> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        start?.let(::File)?.let { dialog.directory = (if (it.isDirectory) it else it.parentFile)?.path }
        dialog.file = pattern
        dialog.isMultipleMode = true
        dialog.isVisible = true
        return dialog.files.orEmpty().filter { it.isFile }
    }

    fun save(title: String, start: String?, name: String): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        start?.let(::File)?.let { dialog.directory = (if (it.isDirectory) it else it.parentFile)?.path }
        dialog.file = name
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }

    fun show(folder: File) {
        runCatching { java.awt.Desktop.getDesktop().open(folder) }
    }
}

// ---------- Small pieces ----------

/** A titled card holding one part of a page. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A button that opens a menu of [options]; shows the chosen one. */
@Composable
fun <T> Choice(label: String, value: T, options: List<T>, name: (T) -> String, onChoose: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotEmpty()) Text(label, Modifier.width(150.dp))
        Column {
            OutlinedButton(onClick = { open = true }) { Text(name(value)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    DropdownMenuItem(text = { Text(name(option)) }, onClick = {
                        open = false
                        onChoose(option)
                    })
                }
            }
        }
    }
}
