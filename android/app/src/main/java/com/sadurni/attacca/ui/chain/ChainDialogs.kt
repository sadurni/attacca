package com.sadurni.attacca.ui.chain

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.layout.offset
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.ChainFilters
import com.sadurni.attacca.chain.ChainLists
import com.sadurni.attacca.chain.FamilyName
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.Trait
import com.sadurni.attacca.chain.VoiceFilter
import com.sadurni.attacca.library.M3uParser
import com.sadurni.attacca.library.PlaylistEntry
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.SegmentedChoice
import com.sadurni.attacca.ui.settings.SwitchRow
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import java.io.File

@Composable
fun moodLabel(mood: Mood): String = stringResource(moodRes(mood))

private fun traitRes(trait: Trait, high: Boolean): Int = when (trait) {
    Trait.TEMPO -> if (high) R.string.trait_fast else R.string.trait_slow
    Trait.BRIGHTNESS -> if (high) R.string.trait_bright else R.string.trait_dark
    Trait.PERCUSSION -> if (high) R.string.trait_percussive else R.string.trait_smooth
    Trait.BASS -> if (high) R.string.trait_heavy_bass else R.string.trait_light_bass
    Trait.DISTORTION -> if (high) R.string.trait_distorted else R.string.trait_clean
    Trait.DYNAMICS -> if (high) R.string.trait_dynamic else R.string.trait_compressed
    Trait.VOICE -> if (high) R.string.trait_vocal else R.string.trait_instrumental
    Trait.LOUDNESS -> if (high) R.string.trait_loud else R.string.trait_quiet
}

/** "Fast, bright, percussive"; families without a distinctive trait are "middle ground". */
fun familyLabel(context: android.content.Context, name: FamilyName): String =
    if (name.traits.isEmpty()) {
        context.getString(R.string.family_middle)
    } else {
        name.traits.joinToString(", ") { (t, high) -> context.getString(traitRes(t, high)) }
            .replaceFirstChar { it.uppercase() }
    }

/** Default track length range of generated lists, minutes. */
private const val DEFAULT_MINUTES_MIN = 1
private const val DEFAULT_MINUTES_MAX = 20
private const val DEFAULT_NOT_HEARD_DAYS = 90

/** Which tracks a generated list may take by how they have been listened to. */
private enum class HeardFilter { ANY, NOT_LATELY, NEVER }

/** Filter values being edited; empty text means "any". */
private class FilterForm {
    var artist by mutableStateOf("")
    var genre by mutableStateOf("")
    var yearFrom by mutableStateOf("")
    var yearTo by mutableStateOf("")
    var bpmMin by mutableStateOf("")
    var bpmMax by mutableStateOf("")
    var minutesMin by mutableStateOf(DEFAULT_MINUTES_MIN.toString())
    var minutesMax by mutableStateOf(DEFAULT_MINUTES_MAX.toString())
    var energy by mutableStateOf(0f..1f)
    var positivity by mutableStateOf(0f..1f)
    var voice by mutableStateOf(VoiceFilter.ANY)
    var onlyIncluded by mutableStateOf(false)
    var inclusion: com.sadurni.attacca.library.Inclusion? = null
    var heard by mutableStateOf(HeardFilter.ANY)
    var heardDays by mutableStateOf(DEFAULT_NOT_HEARD_DAYS.toString())
    var listening: com.sadurni.attacca.library.ListeningStats? = null

    /** The tracks the listening choice leaves out. */
    private fun excluded(): Set<String>? {
        val stats = listening ?: return null
        return when (heard) {
            HeardFilter.ANY -> null
            HeardFilter.NEVER -> stats.everPlayed()
            HeardFilter.NOT_LATELY -> {
                val days = heardDays.toIntOrNull()?.takeIf { it > 0 } ?: return null
                stats.playedSince(System.currentTimeMillis() - days * 86_400_000L)
            }
        }
    }

