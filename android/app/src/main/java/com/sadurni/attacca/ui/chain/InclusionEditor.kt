package com.sadurni.attacca.ui.chain

import com.sadurni.attacca.ui.theme.AttaccaType
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sadurni.attacca.Background
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.InclusionRules
import com.sadurni.attacca.library.InclusionRules.Kind
import com.sadurni.attacca.library.InclusionRules.Rule
import com.sadurni.attacca.library.InclusionStore
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.common.SearchField
import com.sadurni.attacca.ui.common.matchesQuery
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How the rules of the inclusion list are made up, for its summary. */
data class RuleCounts(val added: Int, val takenOut: Int, val byKind: Map<Kind, Int>) {
    val total get() = added + takenOut

    companion object {
        fun of(rules: List<Rule>) = RuleCounts(
            rules.count { it.include },
            rules.count { !it.include },
            rules.groupingBy { it.kind }.eachCount(),
        )
    }
}

/** "Rules: 12 (10 add, 2 take out)" and what kinds they are; the tracks of the resulting list. */
@Composable
fun inclusionSummary(overview: InclusionStore.Overview): List<String> = buildList {
    if (overview.hasRules) {
        val counts = RuleCounts.of(overview.rules)
        add(stringResource(R.string.inclusion_summary_rules, counts.total, counts.added, counts.takenOut))
        if (counts.total > 0) {
            add(
                stringResource(
                    R.string.inclusion_summary_kinds,
                    counts.byKind[Kind.TRACK] ?: 0,
                    counts.byKind[Kind.FOLDER] ?: 0,
                    counts.byKind[Kind.ALBUM] ?: 0,
                    counts.byKind[Kind.ARTIST] ?: 0,
                ),
            )
        }
    } else {
        add(stringResource(R.string.inclusion_summary_no_rules))
    }
    add(stringResource(R.string.inclusion_summary_tracks, overview.tracks.size))
}

private enum class Pending { DELETE_RULES, TAKE_OUT }

/**
 * Everything the inclusion list is made of, on one screen: its rules, to look through them,
 * delete them, switch them between add and take out or edit them all as text; and the list
 * computed from them, where tracks can be taken out as the menus' "Take out" does.
 */
