package com.sadurni.attacca.ui.player

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.AttaccaShapes
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.CircularProgressIndicator
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.CoverDetails
import com.sadurni.attacca.library.TrackDetails
import com.sadurni.attacca.library.TrackInfo
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.SectionTitle
import com.sadurni.attacca.ui.theme.ArtworkLoader
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** Everything known about one file: audio stream, tags, pictures, analysis and how it plays. */
@Composable
fun TrackInfoScreen(viewModel: PlayerViewModel, mediaId: String, onBack: () -> Unit, onEditTags: (String) -> Unit) {
    val context = LocalContext.current
    var details by remember(mediaId) { mutableStateOf<TrackDetails?>(null) }
    LaunchedEffect(mediaId) {
        details = withContext(Dispatchers.IO) { TrackInfo.read(File(mediaId)) }
    }
    val analyses by context.app.analysisResults.collectAsStateWithLifecycle()
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val analysis = analyses[mediaId]

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.info_title), onBack) {
            IconButton(onClick = { onEditTags(mediaId) }) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.tags_title))
            }
        }
        val info = details
        if (info == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // The pictures first: they are the nicest part.
            SectionTitle(stringResource(R.string.info_covers))
            if (info.covers.isEmpty()) Hint(stringResource(R.string.info_no_cover))
            for (cover in info.covers) CoverRow(cover)

            SectionTitle(stringResource(R.string.info_file))
            InfoRow(stringResource(R.string.info_name), info.file.name)
            InfoRow(stringResource(R.string.info_folder), info.file.parent.orEmpty())
            InfoRow(stringResource(R.string.info_size), Formatter.formatFileSize(context, info.sizeBytes) + " (%,d B)".format(info.sizeBytes))
            InfoRow(
                stringResource(R.string.info_modified),
                DateFormat.getMediumDateFormat(context).format(Date(info.modifiedMs)) + " " +
                    DateFormat.getTimeFormat(context).format(Date(info.modifiedMs)),
            )

            SectionTitle(stringResource(R.string.info_audio))
            val audio = info.audio
            audio.format?.let { InfoRow(stringResource(R.string.info_format), it) }
            audio.codec?.takeIf { it != audio.format }?.let { InfoRow(stringResource(R.string.info_codec), it) }
            audio.bitrateKbps?.let { kbps ->
                val mode = when (audio.variableBitrate) {
                    true -> " · VBR"
                    false -> if (audio.lossless == true) "" else " · CBR"
                    null -> ""
                }
                InfoRow(stringResource(R.string.info_bitrate), "$kbps kbps$mode")
            }
            audio.sampleRateHz?.let { InfoRow(stringResource(R.string.info_sample_rate), "%,d Hz".format(it)) }
            audio.bitsPerSample?.let { InfoRow(stringResource(R.string.info_bit_depth), "$it bit") }
            audio.channels?.let { InfoRow(stringResource(R.string.info_channels), it) }
            audio.lossless?.let {
                InfoRow(stringResource(R.string.info_compression), stringResource(if (it) R.string.info_lossless else R.string.info_lossy))
            }
            audio.durationMs?.let { InfoRow(stringResource(R.string.info_duration), formatPrecise(it)) }

            if (analysis != null) {
                SectionTitle(stringResource(R.string.info_analysis))
                InfoRow(stringResource(R.string.info_decoded_duration), formatPrecise(analysis.durationMs))
                InfoRow(stringResource(R.string.info_leading_silence), formatPrecise(analysis.leadingSilenceMs))
                InfoRow(stringResource(R.string.info_trailing_silence), formatPrecise(analysis.trailingSilenceMs))
                analysis.loudnessLufs?.let { lufs ->
                    InfoRow(stringResource(R.string.info_loudness), "%.1f LUFS · %+.1f dB".format(lufs, com.sadurni.attacca.analysis.LoudnessMeter.REFERENCE_LUFS - lufs))
                }
                if (now.mediaId == mediaId) {
                    val end = now.clipEndMs ?: analysis.durationMs
                    InfoRow(stringResource(R.string.info_played_part), formatPrecise(now.clipStartMs) + " – " + formatPrecise(end))
                    if (now.crossfadeMs > 0) {
                        InfoRow(stringResource(R.string.info_crossfade), formatPrecise(now.crossfadeMs.toLong()))
                    }
                }
            }

            SectionTitle(stringResource(R.string.section_listening))
            val listeningVersion by context.app.listening.version.collectAsStateWithLifecycle()
            val heard = remember(mediaId, listeningVersion) { context.app.listening[mediaId] }
            InfoRow(stringResource(R.string.info_plays), (heard?.plays ?: 0).toString())
            heard?.lastPlayedMs?.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.info_last_played), dateTime(context, it)) }
            heard?.firstPlayedMs?.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.info_first_played), dateTime(context, it)) }
            InfoRow(stringResource(R.string.info_skips), (heard?.skips ?: 0).toString())
            val favoritesName = stringResource(R.string.favorites)
            val inLists by androidx.compose.runtime.produceState<List<String>?>(null, mediaId) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.sadurni.attacca.library.UserLists.all()
                        .filter { runCatching { com.sadurni.attacca.library.UserLists.contains(it, mediaId) }.getOrDefault(false) }
                        .map { if (it == com.sadurni.attacca.library.UserLists.favorites) favoritesName else it.nameWithoutExtension }
                }
            }
            inLists?.let { names ->
                InfoRow(stringResource(R.string.info_in_lists), names.joinToString(", ").ifEmpty { stringResource(R.string.info_in_lists_none) })
            }

            SectionTitle(stringResource(R.string.info_tags))
            info.tagError?.let { Hint(stringResource(R.string.tags_unsupported) + "\n" + it) }
            if (info.tags.isEmpty() && info.tagError == null) Hint(stringResource(R.string.info_no_tags))
            for ((key, value) in info.tags) {
                val known = TAG_NAMES[key.uppercase()]?.let { stringResource(it) }
                InfoRow(if (known != null && !known.equals(key, ignoreCase = true)) "$known ($key)" else key, value)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Readable names for the usual keys of ID3, Vorbis comments and MP4 tags. */
private val TAG_NAMES: Map<String, Int> = buildMap {
    fun put(res: Int, vararg keys: String) = keys.forEach { put(it, res) }
    put(R.string.tag_title, "TIT2", "TT2", "TITLE", "©NAM")
    put(R.string.tag_artist, "TPE1", "TP1", "ARTIST", "©ART")
    put(R.string.tag_album, "TALB", "TAL", "ALBUM", "©ALB")
    put(R.string.tag_album_artist, "TPE2", "TP2", "ALBUMARTIST", "ALBUM ARTIST", "AART")
    put(R.string.tag_track, "TRCK", "TRK", "TRACKNUMBER", "TRKN")
    put(R.string.tag_disc, "TPOS", "TPA", "DISCNUMBER", "DISK")
    put(R.string.tag_year, "TYER", "TYE", "TDRC", "DATE", "YEAR", "©DAY")
    put(R.string.tag_genre, "TCON", "TCO", "GENRE", "©GEN")
    put(R.string.tag_composer, "TCOM", "TCM", "COMPOSER", "©WRT")
    put(R.string.tag_comment, "COMM", "COM", "COMMENT", "DESCRIPTION", "©CMT")
}

private fun dateTime(context: android.content.Context, ms: Long): String =
    DateFormat.getMediumDateFormat(context).format(Date(ms)) + " " + DateFormat.getTimeFormat(context).format(Date(ms))

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Text(
            label,
            fontSize = AttaccaType.secondary,
            color = LocalAttaccaColors.current.textGrayed,
            modifier = Modifier.weight(0.38f).padding(end = 8.dp),
        )
        SelectionContainer(Modifier.weight(0.62f)) {
            Text(value, fontSize = AttaccaType.body)
        }
    }
}

