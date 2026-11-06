package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.settings.ThemeImage
import com.sadurni.attacca.settings.ThemeSection
import com.sadurni.attacca.settings.ThemeSpec
import com.sadurni.attacca.ui.theme.AttaccaShapes
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.ImageUse
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import com.sadurni.attacca.ui.theme.ThemeImages
import com.sadurni.attacca.ui.theme.rememberThemeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The theme being edited, whose folder new pictures go into. */
val LocalEditedThemeId = staticCompositionLocalOf { "" }

/** Whether the colors being edited are the dark ones, and whether the theme has both versions. */
val LocalEditedVersion = androidx.compose.runtime.compositionLocalOf { true to false }

/**
 * Opens the picture choice: the pictures already in any theme (one of another theme is copied
 * into this one), or one from the phone, prepared for [use]. The chosen one goes to [onPicked].
 */
@Composable
fun rememberImagePicker(use: ImageUse, onPicked: (ThemeImage) -> Unit): () -> Unit {
    val context = LocalContext.current
    val themeId = LocalEditedThemeId.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed = stringResource(R.string.image_failed)
    var gallery by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = withContext(Dispatchers.IO) { ThemeImages.import(context, uri, themeId, use) }
            if (image != null) picked(image) else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }
    val fromPhone = { launcher.launch(arrayOf("image/*")) }
    gallery?.let { images ->
        ImageGalleryDialog(
            images,
            onPick = { (theme, file) ->
                gallery = null
                scope.launch {
                    val image = withContext(Dispatchers.IO) { ThemeImages.reuse(theme, file, themeId, use) }
                    if (image != null) picked(image) else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                }
            },
            onFromPhone = {
                gallery = null
                fromPhone()
            },
            onDismiss = { gallery = null },
        )
    }
    return {
        scope.launch {
            val existing = withContext(Dispatchers.IO) { ThemeImages.all() }
            // Nothing to reuse yet: straight to the phone's pictures.
            if (existing.isEmpty()) fromPhone() else gallery = existing
        }
    }
}

/** The pictures of every theme, by theme, and the way to the phone's pictures. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImageGalleryDialog(
    images: List<Pair<String, String>>,
    onPick: (Pair<String, String>) -> Unit,
    onFromPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAttaccaColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_pick)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                for ((theme, files) in images.groupBy({ it.first }, { it.second })) {
                    Text(theme, fontSize = AttaccaType.secondary, color = colors.textGrayed, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        for (file in files) {
                            val bitmap = rememberThemeImage(ThemeImage(file), theme)
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(AttaccaShapes.small)
                                    .border(1.dp, colors.textGrayed.copy(alpha = 0.4f), AttaccaShapes.small)
                                    .background(colors.surface)
                                    .clickable { onPick(theme to file) },
                            ) {
                                bitmap?.let { Image(it, contentDescription = file, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onFromPhone) { Text(stringResource(R.string.image_from_phone)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A small view of a picture of the edited theme (as an icon: in the text color when tinted). */
@Composable
fun ImageThumb(image: ThemeImage?, size: Dp = 36.dp) {
    val colors = LocalAttaccaColors.current
    val bitmap = rememberThemeImage(image, LocalEditedThemeId.current, LocalEditedVersion.current.first)
    Box(
        Modifier
            .size(size)
            .border(1.dp, colors.textGrayed.copy(alpha = 0.4f), AttaccaShapes.small)
            .background(colors.surface, AttaccaShapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (image?.tint == true) ColorFilter.tint(colors.text) else null,
                modifier = Modifier.size(size - 4.dp),
            )
        }
    }
}

