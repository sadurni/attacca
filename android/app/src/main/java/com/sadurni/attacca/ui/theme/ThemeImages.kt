package com.sadurni.attacca.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.sadurni.attacca.settings.ThemeImage
import com.sadurni.attacca.settings.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What an imported picture is for: it is prepared differently. */
enum class ImageUse { ICON, BACKGROUND }

/**
 * The user's own pictures in themes: imported once, prepared for their use (icons square and
 * small, backgrounds no larger than the screen) and kept in the theme's folder; read back with a
 * small memory cache.
 */
object ThemeImages {

    private const val ICON_PX = 192
    private const val BACKGROUND_PX = 2048

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Copies the picture at [uri] into the theme [themeId], prepared for [use]. Null when it cannot be read. Blocking. */
    fun import(context: Context, uri: Uri, themeId: String, use: ImageUse): ThemeImage? {
        val longest = if (use == ImageUse.ICON) ICON_PX * 2 else BACKGROUND_PX
        val decoded = runCatching { decode(context, uri, longest) }.getOrNull() ?: return null
        val prepared = when (use) {
            ImageUse.ICON -> squareIcon(decoded)
            ImageUse.BACKGROUND -> decoded
        }
        val transparent = hasTransparency(prepared)
        val dir = ThemeStore.imagesDir(themeId).apply { mkdirs() }
        val name = "${use.name.lowercase()}-${System.currentTimeMillis()}.${if (transparent) "png" else "jpg"}"
        File(dir, name).outputStream().use { out ->
            if (transparent) {
                prepared.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                prepared.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
        return when (use) {
            // A shape on transparency takes the theme's colors; a photo stays as it is.
            ImageUse.ICON -> ThemeImage(name, dim = 0, tint = transparent)
            ImageUse.BACKGROUND -> ThemeImage(name, dim = DEFAULT_DIM)
        }
    }

    /** Every picture of every theme: (theme id, file name), by theme. Blocking. */
    fun all(): List<Pair<String, String>> =
        ThemeStore.directory.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }.flatMap { dir ->
            dir.listFiles { f -> f.isFile && !ThemeFonts.isFont(f.name) }.orEmpty().sortedBy { it.name }.map { dir.name to it.name }
        }

    /** A picture of theme [fromTheme] for theme [themeId] (copied when it is another theme's). Blocking. */
    fun reuse(fromTheme: String, file: String, themeId: String, use: ImageUse): ThemeImage? {
        val source = File(ThemeStore.imagesDir(fromTheme), file)
        if (!source.isFile) return null
        val name = if (fromTheme == themeId) {
            file
        } else {
            val dir = ThemeStore.imagesDir(themeId).apply { mkdirs() }
            val copy = "${use.name.lowercase()}-${System.currentTimeMillis()}.${source.extension}"
            source.copyTo(File(dir, copy), overwrite = true)
            copy
        }
        val transparent = load(themeId, name)?.let(::hasTransparency) ?: false
        return when (use) {
            ImageUse.ICON -> ThemeImage(name, dim = 0, tint = transparent)
            ImageUse.BACKGROUND -> ThemeImage(name, dim = DEFAULT_DIM)
        }
    }

    /** The picture, decoded no larger than [longest] px (rotated as taken). */
    private fun decode(context: Context, uri: Uri, longest: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, longest.toFloat() / max(w, h))
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val scale = min(1f, longest.toFloat() / max(bitmap.width, bitmap.height))
        return if (scale >= 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
    }

    /** The middle square, [ICON_PX] wide. */
    private fun squareIcon(bitmap: Bitmap): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, ICON_PX, ICON_PX, true)
    }

    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        // A sample of the pixels is enough to tell a shape from a photo.
        val step = max(1, min(bitmap.width, bitmap.height) / 48)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                if ((bitmap.getPixel(x, y) ushr 24) < 250) return true
            }
        }
        return false
    }

    /** A theme's picture, from memory or its file. Blocking. */
    fun load(themeId: String, file: String): Bitmap? {
        val path = File(ThemeStore.imagesDir(themeId), file)
        val key = "${path.path}:${path.lastModified()}"
        cache.get(key)?.let { return it }
        val bitmap = runCatching { BitmapFactory.decodeFile(path.path) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** Softening of a new background, %. */
    const val DEFAULT_DIM = 40
}

/** [image] of the theme in use (or of [themeId]), once loaded: its picture for dark colors or light ones ([dark], by default the colors in use). */
@Composable
fun rememberThemeImage(
    image: ThemeImage?,
    themeId: String = LocalThemeSpec.current.id,
    dark: Boolean = LocalAttaccaColors.current.isDark,
    wide: Boolean = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
): ImageBitmap? {
    val file = image?.fileFor(dark, wide)
    val bitmap by produceState<ImageBitmap?>(null, file, themeId) {
        value = file?.let { withContext(Dispatchers.IO) { ThemeImages.load(themeId, it)?.asImageBitmap() } }
    }
    return bitmap
}

/** Which icon of the app [icon] is, to look for the theme's own. */
private fun slotOf(icon: androidx.compose.ui.graphics.vector.ImageVector): com.sadurni.attacca.settings.AppIcon? = when (icon) {
    Icons.Rounded.Folder -> com.sadurni.attacca.settings.AppIcon.FOLDER
    Icons.AutoMirrored.Rounded.QueueMusic -> com.sadurni.attacca.settings.AppIcon.PLAYLIST
    Icons.Rounded.MusicNote -> com.sadurni.attacca.settings.AppIcon.TRACK
    Icons.Rounded.PlayCircle -> com.sadurni.attacca.settings.AppIcon.PLAYER
    Icons.Rounded.LibraryMusic -> com.sadurni.attacca.settings.AppIcon.LISTS
    Icons.Rounded.Bookmarks -> com.sadurni.attacca.settings.AppIcon.BOOKMARKS
    Icons.Rounded.GraphicEq -> com.sadurni.attacca.settings.AppIcon.SOUND
    Icons.Rounded.Headphones -> com.sadurni.attacca.settings.AppIcon.DEVICES
    Icons.Rounded.Settings -> com.sadurni.attacca.settings.AppIcon.SETTINGS
    else -> null
}

/**
 * An icon of the app, or the theme's own picture for it ([slot]; by default the one [icon] stands
 * for), in [tint] when the picture is a shape.
 */
@Composable
fun ThemedIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    slot: com.sadurni.attacca.settings.AppIcon? = slotOf(icon),
) {
    val own = slot?.let { LocalThemeSpec.current.icons[it] }
    val picture = rememberThemeImage(own)
    if (own != null && picture != null) {
        Image(
            picture,
            contentDescription = contentDescription,
            colorFilter = if (own.tint) androidx.compose.ui.graphics.ColorFilter.tint(tint) else null,
            modifier = modifier.size(24.dp),
        )
    } else {
        androidx.compose.material3.Icon(icon, contentDescription = contentDescription, tint = tint, modifier = modifier)
    }
}