    fun toFilters(moods: Set<Mood>? = null, families: Set<Int>? = null) = ChainFilters(
        moods = moods,
        families = families,
        artist = artist.ifBlank { null },
        genre = genre.ifBlank { null },
        yearFrom = yearFrom.toIntOrNull(),
        yearTo = yearTo.toIntOrNull(),
        bpmMin = bpmMin.toIntOrNull(),
        bpmMax = bpmMax.toIntOrNull(),
        durationMinS = minutesMin.toIntOrNull()?.takeIf { it > 0 }?.times(60),
        durationMaxS = minutesMax.toIntOrNull()?.times(60),
        energyMin = energy.start.takeIf { it > 0f },
        energyMax = energy.endInclusive.takeIf { it < 1f },
        positivityMin = positivity.start.takeIf { it > 0f },
        positivityMax = positivity.endInclusive.takeIf { it < 1f },
        voice = voice,
        inclusion = inclusion.takeIf { onlyIncluded },
        excluded = excluded(),
    )

    /** Takes the fitted values (see [com.sadurni.attacca.chain.ListGenerator.fitToSeed]). */
    fun fitted(f: ChainFilters) {
        yearFrom = f.yearFrom?.toString().orEmpty()
        yearTo = f.yearTo?.toString().orEmpty()
        bpmMin = f.bpmMin?.toString().orEmpty()
        bpmMax = f.bpmMax?.toString().orEmpty()
        energy = (f.energyMin ?: 0f)..(f.energyMax ?: 1f)
        positivity = (f.positivityMin ?: 0f)..(f.positivityMax ?: 1f)
        voice = f.voice
    }
}

