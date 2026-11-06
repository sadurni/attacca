package com.sadurni.attacca.ui.lists

import com.sadurni.attacca.ui.theme.AttaccaType
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R
import com.sadurni.attacca.library.TagEditor
import com.sadurni.attacca.library.TagFields
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TagEditorScreen(mediaId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember(mediaId) { File(mediaId) }
    var original by remember { mutableStateOf<TagFields?>(null) }
    var edited by remember { mutableStateOf(TagFields()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.tags_saved)
    val unsupported = stringResource(R.string.tags_unsupported)

    LaunchedEffect(mediaId) {
        val result = withContext(Dispatchers.IO) { runCatching { TagEditor.read(file) } }
        result.onSuccess {
            original = it
            edited = it
        }.onFailure { error = unsupported + "\n" + (it.message ?: it.javaClass.simpleName) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.tags_title), onBack) {
            val base = original
            if (base != null && base != edited && !saving) {
                IconButton(onClick = {
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { TagEditor.write(file, base, edited) } }
                        saving = false
                        result.onSuccess {
                            Toast.makeText(context, savedMessage, Toast.LENGTH_LONG).show()
                            onBack()
                        }.onFailure {
                            Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.tags_save))
                }
            }
        }
        Text(
            file.name,
            fontSize = AttaccaType.secondary,
            color = LocalAttaccaColors.current.textGrayed,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            error != null -> Hint(error!!)
            original == null || saving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                val fields: List<Triple<Int, String, (String) -> TagFields>> = listOf(
                    Triple(R.string.tag_title, edited.title) { v -> edited.copy(title = v) },
                    Triple(R.string.tag_artist, edited.artist) { v -> edited.copy(artist = v) },
                    Triple(R.string.tag_album, edited.album) { v -> edited.copy(album = v) },
                    Triple(R.string.tag_album_artist, edited.albumArtist) { v -> edited.copy(albumArtist = v) },
                    Triple(R.string.tag_track, edited.track) { v -> edited.copy(track = v) },
                    Triple(R.string.tag_disc, edited.disc) { v -> edited.copy(disc = v) },
                    Triple(R.string.tag_year, edited.year) { v -> edited.copy(year = v) },
                    Triple(R.string.tag_genre, edited.genre) { v -> edited.copy(genre = v) },
                    Triple(R.string.tag_composer, edited.composer) { v -> edited.copy(composer = v) },
                    Triple(R.string.tag_comment, edited.comment) { v -> edited.copy(comment = v) },
                )
                val numeric = setOf(R.string.tag_track, R.string.tag_disc)
                for ((label, value, update) in fields) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { edited = update(it) },
                        label = { Text(stringResource(label)) },
                        singleLine = label != R.string.tag_comment,
                        keyboardOptions = if (label in numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                Hint(stringResource(R.string.tags_hint))
                AlbumCoverSection(file)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