/** A picture to choose, see and take away, with how much it is softened when it is a background. */
@Composable
fun ImageRow(label: String, image: ThemeImage?, use: ImageUse, onChange: (ThemeImage?) -> Unit) {
    val (editedDark, both) = LocalEditedVersion.current
    // With both versions, a background picked for one version leaves the other's as it was.
    val pick = rememberImagePicker(use) { picked ->
        onChange(
            when {
                image == null || !both || use != ImageUse.BACKGROUND -> picked
                editedDark -> image.copy(file = picked.file, fileLight = image.fileLight ?: image.file)
                else -> image.copy(fileLight = picked.file)
            },
        )
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
        ImageThumb(image)
        IconButton(onClick = pick) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = stringResource(R.string.image_pick))
        }
        IconButton(onClick = { onChange(null) }, enabled = image != null) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.image_remove))
        }
    }
    val dark = LocalEditedVersion.current.first
    if (image != null && use == ImageUse.BACKGROUND) {
        ValueSlider(
            label = LocalEditedVersion.current.let { (dark, both) ->
                // With both versions, the softening is that of the colors being edited.
                stringResource(R.string.image_dim) +
                    if (both) " · " + stringResource(if (dark) R.string.colors_dark else R.string.colors_light) else ""
            },
            value = image.dimFor(LocalEditedVersion.current.first).toFloat(),
            range = 0f..ThemeImage.MAX_DIM.toFloat(),
            step = 5f,
            format = { "${it.toInt()} %" },
            onChange = { onChange(image.withDim(dark, it.toInt())) },
        )
    }
}

/** Settings → theme editor: a background picture for Now playing and each screen. */
@Composable
fun BackgroundsSection(spec: ThemeSpec, onChange: (ThemeSpec) -> Unit) {
    SectionTitle(stringResource(R.string.section_backgrounds))
    Hint(stringResource(R.string.backgrounds_hint))
    if (LocalEditedVersion.current.second) Hint(stringResource(R.string.backgrounds_per_version))
    for (section in ThemeSection.entries) {
        val current = spec.backgrounds[section]
        ImageRow(sectionName(section), current, ImageUse.BACKGROUND) { image ->
            onChange(spec.copy(backgrounds = if (image == null) spec.backgrounds - section else spec.backgrounds + (section to image)))
        }
        if (current != null) WidePictureRow(current) { onChange(spec.copy(backgrounds = spec.backgrounds + (section to it))) }
    }
}

/** A background's own picture in landscape (for the version being edited), or the upright one cropped. */
@Composable
private fun WidePictureRow(image: ThemeImage, onChange: (ThemeImage) -> Unit) {
    val (editedDark, both) = LocalEditedVersion.current
    val light = both && !editedDark
    val pick = rememberImagePicker(ImageUse.BACKGROUND) { picked ->
        onChange(if (light) image.copy(fileWideLight = picked.file, fileWide = image.fileWide ?: picked.file) else image.copy(fileWide = picked.file))
    }
    val own = if (light) image.fileWideLight ?: image.fileWide else image.fileWide
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.image_landscape), fontSize = AttaccaType.secondary, modifier = Modifier.weight(1f))
        own?.let { ImageThumb(ThemeImage(it), 30.dp) }
        IconButton(onClick = pick) { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = stringResource(R.string.image_pick)) }
        IconButton(onClick = {
            onChange(if (light) image.copy(fileWideLight = null) else image.copy(fileWide = null, fileWideLight = null))
        }, enabled = own != null) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.image_remove))
        }
    }
}

/** Settings → theme editor: own pictures for icons of the app (folder, list, track… and the side menu). */
@Composable
fun AppIconsSection(spec: ThemeSpec, onChange: (ThemeSpec) -> Unit) {
    SectionTitle(stringResource(R.string.section_app_icons))
    Hint(stringResource(R.string.app_icons_hint))
    for (slot in com.sadurni.attacca.settings.AppIcon.entries) {
        val image = spec.icons[slot]
        fun set(new: ThemeImage?) = onChange(spec.copy(icons = if (new == null) spec.icons - slot else spec.icons + (slot to new)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                appIconDefault(slot),
                contentDescription = null,
                tint = LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.padding(start = 16.dp).size(22.dp),
            )
            Box(Modifier.weight(1f)) { ImageRow(appIconName(slot), image, ImageUse.ICON) { set(it) } }
        }
        if (image != null) {
            SwitchRow(stringResource(R.string.icon_tinted), image.tint, { set(image.copy(tint = it)) })
        }
    }
}

