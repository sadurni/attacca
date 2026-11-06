package com.sadurni.attacca.ui.lists

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.ui.common.OpenDocumentAt
import com.sadurni.attacca.ui.common.PickerMemory
import com.sadurni.attacca.ui.common.Thumbnails
import com.sadurni.attacca.ui.common.TrackThumbnail
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The names a cover written here may have; any of them is replaced by a new one. */
private val COVER_FILES = listOf("cover.jpg", "cover.jpeg", "cover.png")

/**
 * The album cover of [track]'s folder: what it shows now, and a picture chosen from the files
 * on the phone, copied into the folder as cover.jpg (or cover.png), asking before replacing one.
 */
@Composable
fun AlbumCoverSection(track: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder = track.parentFile ?: return
    var version by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Uri?>(null) }

    fun copy(uri: Uri) {
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val type = context.contentResolver.getType(uri).orEmpty()
                    val target = File(folder, if (type == "image/png") "cover.png" else "cover.jpg")
                    val temp = File(folder, ".cover-new")
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                        ?: error("no data")
                    COVER_FILES.map { File(folder, it) }.filter { it.exists() }.forEach { old ->
                        Thumbnails.forget(old.path)
                        old.delete()
                    }
                    check(temp.renameTo(target)) { "rename" }
                    AudioFiles.forgetFolderCover(folder)
                    Thumbnails.forget(target.path)
                    target
                }.getOrNull()
            }
            version++
            Toast.makeText(
                context,
                if (written != null) context.getString(R.string.cover_saved, written.name) else context.getString(R.string.cover_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val pick = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.COVER_IMAGE, uri)
        if (COVER_FILES.any { File(folder, it).exists() }) pending = uri else copy(uri)
    }

    SectionTitle(stringResource(R.string.cover_section))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Keyed on the version so a new picture shows at once.
        androidx.compose.runtime.key(version) { TrackThumbnail(track.absolutePath, size = 96.dp) }
        Column(Modifier.padding(start = 16.dp)) {
            OutlinedButton(onClick = {
                pick.launch(arrayOf("image/jpeg", "image/png") to PickerMemory.get(context, PickerMemory.COVER_IMAGE))
            }) { Text(stringResource(R.string.cover_choose)) }
        }
    }
    Hint(stringResource(R.string.cover_hint))

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.cover_replace_title)) },
            text = { Text(stringResource(R.string.cover_replace_text, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    copy(uri)
                }) { Text(stringResource(R.string.cover_replace)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