@Composable
private fun CoverRow(cover: CoverDetails) {
    val colors = LocalAttaccaColors.current
    val bitmap by produceState<Bitmap?>(null, cover) {
        value = withContext(Dispatchers.Default) { ArtworkLoader.decode(cover.data, cover.file, maxSize = 512) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        val image = Modifier.size(120.dp).clip(AttaccaShapes.medium)
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = image)
        } ?: Box(image.background(colors.surface))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val source = if (cover.file != null) {
                stringResource(R.string.info_cover_folder)
            } else {
                stringResource(R.string.info_cover_embedded) + " · " + pictureTypeName(cover.pictureType)
            }
            Text(source, fontSize = AttaccaType.body)
            if (cover.width > 0) Text("${cover.width} × ${cover.height} px", fontSize = AttaccaType.secondary)
            val type = cover.mimeType?.substringAfter('/')?.uppercase()
            Text(
                listOfNotNull(type, Formatter.formatFileSize(LocalContext.current, cover.sizeBytes)).joinToString(" · "),
                fontSize = AttaccaType.secondary,
                color = colors.textGrayed,
            )
            cover.description?.let { Text(it, fontSize = AttaccaType.secondary, color = colors.textGrayed) }
            cover.file?.let {
                SelectionContainer { Text(it.path, fontSize = AttaccaType.secondary, color = colors.textGrayed) }
            }
        }
    }
}

@Composable
private fun pictureTypeName(type: Int?): String = when (type) {
    3 -> stringResource(R.string.info_picture_front)
    4 -> stringResource(R.string.info_picture_back)
    null -> stringResource(R.string.info_picture_other)
    else -> stringResource(R.string.info_picture_type, type)
}

/** Minutes and seconds with tenths, for silences and clip points. */
private fun formatPrecise(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) + 50) / 100
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}
