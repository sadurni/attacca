package com.sadurni.attacca.settings

import org.json.JSONArray
import org.json.JSONObject

/** What a button of Now playing does. */
enum class PlayerAction {
    PLAY_PAUSE, PREVIOUS, NEXT, PREVIOUS_ALBUM, NEXT_ALBUM, SHUFFLE, REPEAT, SEEK_BACK, SEEK_FORWARD,
    NAVIGATOR, BOOKMARK, SLEEP, FAVORITE, SOUND, QUEUE, LISTS, LYRICS, ADD_TO_LIST, SPEED, AB_REPEAT,
    TAGS, INFO, FOLDER, ALBUM, ARTIST, TO_END, QUICK_LIST, RANDOM_LIST, ORAMA,
}

/** The parts Now playing is made of. */
enum class BlockType {
    /** The cover; takes the room left over. */
    COVER,

    /** Title, artist and album, with the lyrics as a second page and the track menu. */
    TITLE,

    /** The list being played: its name, the position and a few tags. */
    LIST,

    /** Seek bar (or waveform) and times. */
    PROGRESS,

    /** A row of buttons. */
    BUTTONS,

    /** A line of information made from a template (see [InfoTemplate]). */
    TEXT,

    /**
     * A box holding other parts (lines, buttons, other boxes…), one under the other or side by side,
     * with or without a frame. Any number of them.
     */
    BOX,

    /** Empty room that grows (to push the rest down or up). */
    SPACE,
}

enum class TextSize { SMALL, MEDIUM, LARGE, HUGE }

/** How the cover fills a box: cropped to fill it, whole inside it, or stretched to its shape. */
enum class CoverFit { CROP, FIT, STRETCH }

/**
 * The cover of the playing track as the background of a box: how it fits, how much it is softened
 * with the theme's background color ([dim] %, as a picture's) and blurred ([blur] dp).
 */
data class CoverBackground(val fit: CoverFit = CoverFit.CROP, val dim: Int = 40, val blur: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("fit", fit.name).put("dim", dim).put("blur", blur)

    companion object {
        const val MAX_BLUR = 50

        fun fromJson(o: JSONObject?): CoverBackground? = o?.let {
            CoverBackground(
                CoverFit.entries.firstOrNull { f -> f.name == it.optString("fit") } ?: CoverFit.CROP,
                it.optInt("dim", 40).coerceIn(0, ThemeImage.MAX_DIM),
                it.optInt("blur", 0).coerceIn(0, MAX_BLUR),
            )
        }
    }
}

/** What a line of the title or list box shows. */
enum class LineKind {
    /** The track title, with the mark of tracks left out of the inclusion list. */
    TITLE,
    ARTIST,
    ALBUM,

    /** The name of the list being played. */
    LIST_NAME,

    /** Position in the list, track number, year and genre in one row. */
    LIST_DETAILS,

    /** The track that plays next (title and artist), as the list's play order has it. */
    NEXT,

    /** A line from a template (see [InfoTemplate]). */
    TEMPLATE,
}

/** A line of the title or list box; every kind has its text size (the usual one: [defaultSize]). */
data class BoxLine(val kind: LineKind, val template: String = "", val size: TextSize = defaultSize(kind)) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).also { o ->
        if (kind == LineKind.TEMPLATE) o.put("template", template)
        if (kind == LineKind.TEMPLATE || size != defaultSize(kind)) o.put("size", size.name)
    }

    companion object {
        fun fromJson(o: JSONObject): BoxLine? {
            val kind = LineKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
            val size = TextSize.entries.firstOrNull { it.name == o.optString("size") } ?: defaultSize(kind)
            return BoxLine(kind, if (kind == LineKind.TEMPLATE) o.optString("template") else "", size)
        }

        /** The size a line has always had: the title huge, artist and album large, the list's lines small. */
        fun defaultSize(kind: LineKind): TextSize = when (kind) {
            LineKind.TITLE -> TextSize.HUGE
            LineKind.ARTIST, LineKind.ALBUM -> TextSize.LARGE
            LineKind.LIST_NAME, LineKind.LIST_DETAILS, LineKind.NEXT -> TextSize.SMALL
            LineKind.TEMPLATE -> TextSize.MEDIUM
        }

        val TITLE_BOX = listOf(BoxLine(LineKind.TITLE), BoxLine(LineKind.ARTIST), BoxLine(LineKind.ALBUM))
        val LIST_BOX = listOf(BoxLine(LineKind.LIST_NAME), BoxLine(LineKind.LIST_DETAILS), BoxLine(LineKind.NEXT))
    }
}

