package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.sadurni.attacca.settings.LayoutParts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.settings.AppIcon
import com.sadurni.attacca.settings.BlockType
import com.sadurni.attacca.settings.InfoTemplate
import com.sadurni.attacca.settings.PlayerAction
import com.sadurni.attacca.settings.PlayerBlock
import com.sadurni.attacca.settings.PlayerLayout
import com.sadurni.attacca.settings.TextSize
import com.sadurni.attacca.settings.ThemeImage
import com.sadurni.attacca.settings.ThemeSpec

/** The theme's own icons for parts of the phone's app: a picture each, drawn as it is or in the theme's colours. */
@Composable
internal fun IconsSection(state: AppState, theme: Theme, change: ((ThemeSpec) -> ThemeSpec) -> Unit) {
    Section(tr("Own icons (phone)", "Iconos propios (teléfono)")) {
        Hint(tr(
            "A picture (PNG with transparency is best) for each icon to replace. Theme colours draws only its shape, in the theme's colours.",
            "Una imagen (mejor PNG con transparencia) por cada icono que se quiera cambiar. Con colores del tema se dibuja solo su forma, en los colores del tema.",
        ))
        for (icon in AppIcon.entries) {
            val image = theme.spec.icons[icon]
            fun set(i: ThemeImage?) = change { s -> s.copy(icons = if (i == null) s.icons - icon else s.icons + (icon to i)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumb(theme, image?.file, 32.dp)
                Text(iconName(icon), Modifier.weight(1f))
                if (image != null) {
                    Text(tr("Theme colours", "Colores del tema"), style = MaterialTheme.typography.bodySmall)
                    Switch(image.tint, { on -> set(image.copy(tint = on)) })
                }
                TextButton(onClick = {
                    pickFile(state, iconName(icon), "*.png;*.webp;*.jpg")?.let { f -> Themes.addFile(theme, f)?.let { set((image ?: ThemeImage(it, tint = true)).copy(file = it)) } }
                }) { Text(if (image == null) tr("Picture…", "Imagen…") else tr("Change…", "Cambiar…")) }
                if (image != null) TextButton(onClick = { set(null) }) { Text(tr("Remove", "Quitar")) }
            }
        }
    }
}

private fun iconName(i: AppIcon) = when (i) {
    AppIcon.FOLDER -> tr("Folder", "Carpeta")
    AppIcon.PLAYLIST -> tr("List", "Lista")
    AppIcon.TRACK -> tr("Track", "Pista")
    AppIcon.NO_COVER -> tr("No cover", "Sin carátula")
    AppIcon.PLAYER -> tr("Now playing", "Reproduciendo")
    AppIcon.LISTS -> tr("Lists", "Listas")
    AppIcon.BOOKMARKS -> tr("Bookmarks", "Marcadores")
    AppIcon.SOUND -> tr("Sound", "Sonido")
    AppIcon.DEVICES -> tr("Devices", "Dispositivos")
    AppIcon.SETTINGS -> tr("Settings", "Ajustes")
}

/** How the phone's Now playing is laid out: its parts top to bottom, upright and (its own, or Attacca's) in landscape. */
@Composable
internal fun LayoutSection(theme: Theme, change: ((ThemeSpec) -> ThemeSpec) -> Unit) {
    val spec = theme.spec
    Section(tr("Now playing layout (phone)", "Disposición de Reproduciendo (teléfono)")) {
        Hint(tr(
            "Its parts top to bottom. A box groups parts (side by side or one under the other), with or without a frame. Drag a part by ⠿ to move it (onto the middle of a box: into it); ⋯ copies or cuts it (to paste in another box, layout or theme) and puts it into or out of a box. The previews follow every change.",
            "Sus partes de arriba abajo. Una caja agrupa partes (una al lado de otra o una debajo de otra), con marco o sin él. Arrastra una parte por ⠿ para moverla (sobre el centro de una caja: dentro); ⋯ la copia o la corta (para pegarla en otra caja, disposición o tema) y la mete en una caja o la saca. Las vistas previas siguen cada cambio.",
        ))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { change { it.copy(layout = PlayerLayout.DEFAULT) } }) { Text(tr("As Attacca has it", "Como lo trae Attacca")) }
        }
        LayoutEditing(theme, spec.layout.blocks) { blocks -> change { it.copy(layout = PlayerLayout(blocks.ifEmpty { PlayerLayout.DEFAULT.blocks })) } }
        Text(tr("In landscape", "En horizontal"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        val own = spec.landscape
        if (own == null) {
            Hint(tr("Attacca lays it out by itself: the cover and the list at the left, the rest at the right.", "Attacca la dispone sola: la carátula y la lista a la izquierda, el resto a la derecha."))
            OutlinedButton(onClick = { change { it.copy(landscape = it.layout.toLandscape()) } }) { Text(tr("Make one of its own", "Hacer una propia")) }
        } else {
            TextButton(onClick = { change { it.copy(landscape = null) } }) { Text(tr("Let Attacca lay it out", "Que la disponga Attacca")) }
            LayoutEditing(theme, own.blocks) { blocks -> change { it.copy(landscape = if (blocks.isEmpty()) null else PlayerLayout(blocks)) } }
        }
    }
}

/** The part copied or cut, kept while the window is open: it pastes into another box, layout or theme. */
internal object LayoutClipboard {
    var clip by mutableStateOf<LayoutParts.Clip?>(null)
}

/**
 * One layout being edited: its parts (each by its path, see [LayoutParts]), the theme it belongs
 * to and the part being dragged with the mouse.
 */
private class Editing(val theme: Theme, val root: List<PlayerBlock>, val setRoot: (List<PlayerBlock>) -> Unit) {
    val present: Set<BlockType> = root.flatMap { it.flatten() }.map { it.type }.toSet()

    /** Where the parts' rows and the lists' ends are in the window, for dropping. */
    val rows = HashMap<List<Int>, Rect>()
    val ends = HashMap<List<Int>, Rect>()
    var dragged by mutableStateOf<List<Int>?>(null)
    var pointer by mutableStateOf(Offset.Zero)

    fun clip(block: PlayerBlock) = LayoutParts.Clip(block, theme.id, theme.picture)

    fun move(from: List<Int>, parent: List<Int>, index: Int) {
        LayoutParts.move(root, from, parent, index)?.let(setRoot)
    }

    fun replace(path: List<Int>, block: PlayerBlock) {
        val parent = path.dropLast(1)
        val list = if (parent.isEmpty()) root else LayoutParts.at(root, parent)?.children ?: return
        setRoot(withList(root, parent, list.toMutableList().also { it[path.last()] = block }))
    }

    /** Where the dragged part would go: the list (its path), the place in it and the row under the pointer (null: the list's end). */
    fun target(): Triple<List<Int>, Int, List<Int>?>? {
        val from = dragged ?: return null
        val inside = { q: List<Int> -> q.size >= from.size && q.subList(0, from.size) == from }
        val row = rows.entries.filter { (q, r) -> r.contains(pointer) && !inside(q) }.maxByOrNull { it.key.size }
        if (row != null) {
            val q = row.key
            val r = row.value
            val f = (pointer.y - r.top) / r.height.coerceAtLeast(1f)
            val block = LayoutParts.at(root, q)
            return when {
                block?.type == BlockType.BOX && f in 0.3f..0.7f -> Triple(q, block.children.size, q)
                f < 0.5f -> Triple(q.dropLast(1), q.last(), q)
                else -> Triple(q.dropLast(1), q.last() + 1, q)
            }
        }
        val end = ends.entries.filter { (q, r) -> r.contains(pointer) && !inside(q) }.maxByOrNull { it.key.size } ?: return null
        val list = if (end.key.isEmpty()) root else LayoutParts.at(root, end.key)?.children.orEmpty()
        return Triple(end.key, list.size, null)
    }

    fun drop() {
        val from = dragged
        val to = target()
        dragged = null
        if (from != null && to != null) move(from, to.first, to.second)
    }
}

/** [blocks] with the list at [parent] (the screen's, or a box's) replaced by [list]. */
private fun withList(blocks: List<PlayerBlock>, parent: List<Int>, list: List<PlayerBlock>): List<PlayerBlock> {
    if (parent.isEmpty()) return list
    return blocks.mapIndexed { j, b -> if (j == parent.first()) b.copy(children = withList(b.children, parent.drop(1), list)) else b }
}

@Composable
private fun LayoutEditing(theme: Theme, blocks: List<PlayerBlock>, setRoot: (List<PlayerBlock>) -> Unit) {
    val editing = remember(theme.id, blocks) { Editing(theme, blocks, setRoot) }
    BlockList(editing, blocks, emptyList())
}

/** A list of parts (the screen's or a box's, at [parent]), each to move, take out or change, and a part to add or paste. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockList(ed: Editing, blocks: List<PlayerBlock>, parent: List<Int>) {
    val depth = parent.size
    fun set(list: List<PlayerBlock>) = ed.setRoot(withList(ed.root, parent, list))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { i, block ->
            BlockEditor(ed, block, parent + i, first = i == 0, last = i == blocks.lastIndex)
        }
        // Parts that appear once are offered while missing anywhere in the layout.
        val addable = BlockType.entries.filter { it !in PlayerLayout.SINGLE || it !in ed.present }
            .filter { it != BlockType.BOX || depth < PlayerBlock.MAX_DEPTH - 1 }
        val dropHere = ed.dragged != null && ed.target()?.let { it.third == null && it.first == parent } == true
        FlowRow(
            Modifier.fillMaxWidth().onGloballyPositioned { ed.ends[parent] = it.boundsInRoot() }
                .then(if (dropHere) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tr("Add:", "Añadir:"), Modifier.align(Alignment.CenterVertically))
            for (type in addable) TextButton(onClick = { set(blocks + newBlock(type)) }) { Text(blockName(type)) }
            // The part copied or cut (with its pictures, from another theme).
            val clip = LayoutClipboard.clip
            if (clip != null) TextButton(
                onClick = {
                    val folder = ed.theme.file?.parentFile ?: return@TextButton
                    val block = LayoutParts.paste(clip, ed.theme.id, folder)
                    Themes.forget(ed.theme.file)
                    set(blocks + block.copy(width = 0))
                },
                enabled = ed.theme.file != null && LayoutParts.fits(clip.block, depth, ed.present),
            ) { Text(tr("Paste: ", "Pegar: ") + blockName(clip.block.type)) }
        }
    }
}

private fun boxLevels(block: PlayerBlock) = LayoutParts.boxLevels(block)

private fun newBlock(type: BlockType) = when (type) {
    BlockType.BUTTONS -> PlayerBlock(type, actions = listOf(PlayerAction.PREVIOUS, PlayerAction.PLAY_PAUSE, PlayerAction.NEXT))
    BlockType.TEXT -> PlayerBlock(type, template = PlayerBlock.NEW_LINE_TEMPLATE)
    else -> PlayerBlock(type)
}

internal fun blockName(t: BlockType) = when (t) {
    BlockType.COVER -> tr("Cover", "Carátula")
    BlockType.TITLE -> tr("Title box", "Caja del título")
    BlockType.LIST -> tr("List box", "Caja de la lista")
    BlockType.PROGRESS -> tr("Progress", "Progreso")
    BlockType.BUTTONS -> tr("Buttons", "Botones")
    BlockType.TEXT -> tr("Information line", "Línea de información")
    BlockType.BOX -> tr("Box", "Caja")
    BlockType.SPACE -> tr("Space", "Espacio")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockEditor(ed: Editing, block: PlayerBlock, path: List<Int>, first: Boolean, last: Boolean) {
    var open by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val depth = path.size - 1
    val parent = path.dropLast(1)
    val index = path.last()
    val siblings = if (parent.isEmpty()) ed.root else LayoutParts.at(ed.root, parent)?.children.orEmpty()
    val onChange = { b: PlayerBlock -> ed.replace(path, b) }
    val onRemove = { ed.setRoot(LayoutParts.remove(ed.root, path)) }
    val editable = block.type in setOf(BlockType.BUTTONS, BlockType.TEXT, BlockType.TITLE, BlockType.LIST, BlockType.BOX)
    val target = if (ed.dragged != null) ed.target() else null
    val highlighted = target?.third == path
    val into = highlighted && target?.first == path
    var handle by remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier.fillMaxWidth()
            .then(if (ed.dragged == path) Modifier.alpha(0.4f) else Modifier)
            .then(if (into) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)) else Modifier)
            .border(
                BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(Modifier.onGloballyPositioned { ed.rows[path] = it.boundsInRoot() }, verticalAlignment = Alignment.CenterVertically) {
            // The handle to drag the part elsewhere (into a box: onto its middle).
            Text(
                "⠿",
                Modifier.padding(end = 6.dp).onGloballyPositioned { handle = it.boundsInRoot() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(path) {
                        detectDragGestures(
                            onDragStart = { at -> ed.dragged = path; ed.pointer = handle.topLeft + at },
                            onDrag = { change, _ -> change.consume(); ed.pointer = handle.topLeft + change.position },
                            onDragEnd = { ed.drop() },
                            onDragCancel = { ed.dragged = null },
                        )
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(blockName(block.type))
                summary(block).takeIf { it.isNotEmpty() }?.let {
                    Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Mini("↑", !first) { ed.move(path, parent, index - 1) }
            Mini("↓", !last) { ed.move(path, parent, index + 2) }
            Box {
                Mini("⋯") { menu = true }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    // Into a box beside it (one it fits in), or out of its own (right after it).
                    for ((j, box) in siblings.withIndex()) {
                        if (j == index || box.type != BlockType.BOX || depth + 1 + boxLevels(block) > PlayerBlock.MAX_DEPTH - 1) continue
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(tr("Into the box: ", "A la caja: ") + box.children.joinToString(", ") { blockName(it.type) }.ifEmpty { "—" }) },
                            onClick = { menu = false; ed.move(path, parent + j, box.children.size) },
                        )
                    }
                    if (parent.isNotEmpty()) androidx.compose.material3.DropdownMenuItem(
                        text = { Text(tr("Take out of the box", "Sacar de la caja")) },
                        onClick = { menu = false; ed.move(path, parent.dropLast(1), parent.last() + 1) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(tr("Copy", "Copiar")) },
                        onClick = { menu = false; LayoutClipboard.clip = ed.clip(block) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(tr("Cut", "Cortar")) },
                        onClick = { menu = false; LayoutClipboard.clip = ed.clip(block); onRemove() },
                    )
                }
            }
            if (editable) Mini(if (open) tr("Less", "Menos") else tr("More", "Más")) { open = !open }
            Mini("✕", color = MaterialTheme.colorScheme.error, onClick = onRemove)
        }
        if (!open) return@Column
        when (block.type) {
            BlockType.BUTTONS -> {
                Hint(tr("Up to %d buttons, in the order chosen.", "Hasta %d botones, en el orden elegido.").format(PlayerLayout.MAX_BUTTONS))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in PlayerAction.entries) {
                        val on = a in block.actions
                        FilterChip(on, onClick = {
                            val actions = if (on) block.actions - a else (block.actions + a).take(PlayerLayout.MAX_BUTTONS)
                            if (actions.isNotEmpty()) onChange(block.copy(actions = actions, icons = block.icons.filterKeys { it in actions }))
                        }, label = { Text(actionName(a)) })
                    }
                }
            }
            BlockType.TEXT -> {
                var text by remember(block.template) { mutableStateOf(block.template) }
                OutlinedTextField(text, { v -> text = v; onChange(block.copy(template = v)) }, Modifier.fillMaxWidth(), singleLine = true)
                Hint(tr("Fields in braces: ", "Campos entre llaves: ") + InfoTemplate.FIELDS.joinToString(" ") { "{$it}" } +
                    tr(". [ … ] around a part leaves it out when a field in it is empty.", ". [ … ] alrededor de una parte la quita si un campo de dentro está vacío."))
                Choice(tr("Size", "Tamaño"), block.size, TextSize.entries, ::sizeName, { onChange(block.copy(size = it)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(block.boxed, { onChange(block.copy(boxed = it)) })
                    Text("  " + tr("In a frame", "En un marco"))
                }
            }
            BlockType.TITLE, BlockType.LIST -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(block.framed, { onChange(block.copy(framed = it)) })
                Text("  " + tr("With a frame", "Con marco"))
            }
            BlockType.BOX -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(block.framed, { onChange(block.copy(framed = it)) })
                    Text("  " + tr("With a frame", "Con marco") + "    ")
                    Switch(block.horizontal, { onChange(block.copy(horizontal = it)) })
                    Text("  " + tr("Side by side", "Una al lado de otra"))
                }
                Column(Modifier.padding(start = 12.dp)) {
                    BlockList(ed, block.children, path)
                }
            }
            else -> Unit
        }
        if (depth > 0 || block.width > 0) {
            SliderRow(tr("Width (side by side)", "Anchura (en fila)"), block.width.toFloat(), 0f..100f, { if (it < 1f) tr("shared", "repartida") else "${it.toInt()} %" }) {
                onChange(block.copy(width = it.toInt()))
            }
        }
    }
}

private fun summary(b: PlayerBlock): String = when (b.type) {
    BlockType.BUTTONS -> b.actions.joinToString(", ") { actionName(it) }
    BlockType.TEXT -> b.template
    BlockType.BOX -> b.children.joinToString(", ") { blockName(it.type) }
    else -> ""
}

/** A narrow text button (the row has several). */
@Composable
private fun Mini(text: String, enabled: Boolean = true, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
    ) { Text(text, color = if (enabled) color else androidx.compose.ui.graphics.Color.Unspecified) }
}

