package com.sadurni.attacca.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.sadurni.attacca.settings.ThemeStore
import java.io.File

/**
 * Typefaces of a theme: TTF or OTF files (variable ones too) kept in the theme's folder, so they
 * travel with it. Bold and italic are drawn from the one file when it has no others.
 */
object ThemeFonts {

    private const val MAX_BYTES = 30L * 1024 * 1024

    private val families = HashMap<String, FontFamily>()

    /**
     * Copies the font at [uri] into the theme [themeId]; returns its file name (it keeps the
     * original name, readable in the editor), or null when it is not a font Android can use. Blocking.
     */
    fun import(context: Context, uri: Uri, themeId: String): String? {
        val original = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "font"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            data.takeIf { it.size in 12..MAX_BYTES }
        } ?: return null
        val extension = when (String(bytes, 0, 4, Charsets.ISO_8859_1)) {
            "OTTO" -> "otf"
            "\u0000\u0001\u0000\u0000", "true", "ttcf" -> "ttf"
            else -> return null // WOFF/WOFF2 and anything else
        }
        val dir = ThemeStore.imagesDir(themeId).apply { mkdirs() }
        val base = original.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_").take(40).ifBlank { "font" }
        val file = File(dir, "font-$base-${System.currentTimeMillis() % 100000}.$extension")
        file.writeBytes(bytes)
        // Android must be able to draw with it.
        val usable = runCatching { Typeface.Builder(file).build() != null }.getOrDefault(false)
        if (!usable) {
            file.delete()
            return null
        }
        return file.name
    }

    /** The font family of file [name] of theme [themeId], or null when it is not there. */
    fun family(themeId: String, name: String): FontFamily? {
        val file = File(ThemeStore.imagesDir(themeId), name)
        val key = file.path
        synchronized(families) {
            families[key]?.let { return it }
            if (!file.isFile) return null
            val family = runCatching { FontFamily(Font(file)) }.getOrNull() ?: return null
            families[key] = family
            return family
        }
    }

    /** "Lora-Regular" for "font-Lora-Regular-12345.ttf". */
    fun label(name: String): String = name.removePrefix("font-").substringBeforeLast('.').substringBeforeLast('-')

    fun isFont(name: String): Boolean = name.startsWith("font-") && (name.endsWith(".ttf") || name.endsWith(".otf"))
}