data class PlayerBlock(
    val type: BlockType,
    /** For [BlockType.BUTTONS]. */
    val actions: List<PlayerAction> = emptyList(),
    /** For [BlockType.TEXT]. */
    val template: String = "",
    val size: TextSize = TextSize.SMALL,
    /** For [BlockType.TEXT]: drawn in a frame like the list box. */
    val boxed: Boolean = false,
    /** For [BlockType.TITLE] and [BlockType.LIST]: their lines, top to bottom. */
    val lines: List<BoxLine> = defaultLines(type),
    /** For [BlockType.BOX]: the parts inside. */
    val children: List<PlayerBlock> = defaultChildren(type),
    /** For [BlockType.BOX], [BlockType.TITLE] and [BlockType.LIST]: drawn with a frame (a box without one only groups or lines up its parts). */
    val framed: Boolean = true,
    /** For [BlockType.BOX]: its parts side by side instead of one under the other. */
    val horizontal: Boolean = false,
    /** Share of the width (%) inside a box with parts side by side; 0 shares what is left equally. */
    val width: Int = 0,
    /** For [BlockType.BUTTONS]: own icons of some of its buttons. */
    val icons: Map<PlayerAction, ThemeImage> = emptyMap(),
    /** For [BlockType.BOX], [BlockType.TITLE] and [BlockType.LIST]: a background image, fitted to the box. */
    val image: ThemeImage? = null,
    /** For [BlockType.BOX], [BlockType.TITLE] and [BlockType.LIST]: the playing track's cover as background (over [image]). */
    val cover: CoverBackground? = null,
) {
    /** This part and every part inside it. */
    fun flatten(): List<PlayerBlock> = listOf(this) + children.flatMap { it.flatten() }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).also { o ->
        if (width > 0) o.put("width", width)
        when (type) {
            BlockType.BUTTONS -> o.put("buttons", JSONArray(actions.map { it.name })).also {
                if (icons.isNotEmpty()) o.put("icons", JSONObject().also { i -> icons.forEach { (a, img) -> i.put(a.name, img.toJson()) } })
            }
            BlockType.TEXT -> o.put("template", template).put("size", size.name).put("boxed", boxed)
            BlockType.TITLE, BlockType.LIST -> o.put("lines", JSONArray(lines.map { it.toJson() }))
                .put("framed", framed)
                .also { if (image != null) it.put("image", image.toJson()) }
                .also { if (cover != null) it.put("cover", cover.toJson()) }
            BlockType.BOX -> o.put("children", JSONArray(children.map { it.toJson() }))
                .put("framed", framed)
                .put("horizontal", horizontal)
                .also { if (image != null) it.put("image", image.toJson()) }
                .also { if (cover != null) it.put("cover", cover.toJson()) }
            else -> Unit
        }
    }

    companion object {
        /** Boxes inside boxes go this deep at most. */
        const val MAX_DEPTH = 4

        /**
         * A block from its JSON; null for what this version does not know. [seen] collects the
         * parts that appear once (a second one is dropped, wherever it is).
         */
        fun fromJson(o: JSONObject, seen: MutableSet<BlockType> = mutableSetOf(), depth: Int = 0): PlayerBlock? {
            val type = BlockType.entries.firstOrNull { it.name == o.optString("type") } ?: return null
            if (type in PlayerLayout.SINGLE && !seen.add(type)) return null
            val width = o.optInt("width", 0).coerceIn(0, 100)
            return parse(type, o, seen, depth)?.copy(width = width)
        }

        private fun parse(type: BlockType, o: JSONObject, seen: MutableSet<BlockType>, depth: Int): PlayerBlock? {
            return when (type) {
                BlockType.BUTTONS -> {
                    val names = o.optJSONArray("buttons") ?: JSONArray()
                    val actions = (0 until names.length())
                        .mapNotNull { i -> PlayerAction.entries.firstOrNull { it.name == names.optString(i) } }
                        .distinct()
                        .take(PlayerLayout.MAX_BUTTONS)
                    val icons = o.optJSONObject("icons")?.let { i ->
                        actions.mapNotNull { a -> ThemeImage.fromJson(i.optJSONObject(a.name))?.let { a to it } }.toMap()
                    }.orEmpty()
                    if (actions.isEmpty()) null else PlayerBlock(type, actions = actions, icons = icons)
                }
                BlockType.TEXT -> PlayerBlock(
                    type,
                    template = o.optString("template"),
                    size = TextSize.entries.firstOrNull { it.name == o.optString("size") } ?: TextSize.SMALL,
                    boxed = o.optBoolean("boxed", false),
                )
                BlockType.TITLE, BlockType.LIST -> {
                    // Boxes saved before they had lines keep the usual ones.
                    val saved = o.optJSONArray("lines")
                    val lines = saved?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(BoxLine::fromJson) } }
                    PlayerBlock(
                        type,
                        lines = lines?.takeIf { it.isNotEmpty() } ?: defaultLines(type),
                        framed = o.optBoolean("framed", true),
                        image = ThemeImage.fromJson(o.optJSONObject("image")),
                        cover = CoverBackground.fromJson(o.optJSONObject("cover")),
                    )
                }
                BlockType.BOX -> {
                    if (depth >= MAX_DEPTH) return null
                    val saved = o.optJSONArray("children") ?: JSONArray()
                    val children = (0 until saved.length()).mapNotNull { i ->
                        saved.optJSONObject(i)?.let { fromJson(it, seen, depth + 1) }
                    }
                    if (children.isEmpty()) {
                        null
                    } else {
                        PlayerBlock(
                            type,
                            children = children,
                            framed = o.optBoolean("framed", true),
                            horizontal = o.optBoolean("horizontal", false),
                            image = ThemeImage.fromJson(o.optJSONObject("image")),
                            cover = CoverBackground.fromJson(o.optJSONObject("cover")),
                        )
                    }
                }
                else -> PlayerBlock(type)
            }
        }

        fun defaultChildren(type: BlockType): List<PlayerBlock> =
            if (type == BlockType.BOX) listOf(PlayerBlock(BlockType.TEXT, template = NEW_LINE_TEMPLATE)) else emptyList()

        /** A new information line starts with the audio stream: something Now playing does not show. */
        const val NEW_LINE_TEMPLATE = "{format}[ · {bitrate}][ · {samplerate}][ · {bits}]"

        fun defaultLines(type: BlockType): List<BoxLine> = when (type) {
            BlockType.TITLE -> BoxLine.TITLE_BOX
            BlockType.LIST -> BoxLine.LIST_BOX
            else -> emptyList()
        }
    }
}