@Composable
private fun FilterFields(form: FilterForm) {
    val chain by LocalContext.current.app.settings.chain.collectAsStateWithLifecycle()
    form.inclusion = remember(chain.inclusionList, chain.inclusionMode) { chain.inclusion() }
    SwitchRow(stringResource(R.string.only_included), form.onlyIncluded && form.inclusion != null, { form.onlyIncluded = it }, enabled = form.inclusion != null)
    if (form.inclusion == null) {
        Hint(stringResource(R.string.only_included_none))
    } else {
        Text(
            stringResource(if (chain.inclusionMode == com.sadurni.attacca.library.InclusionMode.ALLOW) R.string.only_included_allow else R.string.only_included_block),
            fontSize = AttaccaType.caption,
            color = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.textGrayed,
            modifier = Modifier.padding(start = 16.dp).offset(y = (-8).dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallField(form.artist, { form.artist = it }, stringResource(R.string.filter_artist), Modifier.weight(1f))
        SmallField(form.genre, { form.genre = it }, stringResource(R.string.filter_genre), Modifier.weight(1f))
    }
    Row {
        SmallField(form.yearFrom, { form.yearFrom = it.filter(Char::isDigit) }, stringResource(R.string.filter_year_from), Modifier.weight(1f), numeric = true)
        SmallField(form.yearTo, { form.yearTo = it.filter(Char::isDigit) }, stringResource(R.string.filter_year_to), Modifier.weight(1f), numeric = true)
    }
    Row {
        SmallField(form.bpmMin, { form.bpmMin = it.filter(Char::isDigit) }, stringResource(R.string.filter_bpm_min), Modifier.weight(1f), numeric = true)
        SmallField(form.bpmMax, { form.bpmMax = it.filter(Char::isDigit) }, stringResource(R.string.filter_bpm_max), Modifier.weight(1f), numeric = true)
    }
    Row {
        SmallField(form.minutesMin, { form.minutesMin = it.filter(Char::isDigit) }, stringResource(R.string.filter_minutes_min), Modifier.weight(1f), numeric = true)
        SmallField(form.minutesMax, { form.minutesMax = it.filter(Char::isDigit) }, stringResource(R.string.filter_minutes_max), Modifier.weight(1f), numeric = true)
    }
    RangeRow(stringResource(R.string.filter_energy), form.energy) { form.energy = it }
    RangeRow(stringResource(R.string.filter_positivity), form.positivity) { form.positivity = it }
    SegmentedChoice(
        listOf(
            VoiceFilter.ANY to stringResource(R.string.filter_voice_any),
            VoiceFilter.VOICE to stringResource(R.string.filter_voice),
            VoiceFilter.INSTRUMENTAL to stringResource(R.string.filter_instrumental),
        ),
        form.voice,
    ) { form.voice = it }
    form.listening = LocalContext.current.app.listening
    Text(stringResource(R.string.filter_heard), fontSize = AttaccaType.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    SegmentedChoice(
        listOf(
            HeardFilter.ANY to stringResource(R.string.filter_heard_any),
            HeardFilter.NOT_LATELY to stringResource(R.string.filter_heard_not_lately),
            HeardFilter.NEVER to stringResource(R.string.filter_heard_never),
        ),
        form.heard,
    ) { form.heard = it }
    if (form.heard == HeardFilter.NOT_LATELY) {
        SmallField(form.heardDays, { form.heardDays = it.filter(Char::isDigit) }, stringResource(R.string.filter_heard_days), Modifier.fillMaxWidth(), numeric = true)
    }
    Hint(stringResource(R.string.filter_hint))
}

@Composable
private fun SmallField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, numeric: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = AttaccaType.caption) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun RangeRow(label: String, value: ClosedFloatingPointRange<Float>, onChange: (ClosedFloatingPointRange<Float>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row {
            Text(label, fontSize = AttaccaType.secondary, modifier = Modifier.weight(1f))
            Text("${(value.start * 100).toInt()}–${(value.endInclusive * 100).toInt()} %", fontSize = AttaccaType.secondary)
        }
        RangeSlider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}

/** Analysed tracks out of all in the music folder, with a way to analyse the rest. */
@Composable
private fun CoverageHint(collection: ChainLists.Collection) {
    val context = LocalContext.current
    val analyzer = context.app.chainAnalyzer
    val progress by analyzer.libraryProgress.collectAsStateWithLifecycle()
    if (collection.analysed >= collection.total) return
    Hint(stringResource(R.string.chain_coverage, collection.analysed, collection.total))
    val folder = context.app.settings.musicFolder
    if (folder != null) {
        val p = progress
        if (p != null) {
            Hint(com.sadurni.attacca.ui.common.analysisProgressText(R.string.chain_library_progress, p.done, p.total, p.remainingMs))
        } else {
            TextButton(onClick = { analyzer.analyzeLibrary(File(folder)) }) { Text(stringResource(R.string.chain_analyze_rest)) }
        }
    }
}

/** Loads the classified collection; shows a spinner meanwhile. */
@Composable
private fun rememberCollection(extra: String?): Pair<Boolean, ChainLists.Collection?> {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var collection by remember { mutableStateOf<ChainLists.Collection?>(null) }
    LaunchedEffect(extra) {
        collection = runCatching { context.app.chainLists.collection(extra) }.getOrNull()
        loading = false
    }
    return loading to collection
}

/**
 * "Create a list from this track": the closest tracks of the same mood and family (by default)
 * passing the filters, chained from it; then played or saved.
 */
@Composable
fun SeedListDialog(viewModel: PlayerViewModel, seeds: List<String>, groupName: String?, onDismiss: () -> Unit, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val lists = context.app.chainLists
    val working by lists.working.collectAsStateWithLifecycle()
    val seed = seeds.first()
    val group = groupName != null
    val (loading, collection) = rememberCollection(if (group) null else seed)
    val seedIndices = remember(collection) { collection?.generator?.indicesOf(seeds).orEmpty() }
    val onlyByDefault = LocalContext.current.app.chainLists.onlyIncludedByDefault()
    val form = remember { FilterForm().apply { onlyIncluded = onlyByDefault } }
    var sameMood by remember { mutableStateOf(true) }
    var sameFamily by remember { mutableStateOf(true) }
    var count by remember { mutableStateOf(lists.listCount.toString()) }
    var fitted by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<List<String>?>(null) }
    val title = groupName ?: remember(seed) { TrackSort.cachedTags(seed)?.title ?: File(seed).nameWithoutExtension }
    val listName = stringResource(R.string.seed_list_name, title)

    val ids = result
    if (ids != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(listName) },
            text = { Text(if (group) stringResource(R.string.seed_group_ready, ids.size) else stringResource(R.string.seed_list_ready, ids.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.playIds(listName, ids)
                    onDismiss()
                    onPlayed()
                }) { Text(stringResource(R.string.seed_play)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val file = UserLists.fileFor(listName)
                    runCatching {
                        file.parentFile?.mkdirs()
                        M3uParser.write(file, ids.map { PlaylistEntry(it) })
                    }
                    Toast.makeText(context, context.getString(R.string.list_saved).format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text(stringResource(R.string.seed_save)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (group) R.string.seed_group_title else R.string.seed_list_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(title, fontSize = AttaccaType.body)
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                        Text("  " + stringResource(R.string.chain_preparing), fontSize = AttaccaType.secondary)
                    }
                    group && (collection == null || seedIndices.isEmpty()) ->
                        Hint(stringResource(R.string.seed_group_not_analysable))
                    group -> {
                        Hint(stringResource(R.string.seed_group_hint, seedIndices.size))
                        CoverageHint(collection!!)
                        SwitchRow(stringResource(R.string.seed_same_mood), sameMood, { sameMood = it })
                        SwitchRow(stringResource(R.string.seed_same_family), sameFamily, { sameFamily = it })
                        SmallField(count, { count = it.filter(Char::isDigit) }, stringResource(R.string.filter_count), Modifier.fillMaxWidth(), numeric = true)
                        CountChips(count, { count = it })
                        FilterFields(form)
                    }
                    collection == null || collection.generator.indexOf(seed) < 0 ->
                        Hint(stringResource(R.string.seed_not_analysable))
                    else -> {
                        val g = collection.generator
                        val index = g.indexOf(seed)
                        val family = familyLabel(context, g.classification.familyNames[g.familyOf(index)])
                        Text(
                            stringResource(R.string.seed_profile, moodText(context, g, index), family),
                            fontSize = AttaccaType.secondary,
                            color = LocalAttaccaColors.current.textGrayed,
                        )
                        CoverageHint(collection)
                        SwitchRow(stringResource(R.string.seed_same_mood), sameMood, { sameMood = it })
                        SwitchRow(stringResource(R.string.seed_same_family), sameFamily, { sameFamily = it })
                        SmallField(count, { count = it.filter(Char::isDigit); fitted = null }, stringResource(R.string.filter_count), Modifier.fillMaxWidth(), numeric = true)
                        CountChips(count, { count = it; fitted = null })
                        TextButton(onClick = {
                            val n = (count.toIntOrNull() ?: 150).coerceAtLeast(2)
                            val fit = g.fitToSeed(index, form.toFilters(), sameMood, sameFamily, n)
                            form.fitted(fit.filters)
                            fitted = fit.available
                        }) { Text(stringResource(R.string.seed_fit)) }
                        val available = fitted
                        Hint(
                            when {
                                available == null -> stringResource(R.string.seed_fit_hint)
                                available >= (count.toIntOrNull() ?: 150) * 2 -> stringResource(R.string.seed_fit_result, available)
                                else -> stringResource(R.string.seed_fit_short, available)
                            },
                        )
                        FilterFields(form)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !working && collection != null && (if (group) seedIndices.isNotEmpty() else collection.generator.indexOf(seed) >= 0),
                onClick = {
                    val n = listCountOf(count, lists.listCount)
                    val done: (List<String>?) -> Unit = { found ->
                        if (found == null || found.size < 2) {
                            Toast.makeText(context, R.string.seed_list_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            result = found
                        }
                    }
                    if (group) lists.fromSeeds(seeds, form.toFilters(), sameMood, sameFamily, n, done)
                    else lists.fromSeed(seed, form.toFilters(), sameMood, sameFamily, n, done)
                },
            ) { Text(stringResource(if (working) R.string.chain_building else R.string.seed_list_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Generates one chained list per mood and sound family into the Generated folder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenerateListsDialog(onDismiss: () -> Unit, onGenerated: () -> Unit) {
    val context = LocalContext.current
    val lists = context.app.chainLists
    val working by lists.working.collectAsStateWithLifecycle()
    val (loading, collection) = rememberCollection(null)
    val onlyByDefault = LocalContext.current.app.chainLists.onlyIncludedByDefault()
    val form = remember { FilterForm().apply { onlyIncluded = onlyByDefault } }
    var moods by remember { mutableStateOf(Mood.entries.toSet()) }
    var families by remember { mutableStateOf<Set<Int>?>(null) }
    var count by remember { mutableStateOf(lists.listCount.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generate_title)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                        Text("  " + stringResource(R.string.chain_preparing), fontSize = AttaccaType.secondary)
                    }
                    collection == null -> Hint(stringResource(R.string.chain_needs_music_folder))
                    collection.analysed == 0 -> {
                        Hint(stringResource(R.string.generate_nothing_analysed))
                        CoverageHint(collection)
                    }
                    else -> {
                        val names = collection.generator.classification.familyNames
                        val allFamilies = names.indices.toSet()
                        CoverageHint(collection)
                        Hint(stringResource(R.string.generate_hint))
                        Text(stringResource(R.string.filter_moods), fontSize = AttaccaType.secondary)
                        FlowRow {
                            for (m in Mood.entries) {
                                FilterChip(
                                    selected = m in moods,
                                    onClick = { moods = if (m in moods) moods - m else moods + m },
                                    label = { Text(moodLabel(m)) },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }
                        Text(stringResource(R.string.filter_families), fontSize = AttaccaType.secondary)
                        FlowRow {
                            val chosen = families ?: allFamilies
                            for (i in names.indices) {
                                FilterChip(
                                    selected = i in chosen,
                                    onClick = { families = if (i in chosen) chosen - i else chosen + i },
                                    label = { Text(familyLabel(context, names[i]), fontSize = AttaccaType.caption) },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }
                        SmallField(count, { count = it.filter(Char::isDigit) }, stringResource(R.string.filter_count_all), Modifier.fillMaxWidth(), numeric = true)
                        CountChips(count, { count = it })
                        FilterFields(form)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !working && collection != null && collection.analysed > 0 && moods.isNotEmpty(),
                onClick = {
                    lists.generateGeneral(
                        filters = form.toFilters(moods = moods, families = families),
                        count = count.toIntOrNull()?.takeIf { it > 0 },
                        name = { list, c ->
                            context.getString(
                                R.string.generated_list_name,
                                context.getString(moodRes(list.mood)),
                                familyLabel(context, c.generator.classification.familyNames[list.family]),
                            )
                        },
                    ) { written ->
                        val message = if (written == null) context.getString(R.string.generate_failed)
                        else context.resources.getQuantityString(R.plurals.generated_count, written, written)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onGenerated()
                    }
                    onDismiss()
                },
            ) { Text(stringResource(if (working) R.string.chain_building else R.string.generate_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun moodRes(mood: Mood): Int = when (mood) {
    Mood.ENERGETIC -> R.string.mood_energetic
    Mood.CHEERFUL -> R.string.mood_cheerful
    Mood.CALM -> R.string.mood_calm
    Mood.MELANCHOLIC -> R.string.mood_melancholic
    Mood.TENSE -> R.string.mood_tense
}
