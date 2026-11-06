package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.BuildConfig
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.ThemeSpec
import com.sadurni.attacca.settings.ThemeStore
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.theme.AttaccaTheme
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import com.sadurni.attacca.ui.theme.ThemeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The themes that come with Attacca, as they come: each one with a picture of Now playing in it.
 * "Use" copies it among the user's themes and puts it on; the copy can then be edited or deleted
 * freely, while this gallery keeps the originals (and new versions of them with each update).
 */
@Composable
fun ThemeGalleryScreen(viewModel: PlayerViewModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val settings = context.app.settings
    val ui by settings.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val themes by produceState<List<ThemeSpec>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { ThemeStore.gallery(context, BuildConfig.BUILD_TIME.toString()) }.getOrDefault(emptyList()) }
            .sortedBy { themeName(context, it).lowercase() }
    }
    val systemDark = isSystemInDarkTheme()
    val added = stringResource(R.string.theme_gallery_added)
    fun copy(spec: ThemeSpec, use: Boolean, edit: Boolean = false) {
        scope.launch {
            val copy = withContext(Dispatchers.IO) { ThemeStore.adopt(spec, themeName(context, spec)) }
            ThemeRuntime.version++
            if (use) settings.updateUi { it.copy(themeId = copy.id) }
            Toast.makeText(context, added.format(copy.name), Toast.LENGTH_SHORT).show()
            if (edit) onEdit(copy.id)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.theme_gallery_title), onBack, root = false)
        val list = themes
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { Hint(stringResource(R.string.theme_gallery_hint)) }
            items(list, key = { it.id }) { original ->
                val spec = original.forSystem(systemDark)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Now playing in the theme, as it looks on this phone (light or dark like the phone).
                    AttaccaTheme(spec = spec, artworkAccent = null) {
                        com.sadurni.attacca.ui.player.PlayerLayoutPreview(viewModel, ui, Modifier.width(132.dp).height(250.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(themeName(original), fontSize = AttaccaType.heading, fontWeight = FontWeight.SemiBold)
                        ThemeSwatches(original)
                        Text(
                            stringResource(if (original.alternate != null) R.string.theme_gallery_both else if (original.dark) R.string.theme_gallery_dark else R.string.theme_gallery_light),
                            fontSize = AttaccaType.caption,
                            color = LocalAttaccaColors.current.textGrayed,
                        )
                        Button(onClick = { copy(original, use = true) }) { Text(stringResource(R.string.theme_gallery_use)) }
                        OutlinedButton(onClick = { copy(original, use = false, edit = true) }) { Text(stringResource(R.string.theme_gallery_copy_edit)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            ThemeMoreMenu(original, themeName(original), canDelete = false)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