/**
 * How Now playing is laid out: its parts from top to bottom. In landscape the cover and the list
 * box go to the left and the rest to the right, in the same order.
 */
data class PlayerLayout(val blocks: List<PlayerBlock>) {

    fun toJson(): JSONArray = JSONArray(blocks.map { it.toJson() })

    /** Every part, also those inside boxes. */
    val allBlocks: List<PlayerBlock>
        get() = blocks.flatMap { it.flatten() }

    /**
     * The landscape version of this layout as Attacca makes it by itself: two columns without a
     * frame, the cover and the list box (also a box holding it) at the left (42 %), the rest at the
     * right. A starting point for a landscape layout of one's own.
     */
    fun toLandscape(): PlayerLayout {
        val side = landscapeSide
        if (side.none { it.type == BlockType.COVER }) return this
        val rest = blocks - side.toSet()
        fun column(parts: List<PlayerBlock>, width: Int) = PlayerBlock(BlockType.BOX, children = parts, framed = false, width = width)
        return PlayerLayout(
            listOf(
                PlayerBlock(
                    BlockType.BOX,
                    framed = false,
                    horizontal = true,
                    children = listOfNotNull(column(side, 42), rest.takeIf { it.isNotEmpty() }?.let { column(it, 58) }),
                ),
            ),
        )
    }

