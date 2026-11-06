package com.sadurni.attacca.settings

import java.io.File

/**
 * Parts of Now playing moved about: by their path in the layout (the index in the screen's list,
 * then in each box's), copied, cut and pasted, also into another theme with the pictures they use.
 */
object LayoutParts {

    /** A part copied or cut, and the theme it comes from (its pictures are read from there). */
    class Clip(val block: PlayerBlock, val themeId: String, val read: (String) -> ByteArray?)

    /** The part at [path]. */
    fun at(blocks: List<PlayerBlock>, path: List<Int>): PlayerBlock? {
        var list = blocks
        var block: PlayerBlock? = null
        for (i in path) {
            block = list.getOrNull(i) ?: return null
            list = block.children
        }
        return block
    }

    /** [blocks] with the list at [parent] (the screen's, or a box's) changed by [change]. */
    private fun changeList(blocks: List<PlayerBlock>, parent: List<Int>, change: (List<PlayerBlock>) -> List<PlayerBlock>): List<PlayerBlock> {
        if (parent.isEmpty()) return change(blocks)
        val i = parent.first()
        return blocks.mapIndexed { j, b -> if (j == i) b.copy(children = changeList(b.children, parent.drop(1), change)) else b }
    }

    /** [blocks] without the part at [path]; a box left empty goes too. */
    fun remove(blocks: List<PlayerBlock>, path: List<Int>): List<PlayerBlock> {
        val result = changeList(blocks, path.dropLast(1)) { list -> list.filterIndexed { j, _ -> j != path.last() } }
        val parent = path.dropLast(1)
        return if (parent.isNotEmpty() && at(result, parent)?.children?.isEmpty() == true) remove(result, parent) else result
    }

    /** [blocks] with [block] at [index] of the list at [parent]. */
    fun insert(blocks: List<PlayerBlock>, parent: List<Int>, index: Int, block: PlayerBlock): List<PlayerBlock> =
        changeList(blocks, parent) { list -> list.toMutableList().apply { add(index.coerceIn(0, size), block) } }

    /**
     * The part at [from] moved to [index] of the list at [parent] (both as before the move); null
     * when it cannot go there (into itself, or deeper than boxes may go).
     */
    fun move(blocks: List<PlayerBlock>, from: List<Int>, parent: List<Int>, index: Int): List<PlayerBlock>? {
        val block = at(blocks, from) ?: return null
        if (parent.size >= from.size && parent.subList(0, from.size) == from) return null
        if (parent.size + boxLevels(block) > PlayerBlock.MAX_DEPTH - 1) return null
        // Where the place is once the part has left its list.
        val p = parent.toMutableList()
        var at = index
        val fromParent = from.dropLast(1)
        val k = fromParent.size
        if (p.size > k && p.subList(0, k) == fromParent && p[k] > from.last()) p[k]--
        if (p == fromParent && at > from.last()) at--
        val without = changeList(blocks, fromParent) { list -> list.filterIndexed { j, _ -> j != from.last() } }
        val moved = insert(without, p, at, block.copy(width = if (p.size == parent.size && p == fromParent) block.width else 0))
        // A box the part left empty goes (where it is once the part went in before it).
        val left = fromParent.toMutableList()
        if (left.size > p.size && left.subList(0, p.size) == p && left[p.size] >= at) left[p.size]++
        return if (left.isNotEmpty() && at(moved, left)?.let { it.type == BlockType.BOX && it.children.isEmpty() } == true) remove(moved, left) else moved
    }

    /** How many boxes deep [block] goes: 0 for a part, 1 for a box of parts, 2 with a box inside… */
    fun boxLevels(block: PlayerBlock): Int =
        if (block.type != BlockType.BOX) 0 else 1 + (block.children.maxOfOrNull(::boxLevels) ?: 0)

    /**
     * Whether [block] may be pasted in the list at [depth] (0: the screen's) of a layout that has
     * [present] parts: parts that appear once only where they are missing, boxes only as deep as allowed.
     */
    fun fits(block: PlayerBlock, depth: Int, present: Set<BlockType>): Boolean =
        block.flatten().none { it.type in PlayerLayout.SINGLE && it.type in present } &&
            depth + boxLevels(block) <= PlayerBlock.MAX_DEPTH - 1

    /** The pictures [block] uses (its own, its buttons', its parts'). */
    fun files(block: PlayerBlock): Set<String> =
        block.flatten().flatMap { b -> b.image?.files.orEmpty() + b.icons.values.flatMap { it.files } }.toSet()

    /**
     * [clip]'s part ready to paste into the theme [themeId] whose pictures are in [folder]: from
     * another theme, its pictures are copied there (under another name when one is taken by a
     * different picture). Blocking.
     */
    fun paste(clip: Clip, themeId: String, folder: File): PlayerBlock {
        if (clip.themeId == themeId) return clip.block
        val names = HashMap<String, String>()
        for (name in files(clip.block)) {
            val bytes = clip.read(name) ?: continue
            folder.mkdirs()
            var target = File(folder, name)
            var n = 2
            while (target.exists() && !target.readBytes().contentEquals(bytes)) target = File(folder, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "png")}").also { n++ }
            if (!target.exists()) target.writeBytes(bytes)
            names[name] = target.name
        }
        return renamed(clip.block) { names[it] ?: it }
    }

    private fun renamed(block: PlayerBlock, name: (String) -> String): PlayerBlock {
        fun ThemeImage.renamed() = copy(file = name(file), fileLight = fileLight?.let(name), fileWide = fileWide?.let(name), fileWideLight = fileWideLight?.let(name))
        return block.copy(
            image = block.image?.renamed(),
            icons = block.icons.mapValues { it.value.renamed() },
            children = block.children.map { renamed(it, name) },
        )
    }
}
