package com.sadurni.attacca.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoveDown
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import kotlinx.coroutines.launch
import com.sadurni.attacca.settings.BlockType
import com.sadurni.attacca.settings.BoxLine
import com.sadurni.attacca.settings.LineKind
import com.sadurni.attacca.settings.InfoTemplate
import com.sadurni.attacca.settings.PlayerAction
import com.sadurni.attacca.settings.PlayerBlock
import com.sadurni.attacca.settings.PlayerLayout
import com.sadurni.attacca.settings.TextSize
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.player.actionIcon
import com.sadurni.attacca.ui.player.actionName
import com.sadurni.attacca.ui.player.rememberTrackValues
import com.sadurni.attacca.ui.theme.AttaccaShapes
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

private const val NEW_LINE_TEMPLATE = PlayerBlock.NEW_LINE_TEMPLATE

/** The part copied or cut, kept while the app runs: it pastes into another box, layout or theme. */
object LayoutClipboard {
    var clip by mutableStateOf<com.sadurni.attacca.settings.LayoutParts.Clip?>(null)
}

/** The theme being edited, for the clipboard (where its pictures are). */
private val LocalEditedTheme = androidx.compose.runtime.staticCompositionLocalOf { "" }

private fun themeFile(themeId: String, name: String): java.io.File = java.io.File(com.sadurni.attacca.settings.ThemeStore.imagesDir(themeId), name)

private fun needsClassificationOf(template: String) = com.sadurni.attacca.ui.player.needsClassification(template)

/**
 * The parts of Now playing, top to bottom: move, edit, remove and add them. Changes go through
 * [onChange] (the theme editor keeps them for undo).
 */
@Composable
fun LayoutSection(
    spec: com.sadurni.attacca.settings.ThemeSpec,
    viewModel: PlayerViewModel,
    landscape: Boolean,
    onLandscape: (Boolean) -> Unit,
    onChange: (com.sadurni.attacca.settings.ThemeSpec) -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalEditedTheme provides spec.id) { LayoutSectionContent(spec, viewModel, landscape, onLandscape, onChange) }
}

@Composable
private fun LayoutSectionContent(
    spec: com.sadurni.attacca.settings.ThemeSpec,
    viewModel: PlayerViewModel,
    landscape: Boolean,
    onLandscape: (Boolean) -> Unit,
    onChange: (com.sadurni.attacca.settings.ThemeSpec) -> Unit,
) {
    SectionTitle(stringResource(R.string.section_layout))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val tabs = listOf(false to R.string.layout_portrait, true to R.string.layout_landscape)
        tabs.forEachIndexed { i, (wide, label) ->
            SegmentedButton(
                selected = landscape == wide,
                onClick = { onLandscape(wide) },
                shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                icon = {},
            ) { Text(stringResource(label), fontSize = AttaccaType.secondary) }
        }
    }
    if (!landscape) {
        Hint(stringResource(R.string.layout_hint))
        LayoutEditor(spec.layout, viewModel, reset = PlayerLayout.DEFAULT) { onChange(spec.copy(layout = it)) }
    } else {
        val own = spec.landscape
        RadioRow(stringResource(R.string.landscape_auto), own == null) { onChange(spec.copy(landscape = null)) }
        RadioRow(stringResource(R.string.landscape_own), own != null) { if (own == null) onChange(spec.copy(landscape = spec.layout.toLandscape())) }
        Hint(stringResource(R.string.landscape_hint))
        if (own != null) {
            LayoutEditor(own, viewModel, reset = spec.layout.toLandscape()) { onChange(spec.copy(landscape = it)) }
        }
    }
}

/** One layout's parts, the play-button warning and the way back to [reset]. */
@Composable
private fun LayoutEditor(layout: PlayerLayout, viewModel: PlayerViewModel, reset: PlayerLayout, onChange: (PlayerLayout) -> Unit) {
    val present = layout.allBlocks.map { it.type }.toSet()
    BlockListEditor(layout.blocks, present, viewModel, depth = 0, sideBySide = false, padding = 16.dp) { onChange(PlayerLayout(it)) }
    if (!layout.hasPlayButton) Hint(stringResource(R.string.layout_no_play))
    TextButton(
        onClick = { onChange(reset) },
        enabled = layout != reset,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(stringResource(R.string.layout_reset)) }
}