/** A background image filling [modifier]'s space (cropped to fill), softened with the theme's background color. */
@Composable
fun ThemeBackground(image: ThemeImage?, modifier: Modifier = Modifier.fillMaxSize()) {
    val bitmap = rememberThemeImage(image) ?: return
    Box(modifier) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val colors = LocalAttaccaColors.current
        val dim = image?.dimFor(colors.isDark) ?: 0
        if (dim > 0) {
            Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = dim / 100f)))
        }
    }
}

/**
 * The playing track's [cover] filling [modifier]'s space as [style] says: fitted, softened with the
 * theme's background color and blurred (the blur needs Android 12; before, the cover shows sharp).
 */
@Composable
fun CoverBackdrop(cover: android.graphics.Bitmap?, style: com.sadurni.attacca.settings.CoverBackground, modifier: Modifier = Modifier.fillMaxSize()) {
    val bitmap = cover ?: return
    val image = androidx.compose.runtime.remember(bitmap) { bitmap.asImageBitmap() }
    val colors = LocalAttaccaColors.current
    Box(modifier) {
        Image(
            image,
            contentDescription = null,
            contentScale = when (style.fit) {
                com.sadurni.attacca.settings.CoverFit.CROP -> ContentScale.Crop
                com.sadurni.attacca.settings.CoverFit.FIT -> ContentScale.Fit
                com.sadurni.attacca.settings.CoverFit.STRETCH -> ContentScale.FillBounds
            },
            modifier = Modifier.fillMaxSize().then(
                if (style.blur > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    Modifier.blur(style.blur.dp)
                } else {
                    Modifier
                },
            ),
        )
        if (style.dim > 0) Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = style.dim / 100f)))
    }
}

/** The cover behind the screens other than Now playing (see UiSettings.coverBehindScreens). */
val SCREEN_COVER = com.sadurni.attacca.settings.CoverBackground(com.sadurni.attacca.settings.CoverFit.CROP, dim = 65, blur = 60)