@Composable
fun InclusionEditor(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val store = app.inclusionStore
    val scope = rememberCoroutineScope()
    val colors = LocalAttaccaColors.current
    val musicFolder = remember { app.settings.musicFolder?.trimEnd('/')?.plus("/") }

    var version by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<InclusionStore.Overview?>(null) }
    var tagsReady by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        val o = store.overview()
        overview = o
        // Titles and artists of the list's tracks, from the tag database only (no file reads).
        if (o != null && withContext(Background.dispatcher) { TrackSort.warmFromDb(o.tracks) }) tagsReady++
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedRules by remember { mutableStateOf(setOf<Rule>()) }
    var selectedTracks by remember { mutableStateOf(setOf<String>()) }
    var working by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun run(action: suspend () -> Int?) {
        working = true
        scope.launch {
            val count = runCatching { action() }.getOrNull()
            working = false
            selectedRules = emptySet()
            selectedTracks = emptySet()
            version++
            onChanged()
            Toast.makeText(
                context,
                if (count == null) context.getString(R.string.inclusion_failed) else context.resources.getQuantityString(R.plurals.inclusion_count, count, count),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun shortPath(path: String) = musicFolder?.let { if (path.startsWith(it)) path.substring(it.length) else null } ?: path

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
            ScreenHeader(stringResource(R.string.inclusion_editor_title), onBack = onDismiss) {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                }
                if (tab == 0) {
                    IconButton(
                        onClick = { editingText = InclusionRules.format(overview?.rules.orEmpty()) },
                        enabled = overview != null && !working,
                    ) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.inclusion_edit_text)) }
                }
            }
            val o = overview
            if (o == null) {
                if (app.settings.chain.value.inclusionList == null) Hint(stringResource(R.string.inclusion_none))
                return@Column
            }
            Text(
                o.list.absolutePath,
                fontSize = AttaccaType.caption,
                color = colors.textGrayed,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.inclusion_tab_rules, o.rules.size)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.inclusion_tab_list, o.tracks.size)) })
            }
            if (searching) SearchField(query, { query = it }, onClose = { searching = false; query = "" })
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))

            if (tab == 0) {
                val shown = remember(o, query) {
                    o.rules.filter { matchesQuery(query, it.kind.word, it.value) }
                }
                if (!o.hasRules) Hint(stringResource(R.string.inclusion_no_rules_hint))
                SelectionBar(
                    selected = selectedRules.size,
                    shown = shown.size,
                    onAll = { selectedRules = if (selectedRules.containsAll(shown)) selectedRules - shown.toSet() else selectedRules + shown },
                    allSelected = shown.isNotEmpty() && selectedRules.containsAll(shown),
                ) {
                    OutlinedButton(onClick = { pending = Pending.DELETE_RULES }, enabled = selectedRules.isNotEmpty() && !working) {
                        Text(stringResource(R.string.inclusion_delete_rules))
                    }
                    OutlinedButton(
                        onClick = {
                            val flip = selectedRules
                            run { store.saveRules(o.rules.map { if (it in flip) it.copy(include = !it.include) else it }) }
                        },
                        enabled = selectedRules.isNotEmpty() && !working,
                    ) { Text(stringResource(R.string.inclusion_flip_rules)) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown) { rule ->
                        val selected = rule in selectedRules
                        val toggle = { selectedRules = if (selected) selectedRules - rule else selectedRules + rule }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(end = 16.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = { toggle() })
                            Text(
                                if (rule.include) "+" else "−",
                                fontSize = AttaccaType.title,
                                fontWeight = FontWeight.Bold,
                                color = if (rule.include) LocalAccent.current else com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.danger,
                                modifier = Modifier.width(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                val value = when (rule.kind) {
                                    Kind.TRACK, Kind.FOLDER -> shortPath(rule.value)
                                    Kind.ALBUM -> rule.value.replace(InclusionRules.ALBUM_SEPARATOR, " — ")
                                    Kind.ARTIST -> rule.value
                                }
                                Text(value, fontSize = AttaccaType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(kindLabel(rule.kind), fontSize = AttaccaType.caption, color = colors.textGrayed)
                            }
                        }
                    }
                }
            } else {
                val shown = remember(o, query, tagsReady) {
                    o.tracks.filter { path ->
                        val tags = TrackSort.cachedTags(path)
                        matchesQuery(query, path, tags?.title.orEmpty(), tags?.artist.orEmpty(), tags?.album.orEmpty())
                    }
                }
                Hint(stringResource(R.string.inclusion_take_out_hint))
                SelectionBar(
                    selected = selectedTracks.size,
                    shown = shown.size,
                    onAll = { selectedTracks = if (selectedTracks.containsAll(shown)) selectedTracks - shown.toSet() else selectedTracks + shown },
                    allSelected = shown.isNotEmpty() && selectedTracks.containsAll(shown),
                ) {
                    OutlinedButton(onClick = { pending = Pending.TAKE_OUT }, enabled = selectedTracks.isNotEmpty() && !working) {
                        Text(stringResource(R.string.inclusion_take_out_selected))
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it }) { path ->
                        val selected = path in selectedTracks
                        val toggle = { selectedTracks = if (selected) selectedTracks - path else selectedTracks + path }
                        val tags = remember(path, tagsReady) { TrackSort.cachedTags(path) }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(end = 16.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = { toggle() })
                            Column(Modifier.weight(1f)) {
                                val file = File(path)
                                Text(
                                    tags?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                                    fontSize = AttaccaType.body,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(tags?.artist?.takeIf { it.isNotBlank() }, file.parent?.let(::shortPath)).joinToString(" · "),
                                    fontSize = AttaccaType.caption,
                                    color = colors.textGrayed,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingText?.let { text ->
        var value by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { editingText = null },
            title = { Text(stringResource(R.string.inclusion_edit_text)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = AttaccaType.caption),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingText = null
                    val rules = InclusionRules.parse(value)
                    run { store.saveRules(rules) }
                }) { Text(stringResource(R.string.inclusion_save)) }
            },
            dismissButton = { TextButton(onClick = { editingText = null }) { Text(stringResource(R.string.cancel)) } },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    pending?.let { what ->
        val o = overview
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    if (what == Pending.DELETE_RULES) stringResource(R.string.inclusion_delete_rules)
                    else stringResource(R.string.inclusion_take_out_selected),
                )
            },
            text = {
                Text(
                    if (what == Pending.DELETE_RULES) stringResource(R.string.inclusion_delete_rules_confirm, selectedRules.size)
                    else stringResource(R.string.inclusion_take_out_confirm, selectedTracks.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    if (o == null) return@TextButton
                    if (what == Pending.DELETE_RULES) {
                        val gone = selectedRules
                        run { store.saveRules(o.rules.filterNot { it in gone }) }
                    } else {
                        val targets = selectedTracks.map { Rule(true, Kind.TRACK, it) }
                        run { store.changeAll(targets, add = false) }
                    }
                }) { Text(stringResource(R.string.inclusion_apply)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "3 selected", a box to select everything shown, and the actions on the selection. */
@Composable
private fun SelectionBar(
    selected: Int,
    shown: Int,
    allSelected: Boolean,
    onAll: () -> Unit,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = allSelected, onCheckedChange = { onAll() }, enabled = shown > 0)
        Text(
            stringResource(R.string.inclusion_selected, selected),
            fontSize = AttaccaType.secondary,
            color = LocalAttaccaColors.current.textGrayed,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun kindLabel(kind: Kind): String = stringResource(
    when (kind) {
        Kind.TRACK -> R.string.inclusion_kind_track
        Kind.FOLDER -> R.string.inclusion_kind_folder
        Kind.ALBUM -> R.string.inclusion_kind_album
        Kind.ARTIST -> R.string.inclusion_kind_artist
    },
)