/**
 * A list of parts (of the screen or of a box): each one moves, opens for editing and goes away;
 * new ones are added at the end. [present] are the parts used anywhere, so a part that appears once
 * is not offered twice. In a box with parts side by side each part has its width. A part goes into
 * a box beside it, and out of its box with [onTakeOut] (the index of the part, in a box only).
 */
@Composable
private fun BlockListEditor(
    blocks: List<PlayerBlock>,
    present: Set<BlockType>,
    viewModel: PlayerViewModel,
    depth: Int,
    sideBySide: Boolean,
    padding: androidx.compose.ui.unit.Dp,
    onTakeOut: ((Int) -> Unit)? = null,
    onChange: (List<PlayerBlock>) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Int?>(null) }
    val themeId = LocalEditedTheme.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun clip(block: PlayerBlock) = com.sadurni.attacca.settings.LayoutParts.Clip(block, themeId) { name -> themeFile(themeId, name).takeIf { it.isFile }?.readBytes() }
    val colors = LocalAttaccaColors.current
    blocks.forEachIndexed { i, block ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = block.type in EDITABLE) { editing = i }
                .padding(start = padding, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(partIcon(block.type), contentDescription = null, tint = colors.textGrayed, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(partName(block.type), fontSize = AttaccaType.body)
                PartSummary(block)
            }
            IconButton(onClick = { onChange(blocks.move(i, -1)) }, enabled = i > 0) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.layout_up))
            }
            IconButton(onClick = { onChange(blocks.move(i, 1)) }, enabled = i < blocks.lastIndex) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.layout_down))
            }
            // Into a box beside it (one it fits in), or out of its own.
            val boxes = blocks.indices.filter { j -> j != i && blocks[j].type == BlockType.BOX && depth + 1 + boxLevels(block) <= PlayerBlock.MAX_DEPTH - 1 }
            Box {
                IconButton(onClick = { moving = i }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.layout_more))
                }
                DropdownMenu(expanded = moving == i, onDismissRequest = { moving = null }) {
                    for (j in boxes) {
                        val target = blocks[j]
                        val inside = target.children.map { partName(it.type) }.joinToString(" · ").ifEmpty { "—" }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layout_into_box, inside), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(partIcon(BlockType.BOX), contentDescription = null) },
                            onClick = {
                                moving = null
                                val into = target.copy(children = target.children + block.copy(width = 0))
                                onChange(blocks.mapIndexedNotNull { k, b -> when (k) { i -> null; j -> into; else -> b } })
                            },
                        )
                    }
                    if (onTakeOut != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.layout_take_out)) },
                            leadingIcon = { Icon(Icons.Rounded.UnfoldMore, contentDescription = null) },
                            onClick = { moving = null; onTakeOut(i) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_copy)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = { moving = null; LayoutClipboard.clip = clip(block) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_cut)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                        enabled = blocks.size > 1,
                        onClick = {
                            moving = null
                            LayoutClipboard.clip = clip(block)
                            onChange(blocks.filterIndexed { j, _ -> j != i })
                        },
                    )
                }
            }
            IconButton(onClick = { onChange(blocks.filterIndexed { j, _ -> j != i }) }, enabled = blocks.size > 1) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.layout_remove))
            }
        }
        if (sideBySide) {
            // Its share of the width: 0 shares what the others leave.
            val auto = stringResource(R.string.width_auto)
            ValueSlider(
                label = stringResource(R.string.width_label),
                value = block.width.toFloat(),
                range = 0f..100f,
                step = 5f,
                format = { if (it == 0f) auto else "${it.toInt()} %" },
                onChange = { v -> onChange(blocks.mapIndexed { j, b -> if (j == i) b.copy(width = v.toInt()) else b }) },
            )
        }
    }
    // The part copied or cut goes at the end of this list (with its pictures, from another theme).
    val pasting = LayoutClipboard.clip
    if (pasting != null) {
        TextButton(
            onClick = {
                scope.launch {
                    val block = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.sadurni.attacca.settings.LayoutParts.paste(pasting, themeId, com.sadurni.attacca.settings.ThemeStore.imagesDir(themeId))
                    }
                    onChange(blocks + block.copy(width = 0))
                }
            },
            enabled = com.sadurni.attacca.settings.LayoutParts.fits(pasting.block, depth, present),
            modifier = Modifier.padding(start = (padding - 8.dp).coerceAtLeast(0.dp)),
        ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.layout_paste, partName(pasting.block.type)), modifier = Modifier.padding(start = 4.dp))
        }
    }
    Box(Modifier.padding(start = (padding - 8.dp).coerceAtLeast(0.dp))) {
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.layout_add), modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
            val offered = listOf(BlockType.TEXT, BlockType.BUTTONS, BlockType.SPACE) +
                (if (depth < PlayerBlock.MAX_DEPTH - 1) listOf(BlockType.BOX) else emptyList()) +
                PlayerLayout.SINGLE.filter { it !in present }
            for (type in offered) {
                DropdownMenuItem(
                    text = { Text(partName(type)) },
                    leadingIcon = { Icon(partIcon(type), contentDescription = null) },
                    onClick = {
                        adding = false
                        val block = when (type) {
                            BlockType.TEXT -> PlayerBlock(type, template = NEW_LINE_TEMPLATE)
                            BlockType.BUTTONS -> PlayerBlock(type, actions = listOf(PlayerAction.PLAY_PAUSE))
                            else -> PlayerBlock(type)
                        }
                        onChange(blocks + block)
                        // What has content to choose opens for editing at once.
                        if (type in EDITABLE) editing = blocks.size
                    },
                )
            }
        }
    }

    editing?.let { i ->
        val block = blocks.getOrNull(i)
        val replace = { new: PlayerBlock ->
            editing = null
            onChange(blocks.mapIndexed { j, b -> if (j == i) new else b })
        }
        val dismiss = { editing = null }
        when (block?.type) {
            BlockType.BUTTONS -> ButtonsDialog(block, onDone = replace, onDismiss = dismiss)
            BlockType.TEXT -> TemplateDialog(
                block.template, block.size, block.boxed, viewModel,
                onDone = { template, size, boxed -> replace(block.copy(template = template, size = size, boxed = boxed ?: false)) },
                onDismiss = dismiss,
            )
            BlockType.TITLE, BlockType.LIST -> BoxLinesDialog(block, viewModel, onDone = replace, onDismiss = dismiss)
            BlockType.BOX -> BoxDialog(
                block, present, viewModel, depth + 1, onDone = replace, onDismiss = dismiss,
                // The part taken out goes right after its box.
                onTakeOut = { box, part ->
                    editing = null
                    onChange(blocks.flatMapIndexed { j, b -> if (j == i) listOf(box, part.copy(width = 0)) else listOf(b) })
                },
            )
            else -> editing = null
        }
    }
}