    /** The parts the landscape version puts at the left: the cover and the list, or boxes holding them. */
    val landscapeSide: List<PlayerBlock>
        get() = blocks.filter { block -> block.flatten().any { it.type == BlockType.COVER || it.type == BlockType.LIST } }

    /** Whether some button plays and pauses (else only the cover gestures and the notification do). */
    val hasPlayButton: Boolean
        get() = allBlocks.any { PlayerAction.PLAY_PAUSE in it.actions }

    companion object {
        const val MAX_BUTTONS = 7

        /** Parts that appear once at most. */
        val SINGLE = setOf(BlockType.COVER, BlockType.TITLE, BlockType.LIST, BlockType.PROGRESS)

        val TRANSPORT = listOf(
            PlayerAction.SHUFFLE, PlayerAction.PREVIOUS_ALBUM, PlayerAction.PREVIOUS, PlayerAction.PLAY_PAUSE,
            PlayerAction.NEXT, PlayerAction.NEXT_ALBUM, PlayerAction.REPEAT,
        )
        val TOOLS = listOf(
            PlayerAction.NAVIGATOR, PlayerAction.BOOKMARK, PlayerAction.SLEEP, PlayerAction.QUICK_LIST, PlayerAction.FAVORITE,
            PlayerAction.SOUND, PlayerAction.ORAMA,
        )

        /** The layout Attacca has always had. */
        /** The list box between the two rows of buttons, near the thumb (landscape puts it by the cover). */
        val DEFAULT = PlayerLayout(
            listOf(
                PlayerBlock(BlockType.COVER),
                PlayerBlock(BlockType.TITLE),
                PlayerBlock(BlockType.PROGRESS),
                PlayerBlock(BlockType.BUTTONS, actions = TRANSPORT),
                PlayerBlock(BlockType.LIST),
                PlayerBlock(BlockType.BUTTONS, actions = TOOLS),
            ),
        )

        fun fromJson(array: JSONArray?): PlayerLayout {
            if (array == null) return DEFAULT
            val seen = mutableSetOf<BlockType>()
            val blocks = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { PlayerBlock.fromJson(it, seen) }
            }
            return if (blocks.isEmpty()) DEFAULT else PlayerLayout(blocks)
        }
    }
}

/**
 * A line of information: text with `{field}` for values of the playing track, and `[…]` around a
 * part that is left out when a field inside it has no value. `{tag:KEY}` is any tag of the file by
 * its own key. For example `{codec}[ · {bitrate} kbps][ · {loudness} LUFS]`.
 */
object InfoTemplate {

    /** The fields offered in the editor, in the order shown. */
    val FIELDS = listOf(
        "title", "artist", "album", "albumartist", "track", "disc", "year", "genre", "composer", "comment",
        "format", "codec", "bitrate", "samplerate", "bits", "channels", "duration",
        "filename", "folder", "size",
        "list", "position", "count", "next", "nextartist",
        "loudness", "gain", "bpm", "mood", "family",
        "plays", "lastplayed",
    )

    fun render(template: String, value: (String) -> String?): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '[') {
                val end = template.indexOf(']', i + 1)
                if (end < 0) {
                    out.append(fill(template.substring(i + 1), value) ?: "")
                    break
                }
                fill(template.substring(i + 1, end), value)?.let(out::append)
                i = end + 1
            } else {
                val next = template.indexOf('[', i).let { if (it < 0) template.length else it }
                out.append(fill(template.substring(i, next), value, optional = false))
                i = next
            }
        }
        return out.toString().trim()
    }

    /** [part] with its fields filled; null when [optional] and a field has no value. */
    private fun fill(part: String, value: (String) -> String?, optional: Boolean = true): String? {
        val out = StringBuilder()
        var i = 0
        while (i < part.length) {
            val open = part.indexOf('{', i)
            val close = if (open < 0) -1 else part.indexOf('}', open + 1)
            if (open < 0 || close < 0) {
                out.append(part, i, part.length)
                break
            }
            out.append(part, i, open)
            val v = value(part.substring(open + 1, close).trim())?.takeIf { it.isNotBlank() }
            if (v == null && optional) return null
            out.append(v.orEmpty())
            i = close + 1
        }
        return out.toString()
    }
}