private fun appIconDefault(slot: com.sadurni.attacca.settings.AppIcon) = when (slot) {
    com.sadurni.attacca.settings.AppIcon.FOLDER -> Icons.Rounded.Folder
    com.sadurni.attacca.settings.AppIcon.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
    com.sadurni.attacca.settings.AppIcon.TRACK -> Icons.Rounded.MusicNote
    com.sadurni.attacca.settings.AppIcon.NO_COVER -> Icons.Rounded.Album
    com.sadurni.attacca.settings.AppIcon.PLAYER -> Icons.Rounded.PlayCircle
    com.sadurni.attacca.settings.AppIcon.LISTS -> Icons.Rounded.LibraryMusic
    com.sadurni.attacca.settings.AppIcon.BOOKMARKS -> Icons.Rounded.Bookmarks
    com.sadurni.attacca.settings.AppIcon.SOUND -> Icons.Rounded.GraphicEq
    com.sadurni.attacca.settings.AppIcon.DEVICES -> Icons.Rounded.Headphones
    com.sadurni.attacca.settings.AppIcon.SETTINGS -> Icons.Rounded.Settings
}

@Composable
private fun appIconName(slot: com.sadurni.attacca.settings.AppIcon): String = stringResource(
    when (slot) {
        com.sadurni.attacca.settings.AppIcon.FOLDER -> R.string.icon_folder
        com.sadurni.attacca.settings.AppIcon.PLAYLIST -> R.string.icon_playlist
        com.sadurni.attacca.settings.AppIcon.TRACK -> R.string.icon_track
        com.sadurni.attacca.settings.AppIcon.NO_COVER -> R.string.icon_no_cover
        com.sadurni.attacca.settings.AppIcon.PLAYER -> R.string.preview_player
        com.sadurni.attacca.settings.AppIcon.LISTS -> R.string.nav_lists
        com.sadurni.attacca.settings.AppIcon.BOOKMARKS -> R.string.action_bookmarks
        com.sadurni.attacca.settings.AppIcon.SOUND -> R.string.nav_sound
        com.sadurni.attacca.settings.AppIcon.DEVICES -> R.string.nav_devices
        com.sadurni.attacca.settings.AppIcon.SETTINGS -> R.string.nav_settings
    },
)

@Composable
private fun sectionName(section: ThemeSection): String = stringResource(
    when (section) {
        ThemeSection.PLAYER -> R.string.preview_player
        ThemeSection.FOLDERS -> R.string.nav_folders
        ThemeSection.LISTS -> R.string.nav_lists
        ThemeSection.QUEUE -> R.string.nav_queue
        ThemeSection.BOOKMARKS -> R.string.action_bookmarks
        ThemeSection.SOUND -> R.string.nav_sound
        ThemeSection.DEVICES -> R.string.nav_devices
        ThemeSection.SETTINGS -> R.string.nav_settings
        ThemeSection.OTHERS -> R.string.backgrounds_others
    },
)

/** A box's background from the playing track's cover: on or off, how it fits, softening and blur. */
@Composable
fun CoverRow(cover: com.sadurni.attacca.settings.CoverBackground?, onChange: (com.sadurni.attacca.settings.CoverBackground?) -> Unit) {
    SwitchRow(stringResource(R.string.box_cover), cover != null, { onChange(if (it) com.sadurni.attacca.settings.CoverBackground() else null) })
    if (cover == null) return
    val fits = listOf(
        com.sadurni.attacca.settings.CoverFit.CROP to R.string.cover_fit_crop,
        com.sadurni.attacca.settings.CoverFit.FIT to R.string.cover_fit_fit,
        com.sadurni.attacca.settings.CoverFit.STRETCH to R.string.cover_fit_stretch,
    )
    for ((fit, label) in fits) RadioRow(stringResource(label), cover.fit == fit) { onChange(cover.copy(fit = fit)) }
    ValueSlider(
        label = stringResource(R.string.image_dim),
        value = cover.dim.toFloat(),
        range = 0f..ThemeImage.MAX_DIM.toFloat(),
        step = 5f,
        format = { "${it.toInt()} %" },
        onChange = { onChange(cover.copy(dim = it.toInt())) },
    )
    val off = stringResource(R.string.off)
    ValueSlider(
        label = stringResource(R.string.cover_blur),
        value = cover.blur.toFloat(),
        range = 0f..com.sadurni.attacca.settings.CoverBackground.MAX_BLUR.toFloat(),
        step = 5f,
        format = { if (it == 0f) off else "${it.toInt()} dp" },
        onChange = { onChange(cover.copy(blur = it.toInt())) },
    )
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) Hint(stringResource(R.string.cover_blur_old))
}