/** What a part holds, in small under its name. */
@Composable
private fun PartSummary(block: PlayerBlock) {
    val colors = LocalAttaccaColors.current
    @Composable
    fun line(text: String) = Text(text, fontSize = AttaccaType.caption, color = colors.textGrayed, maxLines = 1, overflow = TextOverflow.Ellipsis)
    when (block.type) {
        BlockType.BUTTONS -> Row(Modifier.padding(top = 2.dp)) {
            for (action in block.actions) {
                Icon(actionIcon(action), contentDescription = actionName(action), tint = colors.textGrayed, modifier = Modifier.padding(end = 4.dp).size(18.dp))
            }
        }
        BlockType.TITLE, BlockType.LIST -> line(block.lines.map { lineName(it) }.joinToString(" · "))
        BlockType.TEXT -> line(block.template)
        BlockType.BOX -> line(
            (if (block.horizontal) stringResource(R.string.box_side_by_side) + ": " else "") +
                block.children.map { partName(it.type) }.joinToString(" · "),
        )
        else -> Unit
    }
}

/** A box: frame or not, parts one under the other or side by side, and the parts inside. */
@Composable
private fun BoxDialog(
    block: PlayerBlock,
    present: Set<BlockType>,
    viewModel: PlayerViewModel,
    depth: Int,
    onDone: (PlayerBlock) -> Unit,
    onDismiss: () -> Unit,
    onTakeOut: (box: PlayerBlock, part: PlayerBlock) -> Unit,
) {
    var box by remember { mutableStateOf(block) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(partName(BlockType.BOX)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(stringResource(R.string.box_framed), box.framed, { box = box.copy(framed = it) })
                SwitchRow(stringResource(R.string.box_side_by_side), box.horizontal, { box = box.copy(horizontal = it) })
                ImageRow(stringResource(R.string.box_image), box.image, com.sadurni.attacca.ui.theme.ImageUse.BACKGROUND) {
                    box = box.copy(image = it)
                }
                CoverRow(box.cover) { box = box.copy(cover = it) }
                val inside = present + box.children.flatMap { it.flatten() }.map { it.type }
                BlockListEditor(
                    box.children, inside, viewModel, depth, sideBySide = box.horizontal, padding = 0.dp,
                    onTakeOut = { k -> onTakeOut(box.copy(children = box.children.filterIndexed { j, _ -> j != k }), box.children[k]) },
                ) {
                    box = box.copy(children = it)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(box) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun boxLevels(block: PlayerBlock) = com.sadurni.attacca.settings.LayoutParts.boxLevels(block)

private val EDITABLE = setOf(BlockType.BUTTONS, BlockType.TEXT, BlockType.TITLE, BlockType.LIST, BlockType.BOX)

/**
 * The lines of the title or list box, in order: move, remove and add them (the usual ones, or
 * lines from a template; tapping one of these edits it).
 */
@Composable
private fun BoxLinesDialog(block: PlayerBlock, viewModel: PlayerViewModel, onDone: (PlayerBlock) -> Unit, onDismiss: () -> Unit) {
    var lines by remember { mutableStateOf(block.lines) }
    var framed by remember { mutableStateOf(block.framed) }
    var image by remember { mutableStateOf(block.image) }
    var cover by remember { mutableStateOf(block.cover) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(partName(block.type)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(stringResource(R.string.box_framed), framed, { framed = it })
                ImageRow(stringResource(R.string.box_image), image, com.sadurni.attacca.ui.theme.ImageUse.BACKGROUND) { image = it }
                CoverRow(cover) { cover = it }
                lines.forEachIndexed { i, line ->
                    Row(
                        Modifier.clickable { editing = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(lineName(line), fontSize = AttaccaType.body)
                            if (line.kind == LineKind.TEMPLATE || line.size != BoxLine.defaultSize(line.kind)) {
                                Text(
                                    if (line.kind == LineKind.TEMPLATE) line.template else sizeName(line.size),
                                    fontSize = AttaccaType.caption,
                                    color = LocalAttaccaColors.current.textGrayed,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { lines = lines.move(i, -1) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.layout_up))
                        }
                        IconButton(onClick = { lines = lines.move(i, 1) }, enabled = i < lines.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.layout_down))
                        }
                        IconButton(onClick = { lines = lines.filterIndexed { j, _ -> j != i } }, enabled = lines.size > 1) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.layout_remove))
                        }
                    }
                }
                if (lines.size < MAX_LINES) {
                    Box {
                        TextButton(onClick = { adding = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.box_add_line), modifier = Modifier.padding(start = 4.dp))
                        }
                        DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                            val present = lines.map { it.kind }.toSet()
                            val offered = listOf(LineKind.TEMPLATE) + LineKind.entries.filter { it != LineKind.TEMPLATE && it !in present }
                            for (kind in offered) {
                                DropdownMenuItem(
                                    text = { Text(lineName(BoxLine(kind))) },
                                    onClick = {
                                        adding = false
                                        if (kind == LineKind.TEMPLATE) {
                                            lines = lines + BoxLine(kind, NEW_LINE_TEMPLATE, TextSize.SMALL)
                                            editing = lines.lastIndex
                                        } else {
                                            lines = lines + BoxLine(kind)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(block.copy(lines = lines, framed = framed, image = image, cover = cover)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    editing?.let { i ->
        val line = lines.getOrNull(i) ?: return@let
        if (line.kind != LineKind.TEMPLATE) {
            // The usual lines only change their size.
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(lineName(line)) },
                text = {
                    Column {
                        Text(stringResource(R.string.text_size), fontSize = AttaccaType.secondary)
                        for (value in TextSize.entries) {
                            RadioRow(sizeName(value), line.size == value) {
                                editing = null
                                lines = lines.mapIndexed { j, l -> if (j == i) l.copy(size = value) else l }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
            )
            return@let
        }
        TemplateDialog(
            line.template, line.size, null, viewModel,
            onDone = { template, size, _ ->
                editing = null
                lines = lines.mapIndexed { j, l -> if (j == i) l.copy(template = template, size = size) else l }
            },
            onDismiss = { editing = null },
        )
    }
}

/** A button's icon: an own picture, in the theme's colors or as it is, or the usual one. */
@Composable
private fun ButtonIconMenu(image: com.sadurni.attacca.settings.ThemeImage?, onChange: (com.sadurni.attacca.settings.ThemeImage?) -> Unit, onDismiss: () -> Unit) {
    val pick = rememberImagePicker(com.sadurni.attacca.ui.theme.ImageUse.ICON) {
        onChange(it)
        onDismiss()
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.icon_own)) }, onClick = pick)
        if (image != null) {
            DropdownMenuItem(
                text = { Text(stringResource(if (image.tint) R.string.icon_as_is else R.string.icon_tinted)) },
                onClick = {
                    onChange(image.copy(tint = !image.tint))
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.icon_usual)) },
                onClick = {
                    onChange(null)
                    onDismiss()
                },
            )
        }
    }
}

/** A box holds at most this many lines. */
private const val MAX_LINES = 6

@Composable
private fun sizeName(size: TextSize): String = stringResource(
    when (size) {
        TextSize.SMALL -> R.string.size_small
        TextSize.MEDIUM -> R.string.size_medium
        TextSize.LARGE -> R.string.size_large
        TextSize.HUGE -> R.string.size_huge
    },
)

@Composable
private fun lineName(line: BoxLine): String = stringResource(
    when (line.kind) {
        LineKind.TITLE -> R.string.tag_title
        LineKind.ARTIST -> R.string.tag_artist
        LineKind.ALBUM -> R.string.tag_album
        LineKind.LIST_NAME -> R.string.line_list_name
        LineKind.LIST_DETAILS -> R.string.line_list_details
        LineKind.NEXT -> R.string.line_next
        LineKind.TEMPLATE -> R.string.line_template
    },
)

private fun <T> List<T>.move(i: Int, by: Int): List<T> {
    val j = (i + by).coerceIn(indices)
    if (i == j) return this
    return toMutableList().also { it.add(j, it.removeAt(i)) }
}

@Composable
private fun partName(type: BlockType): String = stringResource(
    when (type) {
        BlockType.COVER -> R.string.part_cover
        BlockType.TITLE -> R.string.part_title
        BlockType.LIST -> R.string.part_list
        BlockType.PROGRESS -> R.string.part_progress
        BlockType.BUTTONS -> R.string.part_buttons
        BlockType.TEXT -> R.string.part_text
        BlockType.BOX -> R.string.part_box
        BlockType.SPACE -> R.string.part_space
    },
)

private fun partIcon(type: BlockType): ImageVector = when (type) {
    BlockType.COVER -> Icons.Rounded.Image
    BlockType.TITLE -> Icons.Rounded.Subtitles
    BlockType.LIST -> Icons.AutoMirrored.Rounded.QueueMusic
    BlockType.PROGRESS -> Icons.Rounded.LinearScale
    BlockType.BUTTONS -> Icons.Rounded.SmartButton
    BlockType.TEXT -> Icons.AutoMirrored.Rounded.ShortText
    BlockType.BOX -> Icons.Rounded.CropFree
    BlockType.SPACE -> Icons.Rounded.UnfoldMore
}

/** The buttons of a row, in order: move, remove and add them. */
@Composable
private fun ButtonsDialog(block: PlayerBlock, onDone: (PlayerBlock) -> Unit, onDismiss: () -> Unit) {
    var actions by remember { mutableStateOf(block.actions) }
    var icons by remember { mutableStateOf(block.icons) }
    var adding by remember { mutableStateOf(false) }
    // The button whose icon menu is open.
    var iconMenu by remember { mutableStateOf<PlayerAction?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.part_buttons)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                actions.forEachIndexed { i, action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Tapping the icon offers an own picture for it.
                        Box {
                            Box(Modifier.clickable { iconMenu = action }.padding(4.dp)) {
                                val own = icons[action]
                                if (own != null) ImageThumb(own, 26.dp) else Icon(actionIcon(action), contentDescription = null, modifier = Modifier.size(22.dp))
                            }
                            if (iconMenu == action) {
                                ButtonIconMenu(
                                    image = icons[action],
                                    onChange = { image -> icons = if (image == null) icons - action else icons + (action to image) },
                                    onDismiss = { iconMenu = null },
                                )
                            }
                        }
                        Text(actionName(action), fontSize = AttaccaType.body, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        IconButton(onClick = { actions = actions.move(i, -1) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.layout_up))
                        }
                        IconButton(onClick = { actions = actions.move(i, 1) }, enabled = i < actions.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.layout_down))
                        }
                        IconButton(onClick = { actions = actions - action }, enabled = actions.size > 1) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.layout_remove))
                        }
                    }
                }
                if (actions.size < PlayerLayout.MAX_BUTTONS) {
                    Box {
                        TextButton(onClick = { adding = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.buttons_add), modifier = Modifier.padding(start = 4.dp))
                        }
                        DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                            for (action in PlayerAction.entries.filter { it !in actions }) {
                                DropdownMenuItem(
                                    text = { Text(actionName(action)) },
                                    leadingIcon = { Icon(actionIcon(action), contentDescription = null) },
                                    onClick = {
                                        adding = false
                                        actions = actions + action
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Hint(stringResource(R.string.buttons_full, PlayerLayout.MAX_BUTTONS))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(block.copy(actions = actions, icons = icons.filterKeys { it in actions })) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** An information line: its template (with the fields to insert), size and frame, and how it reads now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateDialog(
    initialTemplate: String,
    initialSize: TextSize,
    /** Null: no frame option (lines inside a box). */
    initialBoxed: Boolean?,
    viewModel: PlayerViewModel,
    onDone: (template: String, size: TextSize, boxed: Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    var template by remember { mutableStateOf(TextFieldValue(initialTemplate, TextRange(initialTemplate.length))) }
    var size by remember { mutableStateOf(initialSize) }
    var boxed by remember { mutableStateOf(initialBoxed) }
    val values = rememberTrackValues(viewModel, needed = true, classified = needsClassificationOf(template.text))
    val colors = LocalAttaccaColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.part_text)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(R.string.text_template)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // How the line reads with the playing track.
                val shown = InfoTemplate.render(template.text, values)
                Text(
                    shown.ifEmpty { stringResource(R.string.text_preview_empty) },
                    fontSize = AttaccaType.body,
                    color = if (shown.isEmpty()) colors.textGrayed else colors.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .border(1.dp, colors.textGrayed.copy(alpha = 0.4f), AttaccaShapes.small)
                        .padding(8.dp),
                )
                Text(stringResource(R.string.text_fields), fontSize = AttaccaType.secondary, modifier = Modifier.padding(bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (field in InfoTemplate.FIELDS) {
                        Text(
                            fieldName(field),
                            fontSize = AttaccaType.secondary,
                            modifier = Modifier
                                .border(1.dp, colors.textGrayed.copy(alpha = 0.5f), AttaccaShapes.small)
                                .clickable {
                                    // Inserted where the cursor is.
                                    val insert = "{$field}"
                                    val t = template.text
                                    val at = template.selection.end.coerceIn(0, t.length)
                                    template = TextFieldValue(t.substring(0, at) + insert + t.substring(at), TextRange(at + insert.length))
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Hint(stringResource(R.string.text_hint))
                Text(stringResource(R.string.text_size), fontSize = AttaccaType.secondary, modifier = Modifier.padding(top = 4.dp))
                for (value in TextSize.entries) {
                    RadioRow(sizeName(value), size == value) { size = value }
                }
                boxed?.let { b -> SwitchRow(stringResource(R.string.text_boxed), b, { boxed = it }) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(template.text, size, boxed) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun fieldName(field: String): String = stringResource(
    when (field) {
        "title" -> R.string.tag_title
        "artist" -> R.string.tag_artist
        "album" -> R.string.tag_album
        "albumartist" -> R.string.tag_album_artist
        "track" -> R.string.tag_track
        "disc" -> R.string.tag_disc
        "year" -> R.string.tag_year
        "genre" -> R.string.tag_genre
        "composer" -> R.string.tag_composer
        "comment" -> R.string.tag_comment
        "format" -> R.string.info_format
        "codec" -> R.string.info_codec
        "bitrate" -> R.string.info_bitrate
        "samplerate" -> R.string.info_sample_rate
        "bits" -> R.string.info_bit_depth
        "channels" -> R.string.info_channels
        "duration" -> R.string.info_duration
        "filename" -> R.string.info_name
        "folder" -> R.string.info_folder
        "size" -> R.string.info_size
        "list" -> R.string.playlist_label
        "position" -> R.string.field_position
        "count" -> R.string.field_count
        "next" -> R.string.line_next
        "nextartist" -> R.string.field_next_artist
        "loudness" -> R.string.info_loudness
        "gain" -> R.string.field_gain
        "bpm" -> R.string.field_bpm
        "mood" -> R.string.field_mood
        "family" -> R.string.field_family
        "plays" -> R.string.info_plays
        "lastplayed" -> R.string.info_last_played
        else -> R.string.part_text
    },
)