private fun sizeName(s: TextSize) = when (s) {
    TextSize.SMALL -> tr("Small", "Pequeño")
    TextSize.MEDIUM -> tr("Medium", "Mediano")
    TextSize.LARGE -> tr("Large", "Grande")
    TextSize.HUGE -> tr("Huge", "Enorme")
}

internal fun actionName(a: PlayerAction) = when (a) {
    PlayerAction.PLAY_PAUSE -> tr("Play / pause", "Reproducir / pausa")
    PlayerAction.PREVIOUS -> tr("Previous", "Anterior")
    PlayerAction.NEXT -> tr("Next", "Siguiente")
    PlayerAction.PREVIOUS_ALBUM -> tr("Previous album", "Álbum anterior")
    PlayerAction.NEXT_ALBUM -> tr("Next album", "Álbum siguiente")
    PlayerAction.SHUFFLE -> tr("Shuffle", "Aleatorio")
    PlayerAction.REPEAT -> tr("Repeat", "Repetir")
    PlayerAction.SEEK_BACK -> tr("Back a bit", "Atrás un poco")
    PlayerAction.SEEK_FORWARD -> tr("Forward a bit", "Adelante un poco")
    PlayerAction.NAVIGATOR -> tr("Navigator", "Navegador")
    PlayerAction.BOOKMARK -> tr("Bookmark", "Marcador")
    PlayerAction.SLEEP -> tr("Sleep timer", "Temporizador")
    PlayerAction.FAVORITE -> tr("Favorite", "Favorito")
    PlayerAction.SOUND -> tr("Sound", "Sonido")
    PlayerAction.QUEUE -> tr("Queue", "Cola")
    PlayerAction.LISTS -> tr("Lists", "Listas")
    PlayerAction.LYRICS -> tr("Lyrics", "Letra")
    PlayerAction.ADD_TO_LIST -> tr("Add to a list", "Añadir a una lista")
    PlayerAction.SPEED -> tr("Speed", "Velocidad")
    PlayerAction.AB_REPEAT -> tr("A-B repeat", "Repetir A-B")
    PlayerAction.TAGS -> tr("Tags", "Etiquetas")
    PlayerAction.INFO -> tr("Information", "Información")
    PlayerAction.FOLDER -> tr("Its folder", "Su carpeta")
    PlayerAction.ALBUM -> tr("Its album", "Su álbum")
    PlayerAction.ARTIST -> tr("Its artist", "Su artista")
    PlayerAction.TO_END -> tr("To the end", "Al final")
    PlayerAction.QUICK_LIST -> tr("Quick list", "Lista rápida")
    PlayerAction.RANDOM_LIST -> tr("Random list", "Lista al azar")
    PlayerAction.ORAMA -> "Attacca O'rama"
}
