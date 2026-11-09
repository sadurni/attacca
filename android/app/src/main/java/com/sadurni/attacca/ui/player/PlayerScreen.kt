package com.sadurni.attacca.ui.player

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.AttaccaShapes
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Tag
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import com.sadurni.attacca.settings.BlockType
import com.sadurni.attacca.settings.BoxLine
import com.sadurni.attacca.settings.LineKind
import com.sadurni.attacca.ui.NowPlaying
import com.sadurni.attacca.settings.InfoTemplate
import com.sadurni.attacca.settings.PlayerAction
import com.sadurni.attacca.settings.PlayerBlock
import com.sadurni.attacca.settings.PlayerLayout
import com.sadurni.attacca.settings.TextSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.CoverAction
import com.sadurni.attacca.settings.CoverGesture
import com.sadurni.attacca.library.Lyrics
import com.sadurni.attacca.settings.ShuffleMode
import com.sadurni.attacca.settings.UiSettings
import com.sadurni.attacca.settings.WaveformMode
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.formatTime
import com.sadurni.attacca.ui.common.swipeNavigation
import com.sadurni.attacca.ui.common.tipAnchor
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class PlayerDialog { SLEEP, SPEED, ADD_TO_LIST, LIST_SETTINGS, MOOD, ORAMA }

/** Where the buttons and boxes of Now playing lead (to other screens and dialogs). */
private class PlayerHandlers(
    val onToggleRemainingTime: () -> Unit,
    val onOpenNavigator: () -> Unit,
    val onOpenSound: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenBookmarks: () -> Unit,
    val onOpenLists: () -> Unit,
    val onEditTags: (String) -> Unit,
    val onShowInfo: (String) -> Unit,
    val onGoToFolder: (String) -> Unit,
    val onCoverGesture: (CoverGesture) -> Unit,
    val onCoverLongPress: () -> Unit,
    val openDialog: (PlayerDialog) -> Unit,
    val toggleLyrics: () -> Unit,
    /** Opens Music on the track's album (false) or artist (true). */
    val onGoToMusic: (String, Boolean) -> Unit = { _, _ -> },
    /** Opens a list, on a track of it when given. */
    val onOpenList: (com.sadurni.attacca.library.ListSource, String?) -> Unit = { _, _ -> },
    /** The lists played last, newest first (for the navigator's menu). */
    val recentLists: () -> List<com.sadurni.attacca.library.ListSource> = { emptyList() },
)

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    uiSettings: UiSettings,
    onToggleRemainingTime: () -> Unit,
    onOpenNavigator: () -> Unit,
    onOpenSound: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenLists: () -> Unit,
    onEditTags: (String) -> Unit,
    onShowInfo: (String) -> Unit,
    onGoToFolder: (String) -> Unit,
    onGoToMusic: (String, Boolean) -> Unit = { _, _ -> },
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onOpenGestures: () -> Unit = {},
    onOpenList: (com.sadurni.attacca.library.ListSource, String?) -> Unit = { _, _ -> },
    recentLists: () -> List<com.sadurni.attacca.library.ListSource> = { emptyList() },
) {
    // Kept when the screen turns (O'rama is shown upright and wide).
    var dialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<PlayerDialog?>(null) }
    // The new year's show: O'rama opens by itself.
    val oramaAsked by viewModel.oramaRequest.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(oramaAsked) {
        if (oramaAsked) {
            dialog = PlayerDialog.ORAMA
            viewModel.oramaRequest.value = false
        }
    }
    // The cover over the whole screen (a tap on it, by default), for this file.
    var coverShown by remember { mutableStateOf<String?>(null) }
    coverShown?.let { id -> CoverViewer(id, onDismiss = { coverShown = null }) }
    val context = LocalContext.current
    val gestures by context.app.settings.gestures.collectAsStateWithLifecycle()
    val cardPager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val bookmarkAdded = stringResource(R.string.bookmark_added)

    val onCoverGesture: (CoverGesture) -> Unit = { gesture ->
        when (gestures[gesture] ?: CoverAction.NONE) {
            CoverAction.NONE -> Unit
            CoverAction.NEXT -> viewModel.next()
            CoverAction.PREVIOUS -> viewModel.previous()
            CoverAction.NEXT_ALBUM -> viewModel.nextGroup()
            CoverAction.GO_TO_END -> viewModel.skipToEnd()
            CoverAction.QUICK_LIST -> viewModel.quickSeedList(viewModel.nowPlaying.value.mediaId)
            CoverAction.RANDOM_LIST -> viewModel.requestRandomList()
            CoverAction.PREVIOUS_ALBUM -> viewModel.previousGroup()
            CoverAction.PLAY_PAUSE -> viewModel.playPause()
            CoverAction.OPEN_QUEUE -> onOpenQueue()
            CoverAction.OPEN_LISTS -> onOpenLists()
            CoverAction.OPEN_NAVIGATOR -> onOpenNavigator()
            CoverAction.OPEN_SOUND -> onOpenSound()
            CoverAction.TOGGLE_FAVORITE -> viewModel.toggleFavorite()
            CoverAction.ADD_BOOKMARK ->
                if (viewModel.addBookmark()) Toast.makeText(context, bookmarkAdded, Toast.LENGTH_SHORT).show()
            CoverAction.CYCLE_SHUFFLE -> viewModel.cycleShuffle()
            CoverAction.CYCLE_REPEAT -> viewModel.cycleRepeat()
            CoverAction.SLEEP_TIMER -> dialog = PlayerDialog.SLEEP
            CoverAction.SHOW_LYRICS -> scope.launch { cardPager.animateScrollToPage(1 - cardPager.currentPage) }
            CoverAction.GO_TO_FOLDER -> viewModel.nowPlaying.value.mediaId?.takeIf { !it.contains("://") }?.let(onGoToFolder)
            CoverAction.GO_TO_ALBUM -> viewModel.nowPlaying.value.mediaId?.takeIf { !it.contains("://") }?.let { onGoToMusic(it, false) }
            CoverAction.GO_TO_ARTIST -> viewModel.nowPlaying.value.mediaId?.takeIf { !it.contains("://") }?.let { onGoToMusic(it, true) }
            CoverAction.SHOW_COVER -> viewModel.nowPlaying.value.mediaId?.takeIf { !it.contains("://") }?.let { coverShown = it }
            CoverAction.OPEN_ORAMA -> dialog = PlayerDialog.ORAMA
        }
    }
    val handlers = PlayerHandlers(
        onToggleRemainingTime = onToggleRemainingTime,
        onOpenNavigator = onOpenNavigator,
        onOpenSound = onOpenSound,
        onOpenQueue = onOpenQueue,
        onOpenBookmarks = onOpenBookmarks,
        onOpenLists = onOpenLists,
        onEditTags = onEditTags,
        onShowInfo = onShowInfo,
        onGoToFolder = onGoToFolder,
        onCoverGesture = onCoverGesture,
        onCoverLongPress = onOpenGestures,
        openDialog = { dialog = it },
        toggleLyrics = { scope.launch { cardPager.animateScrollToPage(1 - cardPager.currentPage) } },
        onGoToMusic = onGoToMusic,
        onOpenList = onOpenList,
        recentLists = recentLists,
    )

    // General swipes; the cover, title card and seek bar keep their own gestures.
    Box(Modifier.fillMaxSize().swipeNavigation(onSwipeLeft, onSwipeRight)) {
        PlayerBody(
            viewModel = viewModel,
            layout = com.sadurni.attacca.ui.theme.LocalThemeSpec.current.layout,
            uiSettings = uiSettings,
            handlers = handlers,
            cardPager = cardPager,
            landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        )
    }

    Dialogs(viewModel, dialog, onOpenQueue) { dialog = null }
}

/**
 * Now playing as the theme in use lays it out, for the theme editor's preview: drawn at the size of
 * the screen and shrunk into the space given, with every button inert.
 */
@Composable
fun PlayerLayoutPreview(viewModel: PlayerViewModel, uiSettings: UiSettings, modifier: Modifier, landscape: Boolean = false) {
    val configuration = LocalConfiguration.current
    val short = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val long = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val width = if (landscape) long else short
    val height = if (landscape) short else long
    val none: () -> Unit = {}
    val handlers = PlayerHandlers(none, none, none, none, none, none, {}, {}, {}, {}, none, {}, none)
    val pager = rememberPagerState { 2 }
    BoxWithConstraints(modifier.clip(AttaccaShapes.medium), contentAlignment = Alignment.Center) {
        val scale = minOf(maxWidth / width, maxHeight / height)
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            PlayerBody(viewModel, com.sadurni.attacca.ui.theme.LocalThemeSpec.current.layout, uiSettings, handlers, pager, landscape = landscape, Modifier.fillMaxSize().padding(top = 24.dp, bottom = 16.dp))
            // Touches go nowhere: it is a picture of the screen.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        }
    }
}

/** The background of the theme, then the parts of [layout] (portrait: one column; landscape: two). */
@Composable
private fun PlayerBody(
    viewModel: PlayerViewModel,
    layout: PlayerLayout,
    uiSettings: UiSettings,
    handlers: PlayerHandlers,
    cardPager: PagerState,
    landscape: Boolean,
    modifier: Modifier,
) {
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val colors = LocalAttaccaColors.current
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // The theme's background: its own picture, or plain, a gradient from the accent, or the blurred cover.
        val theme = com.sadurni.attacca.ui.theme.LocalThemeSpec.current
        val picture = theme.backgroundOf(com.sadurni.attacca.settings.ThemeSection.PLAYER)
        val background = if (picture != null) com.sadurni.attacca.settings.PlayerBackground.PLAIN else theme.playerBackground
        com.sadurni.attacca.ui.theme.ThemeBackground(picture)
        if (background == com.sadurni.attacca.settings.PlayerBackground.GRADIENT) {
            val accent = LocalAccent.current
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), colors.background, colors.background)),
                ),
            )
        }
        val blurred = picture == null && (uiSettings.blurredBackground || background == com.sadurni.attacca.settings.PlayerBackground.BLURRED_COVER)
        if (blurred && artwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                bitmap = artwork!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.35f),
            )
        }

        // The information lines read the file only when one of them needs it.
        val needsFile = layout.allBlocks.any { block ->
            (block.type == BlockType.TEXT && readsFile(block.template)) ||
                block.lines.any { it.kind == LineKind.TEMPLATE && readsFile(it.template) }
        }
        val needsClasses = layout.allBlocks.any { block ->
            (block.type == BlockType.TEXT && needsClassification(block.template)) ||
                block.lines.any { it.kind == LineKind.TEMPLATE && needsClassification(it.template) }
        }
        val values = rememberTrackValues(viewModel, needsFile, needsClasses)
        val parts = Parts(viewModel, uiSettings, handlers, cardPager, values, compact = landscape)
        val side = layout.landscapeSide
        val ownLandscape = theme.landscape.takeIf { landscape }
        if (ownLandscape != null) {
            Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Blocks(ownLandscape.blocks, parts)
            }
        } else if (landscape && side.any { it.type == BlockType.COVER }) {
            // Left: cover and list box. Right: everything else.
            Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(0.42f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Blocks(side, parts)
                }
                Spacer(Modifier.width(16.dp))
                FitOrScroll(Modifier.weight(0.58f).fillMaxSize()) {
                    Blocks(layout.blocks - side.toSet(), parts, bounded = false)
                }
            }
        } else {
            KeepCover(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { fill ->
                Blocks(layout.blocks, parts, fillHeight = fill)
            }
        }
    }
}

/**
 * The portrait column, its cover (and empty space) sharing the room left over. On a short screen,
 * or with big text or display size, that room can shrink to nothing: then the whole column is drawn
 * smaller around a cover of [MIN_COVER], laid out wider by as much, so the cover stays a cover.
 */
@Composable
private fun KeepCover(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.(fill: androidx.compose.ui.unit.Dp?) -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier.then(Modifier.clipToBounds())) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val unbounded = androidx.compose.ui.unit.Constraints.Infinity
        if (height == unbounded) {
            val p = subcompose("free") { Column(horizontalAlignment = Alignment.CenterHorizontally) { content(MIN_COVER) } }.map { it.measure(constraints) }
            return@SubcomposeLayout layout(width, p.maxOfOrNull { it.height } ?: 0) { p.forEach { it.placeRelative(0, 0) } }
        }
        // How tall the column is with the cover at its least.
        val least = subcompose("least") { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content(MIN_COVER) } }
            .map { it.measure(androidx.compose.ui.unit.Constraints(width, width, 0, unbounded)) }
        val tall = least.maxOfOrNull { it.height } ?: 0
        if (tall <= height) {
            val p = subcompose("normal") { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) { content(null) } }
                .map { it.measure(androidx.compose.ui.unit.Constraints(width, width, height, height)) }
            return@SubcomposeLayout layout(width, height) { p.forEach { it.placeRelative(0, 0) } }
        }
        val scale = (height.toFloat() / tall).coerceAtLeast(0.5f)
        val wide = (width / scale).toInt()
        val p = subcompose("scaled") { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content(MIN_COVER) } }
            .map { it.measure(androidx.compose.ui.unit.Constraints(wide, wide, 0, unbounded)) }
        layout(width, height) {
            p.forEach {
                it.placeWithLayer((width - wide) / 2, (height - it.height) / 2) {
                    scaleX = scale
                    scaleY = scale
                }
            }
        }
    }
}

/** The least height [KeepCover] keeps for the cover before drawing the column smaller. */
private val MIN_COVER = 180.dp

/** What every part of Now playing needs to draw itself. */
private class Parts(
    val viewModel: PlayerViewModel,
    val uiSettings: UiSettings,
    val handlers: PlayerHandlers,
    val cardPager: PagerState,
    val values: (String) -> String?,
    val compact: Boolean,
)

/**
 * [blocks] one under the other. At the top level the cover and empty space share the room left
 * over (without either, the column is centred); inside a box ([nested]) everything takes just its
 * own height and the cover is square.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Blocks(
    blocks: List<PlayerBlock>,
    parts: Parts,
    nested: Boolean = false,
    /** The column has a height of its own to share out (the screen, or a box that grows). */
    bounded: Boolean = !nested,
    /** Instead of sharing out the room, the parts that grow take this height (see [KeepCover]). */
    fillHeight: androidx.compose.ui.unit.Dp? = null,
) {
    if (fillHeight != null && bounded) {
        blocks.forEachIndexed { i, block ->
            val fill = grows(block)
            val height = if (block.type == BlockType.SPACE) 0.dp else fillHeight
            Part(block, parts, if (fill) Modifier.height(height) else Modifier, nested, bounded = fill)
            if (i < blocks.lastIndex) Spacer(Modifier.height(gapAfter(block.type, parts.compact)))
        }
        return
    }
    val grows = bounded && blocks.any(::grows)
    if (bounded && !grows) Spacer(Modifier.weight(1f))
    blocks.forEachIndexed { i, block ->
        val fill = bounded && grows(block)
        Part(block, parts, if (fill) Modifier.weight(1f) else Modifier, nested, bounded = fill)
        if (i < blocks.lastIndex) Spacer(Modifier.height(gapAfter(block.type, parts.compact)))
    }
    if (bounded && !grows) Spacer(Modifier.weight(1f))
}

/** Parts that take the room left over: the cover, empty space and a box holding the cover. */
private fun grows(block: PlayerBlock) = when (block.type) {
    BlockType.COVER, BlockType.SPACE -> true
    BlockType.BOX -> block.flatten().any { it.type == BlockType.COVER }
    else -> false
}

/** One part of Now playing. */
@Composable
private fun Part(block: PlayerBlock, parts: Parts, modifier: Modifier, nested: Boolean, bounded: Boolean = false) {
    val viewModel = parts.viewModel
    val handlers = parts.handlers
    val compact = parts.compact
    when (block.type) {
        BlockType.COVER -> SlidingArtwork(
            viewModel, handlers.onCoverGesture, handlers.onCoverLongPress,
            modifier
                .fillMaxWidth()
                // With a height to fill, the cover fits it (square, as large as fits); else square at full width.
                .then(if (nested && !bounded) Modifier.aspectRatio(1f) else Modifier)
                .padding(horizontal = if (compact || nested) 4.dp else 12.dp, vertical = if (nested) 0.dp else 8.dp),
        )
        BlockType.TITLE -> TrackCard(viewModel, parts.cardPager, handlers, block.lines, parts.values, compact, block.framed, block.image, block.cover)
        BlockType.LIST -> PlaylistBar(viewModel, block.lines, parts.values, handlers.onOpenQueue, handlers.onOpenLists, block.framed, block.image, block.cover)
        BlockType.BOX -> Container(block, parts, modifier, bounded)
        BlockType.PROGRESS -> Progress(
            viewModel, parts.uiSettings, handlers.onToggleRemainingTime, seekBarHeight = if (compact || nested) 56.dp else 72.dp,
            onOpenOrama = { handlers.openDialog(PlayerDialog.ORAMA) },
        )
        BlockType.BUTTONS -> ButtonRow(viewModel, block.actions, handlers, block.icons)
        BlockType.TEXT -> InfoLine(block, parts.values)
        BlockType.SPACE -> Spacer(modifier.then(if (nested) Modifier.size(12.dp) else Modifier))
    }
}

/**
 * A box of the user's own: its parts one under the other or side by side (each with its share of
 * the width: its own %, the rest shared equally), in a frame or not.
 */
@Composable
private fun Container(block: PlayerBlock, parts: Parts, modifier: Modifier, bounded: Boolean = false) {
    val colors = LocalAttaccaColors.current
    // A frame or a picture gives the box its rounded shape and some room inside.
    val shaped = block.framed || block.image != null || block.cover != null
    val outer = modifier
        .fillMaxWidth()
        .then(if (shaped) Modifier.clip(AttaccaShapes.medium) else Modifier)
        .then(if (block.framed) Modifier.border(1.dp, colors.text.copy(alpha = BOX_BORDER_ALPHA), AttaccaShapes.medium) else Modifier)
    val frame = if (shaped) Modifier.padding(horizontal = 10.dp, vertical = 6.dp) else Modifier
    Box(outer) {
        // The picture takes the box's size and shape, cropped to fill it.
        block.image?.let { com.sadurni.attacca.ui.theme.ThemeBackground(it, Modifier.matchParentSize()) }
        block.cover?.let { BoxCover(parts.viewModel, it) }
        ContainerContent(block, parts, frame, bounded)
    }
}

@Composable
private fun ContainerContent(block: PlayerBlock, parts: Parts, frame: Modifier, bounded: Boolean) {
    val modifier = if (bounded) Modifier.fillMaxSize() else Modifier
    if (block.horizontal) {
        val children = block.children
        val auto = children.count { it.width == 0 }
        val share = if (auto == 0) 0f else ((100 - children.sumOf { it.width }).coerceAtLeast(auto * MIN_SHARE).toFloat() / auto)
        Row(
            modifier.fillMaxWidth().then(frame),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (child in children) {
                val weight = if (child.width > 0) child.width.toFloat() else share
                // With a height to fill, each side gets all of it (a cover or a box with the cover fills it).
                val tall = bounded && grows(child)
                val column = Modifier.weight(weight.coerceAtLeast(1f))
                if (bounded && !tall) {
                    // A side without the cover keeps its own height: it scrolls when it does not fit.
                    FitOrScroll(column.fillMaxHeight()) { Part(child, parts, Modifier, nested = true) }
                } else {
                    Column(
                        column.then(if (bounded) Modifier.fillMaxHeight() else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Part(child, parts, if (tall) Modifier.weight(1f) else Modifier, nested = true, bounded = tall)
                    }
                }
            }
        }
    } else {
        Column(modifier.fillMaxWidth().then(frame), horizontalAlignment = Alignment.CenterHorizontally) {
            Blocks(block.children, parts, nested = true, bounded = bounded)
        }
    }
}

/**
 * A column centred in [modifier]'s height. When its parts are taller (big text or display size, a
 * short screen), the whole column is drawn smaller, laid out wider by as much, so every part shows
 * whole instead of being cut; below [MIN_FIT_SCALE] it scrolls.
 */
@Composable
private fun FitOrScroll(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    androidx.compose.ui.layout.SubcomposeLayout(modifier.then(Modifier.clipToBounds())) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        fun column(slot: String, scrolls: Boolean) = subcompose(slot) {
            Column(
                Modifier.fillMaxWidth().then(if (scrolls) Modifier.verticalScroll(scroll) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
        val unbounded = androidx.compose.ui.unit.Constraints.Infinity
        val natural = column("natural", false).map { it.measure(androidx.compose.ui.unit.Constraints(width, width, 0, unbounded)) }
        val tall = natural.maxOfOrNull { it.height } ?: 0
        if (height == unbounded || tall <= height) {
            val h = if (height == unbounded) tall else height
            return@SubcomposeLayout layout(width, h) { natural.forEach { it.placeRelative(0, (h - it.height) / 2) } }
        }
        val fit = height.toFloat() / tall
        val scale = fit.coerceAtLeast(MIN_FIT_SCALE)
        // Laid out wider by the scale, so once drawn smaller it fills the width again.
        val wide = (width / scale).toInt()
        val placed = if (fit < MIN_FIT_SCALE) {
            // Too much even drawn smaller: it scrolls, at the smallest size.
            column("scrolled", true).map { it.measure(androidx.compose.ui.unit.Constraints(wide, wide, 0, (height / scale).toInt())) }
        } else {
            column("scaled", false).map { it.measure(androidx.compose.ui.unit.Constraints(wide, wide, 0, unbounded)) }
        }
        layout(width, height) {
            placed.forEach {
                // Scaled about its centre, so it ends up centred in the room.
                it.placeWithLayer((width - wide) / 2, (height - it.height) / 2) {
                    scaleX = scale
                    scaleY = scale
                }
            }
        }
    }
}

/** Smallest scale [FitOrScroll] draws a column at before it scrolls instead. */
private const val MIN_FIT_SCALE = 0.6f

/** Least share (%) of a part without a width of its own, side by side with others. */
private const val MIN_SHARE = 5

/** The room after each part (as Now playing always had). */
private fun gapAfter(type: BlockType, compact: Boolean) = when (type) {
    BlockType.COVER -> 8.dp
    BlockType.TITLE -> if (compact) 4.dp else 6.dp
    BlockType.LIST -> if (compact) 4.dp else 10.dp
    BlockType.PROGRESS -> if (compact) 0.dp else 8.dp
    BlockType.BUTTONS -> if (compact) 4.dp else 12.dp
    BlockType.TEXT, BlockType.BOX -> 6.dp
    BlockType.SPACE -> 0.dp
}

/** Font size and color of a text size; the small one is quieter. */
@Composable
private fun textStyle(size: TextSize): Pair<androidx.compose.ui.unit.TextUnit, Color> {
    val colors = LocalAttaccaColors.current
    return when (size) {
        TextSize.SMALL -> AttaccaType.secondary to colors.textGrayed
        TextSize.MEDIUM -> AttaccaType.heading to colors.text
        TextSize.LARGE -> AttaccaType.large to colors.text
        TextSize.HUGE -> AttaccaType.display to colors.text
    }
}

/** A line of information from its template; nothing when it comes out empty. */
@Composable
private fun InfoLine(block: PlayerBlock, values: (String) -> String?) {
    val text = InfoTemplate.render(block.template, values)
    if (text.isEmpty()) return
    val colors = LocalAttaccaColors.current
    val (size, color) = textStyle(block.size)
    Text(
        text,
        fontSize = size,
        color = color,
        fontWeight = if (block.boxed || block.size == TextSize.HUGE) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (block.boxed) {
                    Modifier
                        .clip(AttaccaShapes.medium)
                        .border(1.dp, colors.text.copy(alpha = BOX_BORDER_ALPHA), AttaccaShapes.medium)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                } else {
                    Modifier.padding(horizontal = 8.dp)
                },
            ),
    )
}

@Composable
private fun Dialogs(viewModel: PlayerViewModel, dialog: PlayerDialog?, onOpenQueue: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val listSettings by viewModel.listSettings.collectAsStateWithLifecycle()
    val addedMessage = stringResource(R.string.added_to_list)
    when (dialog) {
        PlayerDialog.ORAMA -> com.sadurni.attacca.ui.orama.OramaScreen(viewModel, onDismiss) {
            onDismiss()
            onOpenQueue()
        }
        PlayerDialog.SLEEP -> SleepTimerDialog(
            timer = sleepTimer,
            lastChoice = viewModel.lastSleepChoice,
            onStart = { minutes ->
                viewModel.extras?.startSleepTimer(minutes)
                onDismiss()
            },
            onStartAt = { hour, minute ->
                viewModel.extras?.startSleepTimerAt(hour, minute)
                onDismiss()
            },
            onEndOf = { end ->
                viewModel.extras?.sleepAtEndOf(end)
                onDismiss()
            },
            onCancel = {
                viewModel.extras?.cancelSleepTimer()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        PlayerDialog.MOOD -> viewModel.nowPlaying.value.mediaId?.takeIf { !it.contains("://") }
            ?.let { com.sadurni.attacca.ui.chain.MoodDialog(it, onDismiss) }
        PlayerDialog.SPEED -> SpeedDialog(
            speed, { viewModel.extras?.setSpeed(it) }, onDismiss,
            pitch = viewModel.pitch.collectAsStateWithLifecycle().value, onPitch = { viewModel.extras?.setPitch(it) },
        )
        PlayerDialog.ADD_TO_LIST -> AddToListDialog(
            onPick = { file ->
                viewModel.addToList(file)
                Toast.makeText(context, addedMessage.format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        PlayerDialog.LIST_SETTINGS -> ListSettingsDialog(listSettings, { viewModel.extras?.updateListSettings(it) }, onDismiss)
        null -> Unit
    }
}

/** A recent "previous" from the user means the new track came from the left. */
private const val BACKWARD_WINDOW_MS = 2_000L

private fun cameFromBehind(viewModel: PlayerViewModel): Boolean =
    android.os.SystemClock.elapsedRealtime() - viewModel.lastBackwardSkipAt < BACKWARD_WINDOW_MS

/**
 * Slides the new content in from the right on a track change (from the left after going back),
 * and only cross-fades when the same track's content changes.
 */
private fun <T> trackTransition(
    viewModel: PlayerViewModel,
    sameTrack: (T, T) -> Boolean,
): androidx.compose.animation.AnimatedContentTransitionScope<T>.() -> androidx.compose.animation.ContentTransform = {
    if (sameTrack(initialState, targetState)) {
        androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
    } else {
        val sign = if (cameFromBehind(viewModel)) -1 else 1
        (androidx.compose.animation.slideInHorizontally { sign * it } + androidx.compose.animation.fadeIn()) togetherWith
            (androidx.compose.animation.slideOutHorizontally { -sign * it } + androidx.compose.animation.fadeOut())
    }
}

/** The cover, slid sideways when the track changes (once the new cover is ready). */
@Composable
private fun SlidingArtwork(viewModel: PlayerViewModel, onGesture: (CoverGesture) -> Unit, onLongPress: () -> Unit, modifier: Modifier) {
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    androidx.compose.animation.AnimatedContent(
        targetState = cover,
        transitionSpec = trackTransition(viewModel) { a, b -> a.mediaId == b.mediaId },
        modifier = modifier,
        label = "cover",
    ) { c ->
        Artwork(c.bitmap, onGesture, onLongPress, Modifier.fillMaxSize())
    }
}

/**
 * The cover, with the configurable gestures: swipes in four directions and double taps near each
 * edge (whichever edge the tap is closest to). A long press opens the settings of these gestures.
 */
@Composable
private fun Artwork(bitmap: Bitmap?, onGesture: (CoverGesture) -> Unit, onLongPress: () -> Unit, modifier: Modifier) {
    val colors = LocalAttaccaColors.current
    val gesture by rememberUpdatedState(onGesture)
    val longPress by rememberUpdatedState(onLongPress)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val square = Modifier
            .size(minOf(maxWidth, maxHeight))
            .tipAnchor("player.cover")
            .clip(AttaccaShapes.large)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { longPress() }, onTap = { gesture(CoverGesture.TAP) }, onDoubleTap = { offset ->
                    val dx = offset.x / size.width - 0.5f
                    val dy = offset.y / size.height - 0.5f
                    gesture(
                        when {
                            abs(dy) >= abs(dx) && dy < 0 -> CoverGesture.DOUBLE_TAP_TOP
                            abs(dy) >= abs(dx) -> CoverGesture.DOUBLE_TAP_BOTTOM
                            dx < 0 -> CoverGesture.DOUBLE_TAP_LEFT
                            else -> CoverGesture.DOUBLE_TAP_RIGHT
                        },
                    )
                })
            }
            .pointerInput(Unit) {
                var total = Offset.Zero
                val threshold = 56.dp.toPx()
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        total += amount
                    },
                    onDragEnd = {
                        val (x, y) = total
                        when {
                            abs(x) < threshold && abs(y) < threshold -> Unit
                            abs(x) >= abs(y) -> gesture(if (x < 0) CoverGesture.SWIPE_LEFT else CoverGesture.SWIPE_RIGHT)
                            else -> gesture(if (y < 0) CoverGesture.SWIPE_UP else CoverGesture.SWIPE_DOWN)
                        }
                    },
                )
            }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = square,
            )
        } else {
            Box(square.background(colors.surface), contentAlignment = Alignment.Center) {
                com.sadurni.attacca.ui.theme.ThemedIcon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = colors.textGrayed,
                    modifier = Modifier.size(96.dp),
                    slot = com.sadurni.attacca.settings.AppIcon.NO_COVER,
                )
            }
        }
    }
}

/** The title box: its lines (title, artist, album… by default), with the lyrics as a second page (swipe). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(
    viewModel: PlayerViewModel,
    pager: PagerState,
    handlers: PlayerHandlers,
    lines: List<BoxLine>,
    values: (String) -> String?,
    compact: Boolean,
    framed: Boolean = true,
    image: com.sadurni.attacca.settings.ThemeImage? = null,
    cover: com.sadurni.attacca.settings.CoverBackground? = null,
) {
    // As tall as its lines (the lyrics page needs a fixed height); the usual three lines make 132 dp.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val height = ((lines.sumOf { lineHeight(it).toDouble() }.toFloat() * fontScale + 24f) * (if (compact) 0.88f else 1f))
        .coerceAtLeast(56f).dp
    val onShowInfo = handlers.onShowInfo
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .then(if (framed) Modifier.border(1.dp, LocalAttaccaColors.current.text.copy(alpha = BOX_BORDER_ALPHA), AttaccaShapes.medium) else Modifier)
            .clip(AttaccaShapes.medium),
    ) {
        image?.let { com.sadurni.attacca.ui.theme.ThemeBackground(it, Modifier.matchParentSize()) }
        cover?.let { BoxCover(viewModel, it) }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) androidx.compose.animation.AnimatedContent(
                targetState = now,
                transitionSpec = trackTransition(viewModel) { a, b -> a.mediaId == b.mediaId },
                contentKey = { it.mediaId },
                modifier = Modifier.fillMaxSize(),
                label = "title",
            ) { now ->
                // A tap opens everything known about the file, a long press its album in Music (not for streams).
                val localFile = now.mediaId?.takeIf { !it.contains("://") }
                Column(
                    Modifier
                        .fillMaxSize()
                        .tipAnchor("player.title")
                        .combinedClickable(
                            enabled = localFile != null,
                            onClick = { localFile?.let(onShowInfo) },
                            onLongClick = { localFile?.let { handlers.onGoToMusic(it, false) } },
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (now.mediaId == null) {
                        Text(stringResource(R.string.no_track), fontSize = AttaccaType.display, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(stringResource(R.string.no_track_hint), fontSize = AttaccaType.large, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    } else {
                        BoxLines(now, lines, values, viewModel, centered = true)
                    }
                }
            } else {
                LyricsPage(viewModel, lyrics)
            }
        }
        TrackMenu(
            viewModel = viewModel,
            onLyrics = { scope.launch { pager.animateScrollToPage(1) } },
            onEditTags = { now.mediaId?.takeIf { !it.contains("://") }?.let(handlers.onEditTags) },
            onShowInfo = { now.mediaId?.takeIf { !it.contains("://") }?.let(onShowInfo) },
            onGoToFolder = { now.mediaId?.takeIf { !it.contains("://") }?.let(handlers.onGoToFolder) },
            onGoToMusic = { artist -> now.mediaId?.takeIf { !it.contains("://") }?.let { handlers.onGoToMusic(it, artist) } },
            openDialog = handlers.openDialog,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun LyricsPage(viewModel: PlayerViewModel, lyrics: Lyrics?) {
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    if (lyrics == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_lyrics), color = colors.textGrayed, fontSize = AttaccaType.heading)
        }
        return
    }
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    // LRC times are file positions; the player's position starts at the clip.
    val current = lyrics.currentLine(now.clipStartMs + position)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 8.dp)) {
        itemsIndexed(lyrics.lines) { index, line ->
            Text(
                line.text,
                fontSize = AttaccaType.heading,
                textAlign = TextAlign.Center,
                fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                color = if (index == current) accent else colors.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrackMenu(
    viewModel: PlayerViewModel,
    onLyrics: () -> Unit,
    onEditTags: () -> Unit,
    onShowInfo: () -> Unit,
    onGoToFolder: () -> Unit,
    onGoToMusic: (artist: Boolean) -> Unit,
    openDialog: (PlayerDialog) -> Unit,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var sets by remember { mutableStateOf(false) }
    if (sets) com.sadurni.attacca.ui.chain.InclusionSetDialog { sets = false }
    val ab by viewModel.abRepeat.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    Box(modifier) {
        Icon(
            Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.action_track_menu),
            modifier = Modifier.clip(CircleShape).clickable { open = true }.padding(8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val abLabel = when {
                ab.aMs == null -> stringResource(R.string.ab_set_a)
                ab.bMs == null -> stringResource(R.string.ab_set_b, formatTime(ab.aMs!!))
                else -> stringResource(R.string.ab_clear, formatTime(ab.aMs!!), formatTime(ab.bMs!!))
            }
            DropdownMenuItem(text = { Text(abLabel) }, onClick = {
                viewModel.extras?.toggleAbRepeat()
                open = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.speed_menu, "%.2f×".format(speed))) }, onClick = {
                open = false
                openDialog(PlayerDialog.SPEED)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_to_list)) }, onClick = {
                open = false
                openDialog(PlayerDialog.ADD_TO_LIST)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.seed_list_title)) }, onClick = {
                open = false
                viewModel.nowPlaying.value.mediaId?.let(viewModel::requestSeedList)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.quick_list)) }, onClick = {
                open = false
                viewModel.quickSeedList(viewModel.nowPlaying.value.mediaId)
            })
            val shareContext = androidx.compose.ui.platform.LocalContext.current
            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                open = false
                viewModel.nowPlaying.value.mediaId?.let { com.sadurni.attacca.ui.common.ShareTracks.share(shareContext, listOf(it)) }
            })
            DropdownMenuItem(text = { Text(com.sadurni.attacca.ui.browser.inclusionMenuLabel()) }, onClick = {
                open = false
                viewModel.nowPlaying.value.mediaId?.let(viewModel::requestInclusion)
            })
            if (viewModel.nowPlaying.value.mediaId?.let { !it.contains("://") && it.endsWith(".mp3", ignoreCase = true) } == true) {
                DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair)) }, onClick = {
                    open = false
                    viewModel.nowPlaying.value.mediaId?.let { viewModel.requestRepair(listOf(it)) }
                })
            }
            // Which inclusion set lists keep to now (with whom the music is heard).
            DropdownMenuItem(text = { Text(stringResource(R.string.inclusion_set_menu, com.sadurni.attacca.ui.chain.activeSetName())) }, onClick = {
                open = false
                sets = true
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.list_settings)) }, onClick = {
                open = false
                openDialog(PlayerDialog.LIST_SETTINGS)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.lyrics)) }, onClick = {
                open = false
                onLyrics()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.orama_name)) }, onClick = {
                open = false
                openDialog(PlayerDialog.ORAMA)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.tags_title)) }, onClick = {
                open = false
                onEditTags()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.info_title)) }, onClick = {
                open = false
                onShowInfo()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.mood_menu)) }, onClick = {
                open = false
                openDialog(PlayerDialog.MOOD)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.go_to_folder)) }, onClick = {
                open = false
                onGoToFolder()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.go_to_end)) }, onClick = {
                open = false
                viewModel.skipToEnd()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.go_to_album)) }, onClick = {
                open = false
                onGoToMusic(false)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.go_to_artist)) }, onClick = {
                open = false
                onGoToMusic(true)
            })
        }
    }
}

/** The list box: its lines (the list's name and details by default); a tap opens the list, a long press Lists. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistBar(
    viewModel: PlayerViewModel,
    lines: List<BoxLine>,
    values: (String) -> String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    framed: Boolean = true,
    image: com.sadurni.attacca.settings.ThemeImage? = null,
    cover: com.sadurni.attacca.settings.CoverBackground? = null,
) {
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(AttaccaShapes.medium)
            .then(if (framed) Modifier.border(1.dp, LocalAttaccaColors.current.text.copy(alpha = BOX_BORDER_ALPHA), AttaccaShapes.medium) else Modifier)
            .tipAnchor("player.list")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        image?.let { com.sadurni.attacca.ui.theme.ThemeBackground(it, Modifier.matchParentSize()) }
        cover?.let { BoxCover(viewModel, it) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            BoxLines(now, lines, values, viewModel, centered = false)
        }
    }
}

/** The playing track's cover behind a box, taking the box's size. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.BoxCover(viewModel: PlayerViewModel, style: com.sadurni.attacca.settings.CoverBackground) {
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    com.sadurni.attacca.ui.theme.CoverBackdrop(artwork, style, Modifier.matchParentSize())
}

/** Room a line of the title box takes, dp at the usual text size. */
private fun lineHeight(line: BoxLine): Float = when {
    line.size != BoxLine.defaultSize(line.kind) || line.kind == LineKind.TEMPLATE -> when (line.size) {
        TextSize.SMALL -> if (line.kind == LineKind.LIST_DETAILS) 24f else 20f
        TextSize.MEDIUM -> 26f
        TextSize.LARGE -> 34f
        TextSize.HUGE -> 40f
    }
    line.kind == LineKind.TITLE -> 40f
    line.kind == LineKind.LIST_NAME -> 22f
    line.kind == LineKind.LIST_DETAILS -> 24f
    line.kind == LineKind.NEXT -> 22f
    else -> 34f
}

/** The font size of a line's [TextSize]. */
private fun fontSizeOf(size: TextSize): androidx.compose.ui.unit.TextUnit = when (size) {
    TextSize.SMALL -> AttaccaType.secondary
    TextSize.MEDIUM -> AttaccaType.heading
    TextSize.LARGE -> AttaccaType.large
    TextSize.HUGE -> AttaccaType.display
}

/**
 * The lines of a box for the track [now]. Title, artist and album keep their icon at the left edge
 * and are centred; the list's lines start at the left; template lines are centred in the title box.
 * A line with nothing to show is left out.
 */
@Composable
private fun BoxLines(
    now: NowPlaying,
    lines: List<BoxLine>,
    values: (String) -> String?,
    viewModel: PlayerViewModel,
    centered: Boolean,
    /** Template lines keep clear of the icons and the menu (in the title box). */
    inset: Boolean = true,
) {
    val localFile = now.mediaId?.takeIf { !it.contains("://") }
    for (line in lines) {
        when (line.kind) {
            LineKind.TITLE -> TitleLine(
                Icons.Rounded.MusicNote,
                stringResource(R.string.tag_title),
                mark = {
                    localFile?.let {
                        com.sadurni.attacca.ui.common.DamagedMark(it, Modifier.padding(start = 2.dp))
                        com.sadurni.attacca.ui.common.ExcludedMark(it, Modifier.padding(start = 2.dp))
                    }
                },
            ) { modifier ->
                Text(
                    text = now.title,
                    fontSize = fontSizeOf(line.size),
                    fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = modifier.basicMarquee(),
                )
            }
            LineKind.ARTIST -> now.artist?.takeIf { it.isNotEmpty() }?.let {
                TitleLine(Icons.Rounded.Person, stringResource(R.string.tag_artist)) { modifier ->
                    Text(it, fontSize = fontSizeOf(line.size), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = modifier)
                }
            }
            LineKind.ALBUM -> now.album?.takeIf { it.isNotEmpty() }?.let {
                TitleLine(Icons.Rounded.Album, stringResource(R.string.tag_album)) { modifier ->
                    Text(it, fontSize = fontSizeOf(line.size), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = modifier)
                }
            }
            LineKind.LIST_NAME -> if (now.count > 0) {
                val listName by viewModel.listName.collectAsStateWithLifecycle()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    InfoIcon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.playlist_label))
                    Text(listName, fontSize = fontSizeOf(line.size), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // How many times this track was played, at the right (as on a track in Music); none before the first.
                    values("plays")?.let { plays ->
                        val description = stringResource(R.string.info_plays)
                        Row(
                            Modifier.padding(start = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$description: $plays" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = LocalAttaccaColors.current.textGrayed, modifier = Modifier.size(16.dp))
                            Text(plays, fontSize = fontSizeOf(line.size), color = LocalAttaccaColors.current.textGrayed, maxLines = 1)
                        }
                    }
                }
            }
            LineKind.LIST_DETAILS -> ListDetails(now, fontSizeOf(line.size))
            LineKind.NEXT -> now.nextTitle?.let { next ->
                val text = listOfNotNull(next, now.nextArtist?.takeIf { it.isNotEmpty() }).joinToString(" · ")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    InfoIcon(Icons.Rounded.SkipNext, stringResource(R.string.line_next))
                    Text(text, fontSize = fontSizeOf(line.size), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
                }
            }
            LineKind.TEMPLATE -> {
                val text = InfoTemplate.render(line.template, values)
                if (text.isNotEmpty()) {
                    val (size, color) = textStyle(line.size)
                    Text(
                        text,
                        fontSize = size,
                        color = color,
                        fontWeight = if (line.size == TextSize.HUGE) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // In the title box, clear of the icons and the menu like the other lines.
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (centered && inset) 32.dp else 0.dp),
                    )
                }
            }
        }
    }
}

/** Position in the list, then what the tags say about the track, each behind its icon. */
@Composable
private fun ListDetails(now: NowPlaying, fontSize: androidx.compose.ui.unit.TextUnit = AttaccaType.secondary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (now.count > 0) {
            InfoIcon(Icons.Rounded.FormatListNumbered, null)
            Text("${now.index + 1}/${now.count}", fontSize = fontSize, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        // The genre gives way first.
        now.trackNumber?.let {
            InfoIcon(Icons.Rounded.Tag, stringResource(R.string.tag_track), Modifier.padding(start = 12.dp))
            Text("$it", fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        now.date?.let {
            InfoIcon(Icons.Rounded.CalendarMonth, stringResource(R.string.tag_year), Modifier.padding(start = 12.dp))
            Text(it, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        now.genre?.takeIf { it.isNotBlank() }?.let {
            InfoIcon(Icons.Rounded.Style, stringResource(R.string.tag_genre), Modifier.padding(start = 12.dp))
            Text(it, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small icon in front of a piece of information. */
@Composable
private fun InfoIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String?, modifier: Modifier = Modifier) {
    Icon(
        icon,
        contentDescription = description,
        tint = LocalAttaccaColors.current.textGrayed,
        modifier = modifier.padding(end = 4.dp).size(16.dp),
    )
}

/**
 * A line of the title box with its icon at the left edge; the text keeps the same clearance on
 * both sides (room for the icon and for the menu button) so it is centred on the whole box.
 */
@Composable
private fun TitleLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    mark: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = description,
                tint = LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.size(20.dp),
            )
            mark()
        }
        content(Modifier.padding(horizontal = 32.dp))
    }
}

@Composable
private fun Progress(
    viewModel: PlayerViewModel,
    uiSettings: UiSettings,
    onToggleRemainingTime: () -> Unit,
    seekBarHeight: androidx.compose.ui.unit.Dp,
    onOpenOrama: () -> Unit = {},
) {
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val ab by viewModel.abRepeat.collectAsStateWithLifecycle()
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    val duration = now.durationMs
    // Times count over the whole file: the skipped silences and the crossfade are part of the
    // track, so when the next one takes over the counter shows what was left of the file.
    // Without the analysis (or for a clip of a playlist) they count to the end of the crossfade.
    val fileDuration = waveform?.fileDurationMs?.takeIf { it > 0 && !now.explicitClip }
    val shownDuration = when {
        fileDuration != null -> fileDuration
        duration > 0 -> duration + now.crossfadeMs
        else -> 0L
    }
    val shownPosition = if (fileDuration != null) (now.clipStartMs + position).coerceAtMost(fileDuration) else position
    val thumb = if (colors.isDark) Color.White else colors.text
    val barModifier = Modifier.fillMaxWidth().height(seekBarHeight)

    val strip = uiSettings.oramaStrip
    val wave = waveform.takeIf { uiSettings.waveformMode != WaveformMode.OFF }
    if (strip) {
        // A strip of O'rama, wide and low; the waveform goes small between the times below.
        // Lower when the phone lies on its side, so the title above and the buttons below keep their room.
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Box(Modifier.fillMaxWidth()) {
            com.sadurni.attacca.ui.orama.OramaStrip(
                viewModel,
                Modifier.fillMaxWidth().aspectRatio(if (wide) 6f else 3.6f).clip(com.sadurni.attacca.ui.theme.AttaccaShapes.small),
                onOpenOrama,
            )
            StripSwitch(toOrama = false, Modifier.align(Alignment.TopEnd))
        }
        val timeText = if (uiSettings.showRemainingTime && shownDuration > 0) "-" + formatTime(shownDuration - shownPosition) else formatTime(shownPosition)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(timeText, fontSize = AttaccaType.title, modifier = Modifier.clickable(onClick = onToggleRemainingTime))
            val small = Modifier.weight(1f).padding(horizontal = 10.dp).height(32.dp)
            if (wave != null && wave.fileDurationMs > 0) {
                val fileLength = wave.fileDurationMs.toFloat()
                SeekBar(
                    progress = (wave.clipStartMs + position) / fileLength,
                    accent = accent,
                    unplayed = colors.text,
                    thumb = thumb,
                    onSeek = { fraction ->
                        val target = (fraction * fileLength).toLong() - wave.clipStartMs
                        viewModel.seekTo(target.coerceIn(0L, duration.coerceAtLeast(0L)))
                    },
                    modifier = small,
                    waveform = wave.waveform,
                    mirrored = uiSettings.waveformMode == WaveformMode.MIRRORED,
                    activeFrom = wave.clipStartMs / fileLength,
                    activeTo = wave.clipEndMs / fileLength,
                )
            } else {
                SeekBar(
                    progress = if (duration > 0) position.toFloat() / duration else 0f,
                    accent = accent,
                    unplayed = colors.text,
                    thumb = thumb,
                    onSeek = { fraction -> viewModel.seekTo((fraction * duration).toLong()) },
                    modifier = small,
                )
            }
            if (ab.aMs != null) Text(if (ab.bMs != null) "A-B " else "A- ", fontSize = AttaccaType.body, fontWeight = FontWeight.Bold, color = accent)
            Text(formatTime(shownDuration), fontSize = AttaccaType.title)
        }
        return
    }
    Box(Modifier.fillMaxWidth()) {
    if (wave != null && wave.fileDurationMs > 0) {
        // The waveform spans the whole file; the player's timeline is only the clip.
        val fileDuration = wave.fileDurationMs.toFloat()
        SeekBar(
            progress = (wave.clipStartMs + position) / fileDuration,
            accent = accent,
            unplayed = colors.text,
            thumb = thumb,
            onSeek = { fraction ->
                val target = (fraction * fileDuration).toLong() - wave.clipStartMs
                viewModel.seekTo(target.coerceIn(0L, duration.coerceAtLeast(0L)))
            },
            modifier = barModifier,
            waveform = wave.waveform,
            mirrored = uiSettings.waveformMode == WaveformMode.MIRRORED,
            activeFrom = wave.clipStartMs / fileDuration,
            activeTo = wave.clipEndMs / fileDuration,
        )
    } else {
        SeekBar(
            progress = if (duration > 0) position.toFloat() / duration else 0f,
            accent = accent,
            unplayed = colors.text,
            thumb = thumb,
            onSeek = { fraction -> viewModel.seekTo((fraction * duration).toLong()) },
            modifier = barModifier,
        )
    }
        StripSwitch(toOrama = true, Modifier.align(Alignment.TopEnd))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (uiSettings.showRemainingTime && shownDuration > 0) "-" + formatTime(shownDuration - shownPosition) else formatTime(shownPosition),
            fontSize = AttaccaType.title,
            modifier = Modifier.clickable(onClick = onToggleRemainingTime),
        )
        Spacer(Modifier.weight(1f))
        if (ab.aMs != null) {
            Text(if (ab.bMs != null) "A-B" else "A-", fontSize = AttaccaType.body, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.weight(1f))
        }
        Text(formatTime(shownDuration), fontSize = AttaccaType.title)
    }
}

/** The small round button in the corner of the waveform or the strip of O'rama: one for the other. */
@Composable
private fun StripSwitch(toOrama: Boolean, modifier: Modifier) {
    val settings = androidx.compose.ui.platform.LocalContext.current.app.settings
    val description = stringResource(if (toOrama) R.string.strip_show_orama else R.string.strip_show_wave)
    Box(
        modifier
            .padding(4.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { settings.updateUi { it.copy(oramaStrip = toOrama) } }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (toOrama) Icons.Rounded.Landscape else Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A row of buttons. The play button is larger; on narrow screens (or a larger display size) the
 * buttons shrink instead of running off the edge, so the row stays centred.
 */
@Composable
private fun ButtonRow(
    viewModel: PlayerViewModel,
    actions: List<PlayerAction>,
    handlers: PlayerHandlers,
    icons: Map<PlayerAction, com.sadurni.attacca.settings.ThemeImage> = emptyMap(),
) {
    val hasPlay = PlayerAction.PLAY_PAUSE in actions
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val needed = TOOL_SIZE * (actions.size - if (hasPlay) 1 else 0) + if (hasPlay) PLAY_SIZE else 0.dp
        val scale = (maxWidth / needed).coerceAtMost(1f)
        Row(
            Modifier.fillMaxWidth(),
            // Few buttons spread around the middle; a full row reaches both edges.
            horizontalArrangement = if (actions.size >= 5) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (action in actions) {
                // The theme's own icon for this button, if it has one.
                androidx.compose.runtime.CompositionLocalProvider(LocalButtonImage provides icons[action]) {
                    PlayerButton(viewModel, action, handlers, scale)
                }
            }
        }
    }
}

@Composable
private fun PlayerButton(viewModel: PlayerViewModel, action: PlayerAction, handlers: PlayerHandlers, scale: Float) {
    val context = LocalContext.current
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val size = TOOL_SIZE * scale
    val local = now.mediaId?.takeIf { !it.contains("://") }
    val description = actionName(action)
    // Long press: the button's name, and for some a short menu of related actions.
    val extras = viewModel.extras
    fun entry(label: String, run: () -> Unit) = ButtonMenuEntry(label, run)
    val sleepEntries = listOf(
        entry(stringResource(R.string.sleep_end_of_track)) { extras?.sleepAtEndOf(com.sadurni.attacca.playback.SleepEnd.TRACK) },
        entry(stringResource(R.string.sleep_end_of_album)) { extras?.sleepAtEndOf(com.sadurni.attacca.playback.SleepEnd.ALBUM) },
        entry(stringResource(R.string.sleep_end_of_artist)) { extras?.sleepAtEndOf(com.sadurni.attacca.playback.SleepEnd.ARTIST) },
        entry(stringResource(R.string.sleep_end_of_list)) { extras?.sleepAtEndOf(com.sadurni.attacca.playback.SleepEnd.LIST) },
    )
    when (action) {
        PlayerAction.PLAY_PAUSE -> PlayButton(viewModel, scale, sleepEntries.take(2))
        PlayerAction.SHUFFLE -> {
            val listSettings by viewModel.listSettings.collectAsStateWithLifecycle()
            val shuffle = listSettings.shuffle
            ToolButton(
                if (shuffle == ShuffleMode.ALBUMS) Icons.Rounded.Album else Icons.Rounded.Shuffle,
                stringResource(
                    when (shuffle) {
                        ShuffleMode.OFF -> R.string.shuffle_off
                        ShuffleMode.TRACKS -> R.string.shuffle_tracks
                        ShuffleMode.ALBUMS -> R.string.shuffle_albums
                    },
                ),
                viewModel::cycleShuffle,
                dimmed = shuffle == ShuffleMode.OFF,
                size = size,
                menu = {
                    listOf(
                        entry(context.getString(R.string.shuffle_off)) { extras?.setShuffleMode(ShuffleMode.OFF) },
                        entry(context.getString(R.string.shuffle_tracks)) { extras?.setShuffleMode(ShuffleMode.TRACKS) },
                        entry(context.getString(R.string.shuffle_albums)) { extras?.setShuffleMode(ShuffleMode.ALBUMS) },
                        entry(context.getString(R.string.queue_shuffle_albums)) { extras?.shuffleAlbumsInPlace() },
                        entry(context.getString(R.string.menu_chain_list)) { extras?.chainQueue() },
                    )
                },
            )
        }
        PlayerAction.REPEAT -> ToolButton(
            if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description,
            viewModel::cycleRepeat,
            dimmed = now.repeatMode == Player.REPEAT_MODE_OFF,
            size = size,
            menu = {
                listOf(
                    entry(context.getString(R.string.menu_repeat_off)) { extras?.updateListSettings { it.copy(repeatMode = Player.REPEAT_MODE_OFF) } },
                    entry(context.getString(R.string.menu_repeat_all)) { extras?.updateListSettings { it.copy(repeatMode = Player.REPEAT_MODE_ALL) } },
                    entry(context.getString(R.string.menu_repeat_one)) { extras?.updateListSettings { it.copy(repeatMode = Player.REPEAT_MODE_ONE) } },
                ) + sleepEntries.drop(1)
            },
        )
        PlayerAction.PREVIOUS -> {
            val previousAlbum = actionName(PlayerAction.PREVIOUS_ALBUM)
            ToolButton(Icons.Rounded.SkipPrevious, description, viewModel::previous, size = size, menu = {
                listOf(
                    entry(context.getString(R.string.menu_restart_track)) { viewModel.seekTo(0) },
                    entry(previousAlbum) { viewModel.previousGroup() },
                )
            })
        }
        PlayerAction.NEXT -> {
            val nextAlbum = actionName(PlayerAction.NEXT_ALBUM)
            val toEnd = actionName(PlayerAction.TO_END)
            ToolButton(Icons.Rounded.SkipNext, description, viewModel::next, size = size, menu = {
                listOf(entry(nextAlbum) { viewModel.nextGroup() }, entry(toEnd) { viewModel.skipToEnd() })
            })
        }
        PlayerAction.PREVIOUS_ALBUM -> ToolButton(Icons.Rounded.KeyboardDoubleArrowLeft, description, viewModel::previousGroup, size = size)
        PlayerAction.NEXT_ALBUM -> ToolButton(Icons.Rounded.KeyboardDoubleArrowRight, description, viewModel::nextGroup, size = size)
        PlayerAction.SEEK_BACK -> ToolButton(Icons.Rounded.Replay10, description, {
            viewModel.seekTo((viewModel.positionMs.value - SEEK_STEP_MS).coerceAtLeast(0L))
        }, size = size)
        PlayerAction.TO_END -> ToolButton(Icons.Rounded.LastPage, description, viewModel::skipToEnd, size = size)
        PlayerAction.QUICK_LIST -> {
            val busy by viewModel.listBusy.collectAsStateWithLifecycle()
            val randomName = actionName(PlayerAction.RANDOM_LIST)
            ToolButton(
                Icons.Rounded.AutoMode, description, { viewModel.quickSeedList(viewModel.nowPlaying.value.mediaId) }, dimmed = busy, size = size,
                menu = {
                    listOfNotNull(
                        local?.let { id -> entry(context.getString(R.string.seed_list_title)) { viewModel.requestSeedList(id) } },
                        local?.let { entry(context.getString(R.string.menu_like_album)) { viewModel.requestLikeCurrent(byArtist = false) } },
                        local?.let { entry(context.getString(R.string.menu_like_artist)) { viewModel.requestLikeCurrent(byArtist = true) } },
                        entry(randomName) { viewModel.requestRandomList() },
                        local?.let { id -> entry(context.getString(R.string.quick_list_options)) { viewModel.requestQuickOptions(id) } },
                        entry(context.getString(R.string.random_list_options)) { context.app.randomMoodRequest.value = true },
                        entry(context.getString(R.string.more_lists_title)) { viewModel.moreListsOpen.value = true },
                    )
                },
            )
        }
        PlayerAction.RANDOM_LIST -> {
            val busy by viewModel.listBusy.collectAsStateWithLifecycle()
            val optionsName = stringResource(R.string.random_list_options)
            ToolButton(Icons.Rounded.Casino, description, { viewModel.requestRandomList() }, dimmed = busy, size = size, menu = {
                listOf(entry(optionsName) { context.app.randomMoodRequest.value = true })
            })
        }
        PlayerAction.SEEK_FORWARD -> ToolButton(Icons.Rounded.Forward10, description, {
            val end = now.durationMs.coerceAtLeast(0L)
            viewModel.seekTo((viewModel.positionMs.value + SEEK_STEP_MS).coerceAtMost(end))
        }, size = size)
        PlayerAction.NAVIGATOR -> ToolButton(
            Icons.Rounded.Explore, description, handlers.onOpenNavigator, size = size, modifier = Modifier.tipAnchor("player.navigator"),
            menu = { handlers.recentLists().map { source -> entry(source.name) { handlers.onOpenList(source, null) } } },
        )
        PlayerAction.BOOKMARK -> {
            val bookmarkAdded = stringResource(R.string.bookmark_added)
            ToolButton(
                Icons.Rounded.BookmarkBorder,
                description,
                onClick = {
                    if (viewModel.addBookmark()) Toast.makeText(context, bookmarkAdded, Toast.LENGTH_SHORT).show()
                },
                size = size,
                menu = { listOf(entry(context.getString(R.string.menu_show_bookmarks)) { handlers.onOpenBookmarks() }) },
            )
        }
        PlayerAction.SLEEP -> {
            val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
            ToolButton(
                Icons.Rounded.Timer, description, { handlers.openDialog(PlayerDialog.SLEEP) }, highlighted = sleepTimer != null, size = size,
                menu = {
                    listOfNotNull(sleepTimer?.let { entry(context.getString(R.string.sleep_cancel)) { extras?.cancelSleepTimer() } }) +
                        listOf(15, 30, 60).map { m -> entry(context.getString(R.string.sleep_minutes, m)) { extras?.startSleepTimer(m) } } +
                        sleepEntries
                },
            )
        }
        PlayerAction.FAVORITE -> {
            val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
            ToolButton(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                description,
                viewModel::toggleFavorite,
                highlighted = favorite,
                size = size,
                modifier = Modifier.tipAnchor("player.favorite"),
                menu = {
                    listOf(
                        entry(context.getString(R.string.menu_open_favorites)) {
                            handlers.onOpenList(
                                com.sadurni.attacca.library.ListSource.Playlist(com.sadurni.attacca.library.UserLists.favorites.absolutePath),
                                now.mediaId.takeIf { favorite },
                            )
                        },
                    )
                },
            )
        }
        PlayerAction.SOUND -> {
            val app = context.app
            val off by app.effectsOff.collectAsStateWithLifecycle()
            val profiles by app.settings.profiles.state.collectAsStateWithLifecycle()
            val active by app.settings.profiles.active.collectAsStateWithLifecycle()
            val device by app.settings.profiles.currentDevice.collectAsStateWithLifecycle()
            val toggleLabel = stringResource(if (off) R.string.menu_effects_on else R.string.menu_effects_off)
            val profileNames = profiles.profiles.map { it.id to com.sadurni.attacca.ui.settings.profileName(it) }
            ToolButton(
                Icons.Rounded.GraphicEq, description, handlers.onOpenSound, dimmed = off, size = size, modifier = Modifier.tipAnchor("player.sound"),
                menu = {
                    listOf(entry(toggleLabel) { app.effectsOff.value = !off }) +
                        if (device == null || profileNames.size < 2) {
                            emptyList()
                        } else {
                            profileNames.map { (id, name) ->
                                entry((if (id == active.id) "✓ " else "    ") + name) { device?.let { app.settings.profiles.assignProfile(it.key, id) } }
                            }
                        }
                },
            )
        }
        PlayerAction.QUEUE -> ToolButton(Icons.AutoMirrored.Rounded.QueueMusic, description, handlers.onOpenQueue, size = size)
        PlayerAction.ORAMA -> ToolButton(Icons.Rounded.Landscape, description, { handlers.openDialog(PlayerDialog.ORAMA) }, size = size, modifier = Modifier.tipAnchor("player.orama"))
        PlayerAction.LISTS -> ToolButton(Icons.Rounded.LibraryMusic, description, handlers.onOpenLists, size = size)
        PlayerAction.LYRICS -> ToolButton(Icons.Rounded.Lyrics, description, handlers.toggleLyrics, size = size)
        PlayerAction.ADD_TO_LIST -> ToolButton(
            Icons.AutoMirrored.Rounded.PlaylistAdd, description, { handlers.openDialog(PlayerDialog.ADD_TO_LIST) }, size = size,
        )
        PlayerAction.SPEED -> {
            val speed by viewModel.speed.collectAsStateWithLifecycle()
            val pitch by viewModel.pitch.collectAsStateWithLifecycle()
            ToolButton(Icons.Rounded.Speed, description, { handlers.openDialog(PlayerDialog.SPEED) }, highlighted = speed != 1f || pitch != 0f, size = size)
        }
        PlayerAction.AB_REPEAT -> {
            val ab by viewModel.abRepeat.collectAsStateWithLifecycle()
            ToolButton(Icons.Rounded.Loop, description, { viewModel.extras?.toggleAbRepeat() }, highlighted = ab.aMs != null, size = size)
        }
        PlayerAction.TAGS -> ToolButton(Icons.Rounded.Edit, description, { local?.let(handlers.onEditTags) }, dimmed = local == null, size = size)
        PlayerAction.INFO -> ToolButton(Icons.Rounded.Info, description, { local?.let(handlers.onShowInfo) }, dimmed = local == null, size = size)
        PlayerAction.FOLDER -> ToolButton(Icons.Rounded.FolderOpen, description, { local?.let(handlers.onGoToFolder) }, dimmed = local == null, size = size)
        PlayerAction.ALBUM -> ToolButton(Icons.Rounded.Album, description, { local?.let { handlers.onGoToMusic(it, false) } }, dimmed = local == null, size = size)
        PlayerAction.ARTIST -> ToolButton(Icons.Rounded.Person, description, { local?.let { handlers.onGoToMusic(it, true) } }, dimmed = local == null, size = size)
    }
}

/** Play / pause: while playing it takes the accent colour (the cover's, when dynamic). */
@Composable
private fun PlayButton(viewModel: PlayerViewModel, scale: Float, menu: List<ButtonMenuEntry>) {
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    val whole = wholeButtonPicture()
    val background by androidx.compose.animation.animateColorAsState(
        if (now.isPlaying) accent else colors.surface,
        label = "play",
    )
    val shape = RoundedCornerShape(18.dp * scale * com.sadurni.attacca.ui.theme.LocalCornerScale.current)
    val label = stringResource(if (now.isPlaying) R.string.action_pause else R.string.action_play)
    LongPressButton(label, { menu }, viewModel::playPause) { longPress ->
    Box(
        Modifier
            .size(PLAY_SIZE * scale)
            .clip(shape)
            // The theme's own key needs no background; an accent edge tells it is playing.
            .then(
                when {
                    !whole -> Modifier.background(background)
                    now.isPlaying -> Modifier.border(3.dp * scale, accent, shape)
                    else -> Modifier
                },
            )
            .then(longPress),
        contentAlignment = Alignment.Center,
    ) {
        ButtonIcon(
            if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            description = label,
            tint = if (now.isPlaying) {
                if (accent.luminance() > 0.4f) Color.Black else Color.White
            } else {
                androidx.compose.material3.LocalContentColor.current
            },
            size = if (whole) PLAY_SIZE * scale else 40.dp * scale,
        )
    }
    }
}

/** The own icon of the button being drawn (a picture of the theme), if any. */
private val LocalButtonImage = androidx.compose.runtime.staticCompositionLocalOf<com.sadurni.attacca.settings.ThemeImage?> { null }

/** A button's icon: the theme's own picture when it has one (in [tint] when it is a shape), else [icon]. */
@Composable
private fun ButtonIcon(icon: ImageVector, description: String, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val own = LocalButtonImage.current
    val picture = com.sadurni.attacca.ui.theme.rememberThemeImage(own)
    if (own != null && picture != null) {
        Image(
            picture,
            contentDescription = description,
            colorFilter = if (own.tint) androidx.compose.ui.graphics.ColorFilter.tint(tint) else null,
            alpha = if (own.tint) 1f else tint.alpha,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size / 6)),
        )
    } else {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size))
    }
}

/** The name of a button, for its description and for the layout editor. */
@Composable
fun actionName(action: PlayerAction): String = stringResource(
    when (action) {
        PlayerAction.PLAY_PAUSE -> R.string.action_play_pause
        PlayerAction.PREVIOUS -> R.string.action_previous
        PlayerAction.NEXT -> R.string.action_next
        PlayerAction.PREVIOUS_ALBUM -> R.string.action_previous_group
        PlayerAction.NEXT_ALBUM -> R.string.action_next_group
        PlayerAction.SHUFFLE -> R.string.action_shuffle
        PlayerAction.REPEAT -> R.string.action_repeat
        PlayerAction.SEEK_BACK -> R.string.action_seek_back
        PlayerAction.SEEK_FORWARD -> R.string.action_seek_forward
        PlayerAction.TO_END -> R.string.go_to_end
        PlayerAction.QUICK_LIST -> R.string.quick_list
        PlayerAction.RANDOM_LIST -> R.string.random_list
        PlayerAction.ORAMA -> R.string.orama_name
        PlayerAction.NAVIGATOR -> R.string.action_navigator
        PlayerAction.BOOKMARK -> R.string.action_bookmarks
        PlayerAction.SLEEP -> R.string.action_sleep_timer
        PlayerAction.FAVORITE -> R.string.action_favorite
        PlayerAction.SOUND -> R.string.action_dsp
        PlayerAction.QUEUE -> R.string.nav_queue
        PlayerAction.LISTS -> R.string.nav_lists
        PlayerAction.LYRICS -> R.string.lyrics
        PlayerAction.ADD_TO_LIST -> R.string.add_to_list
        PlayerAction.SPEED -> R.string.action_speed
        PlayerAction.AB_REPEAT -> R.string.action_ab_repeat
        PlayerAction.TAGS -> R.string.tags_title
        PlayerAction.INFO -> R.string.info_title
        PlayerAction.FOLDER -> R.string.go_to_folder
        PlayerAction.ALBUM -> R.string.go_to_album
        PlayerAction.ARTIST -> R.string.go_to_artist
    },
)

/** The icon of a button as the layout editor shows it. */
fun actionIcon(action: PlayerAction): ImageVector = when (action) {
    PlayerAction.PLAY_PAUSE -> Icons.Rounded.PlayArrow
    PlayerAction.PREVIOUS -> Icons.Rounded.SkipPrevious
    PlayerAction.NEXT -> Icons.Rounded.SkipNext
    PlayerAction.PREVIOUS_ALBUM -> Icons.Rounded.KeyboardDoubleArrowLeft
    PlayerAction.NEXT_ALBUM -> Icons.Rounded.KeyboardDoubleArrowRight
    PlayerAction.SHUFFLE -> Icons.Rounded.Shuffle
    PlayerAction.REPEAT -> Icons.Rounded.Repeat
    PlayerAction.SEEK_BACK -> Icons.Rounded.Replay10
    PlayerAction.SEEK_FORWARD -> Icons.Rounded.Forward10
    PlayerAction.TO_END -> Icons.Rounded.LastPage
    PlayerAction.QUICK_LIST -> Icons.Rounded.AutoMode
    PlayerAction.RANDOM_LIST -> Icons.Rounded.Casino
    PlayerAction.ORAMA -> Icons.Rounded.Landscape
    PlayerAction.NAVIGATOR -> Icons.Rounded.Explore
    PlayerAction.BOOKMARK -> Icons.Rounded.BookmarkBorder
    PlayerAction.SLEEP -> Icons.Rounded.Timer
    PlayerAction.FAVORITE -> Icons.Rounded.FavoriteBorder
    PlayerAction.SOUND -> Icons.Rounded.GraphicEq
    PlayerAction.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
    PlayerAction.LISTS -> Icons.Rounded.LibraryMusic
    PlayerAction.LYRICS -> Icons.Rounded.Lyrics
    PlayerAction.ADD_TO_LIST -> Icons.AutoMirrored.Rounded.PlaylistAdd
    PlayerAction.SPEED -> Icons.Rounded.Speed
    PlayerAction.AB_REPEAT -> Icons.Rounded.Loop
    PlayerAction.TAGS -> Icons.Rounded.Edit
    PlayerAction.INFO -> Icons.Rounded.Info
    PlayerAction.FOLDER -> Icons.Rounded.FolderOpen
    PlayerAction.ALBUM -> Icons.Rounded.Album
    PlayerAction.ARTIST -> Icons.Rounded.Person
}

private const val SEEK_STEP_MS = 10_000L

/** Icon button; [highlighted] gives it a tinted accent background. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    dimmed: Boolean = false,
    highlighted: Boolean = false,
    size: androidx.compose.ui.unit.Dp = TOOL_SIZE,
    modifier: Modifier = Modifier,
    menu: () -> List<ButtonMenuEntry> = { emptyList() },
) {
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    val whole = wholeButtonPicture()
    val shape = RoundedCornerShape(14.dp * (size / TOOL_SIZE))
    LongPressButton(description, menu, onClick, modifier) { longPress ->
    Box(
        Modifier
            .size(size)
            .clip(shape)
            // A whole-button picture shows "on" with an accent edge, like a lit key.
            .then(
                when {
                    !highlighted -> Modifier
                    whole -> Modifier.border(2.dp, accent, shape)
                    else -> Modifier.background(accent.copy(alpha = 0.3f))
                },
            )
            .then(longPress),
        contentAlignment = Alignment.Center,
    ) {
        ButtonIcon(icon, description, colors.text.copy(alpha = if (dimmed) 0.4f else 1f), if (whole) size else 28.dp * (size / TOOL_SIZE))
    }
    }
}

/** An action offered by a long press on a button. */
private class ButtonMenuEntry(val label: String, val run: () -> Unit)

/**
 * A button that runs [onClick] on a tap; a long press shows its [label] above it, or, when [menu]
 * gives any, a short menu of related actions headed by the name. [content] draws the button with
 * the modifier it is given.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LongPressButton(
    label: String,
    menu: () -> List<ButtonMenuEntry>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val tip = androidx.compose.material3.rememberTooltipState()
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ButtonMenuEntry>?>(null) }
    com.sadurni.attacca.ui.common.Labelled(label, modifier, tip) {
        Box {
            content(
                Modifier.combinedClickable(onClick = onClick, onLongClick = {
                    val list = menu()
                    if (list.isNotEmpty()) entries = list else scope.launch { tip.show() }
                }),
            )
            DropdownMenu(expanded = entries != null, onDismissRequest = { entries = null }) {
                Text(
                    label,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAccent.current,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                for (e in entries.orEmpty()) {
                    DropdownMenuItem(text = { Text(e.label) }, onClick = {
                        entries = null
                        e.run()
                    })
                }
            }
        }
    }
}

/**
 * Whether the button being drawn has an own picture that is not tinted: a whole button drawn by the
 * theme (a key, a knob), shown at the button's full size instead of as an icon in the middle.
 */
@Composable
private fun wholeButtonPicture(): Boolean = LocalButtonImage.current?.let { !it.tint } == true

private val TOOL_SIZE = 48.dp
private val PLAY_SIZE = 64.dp

/** The frame of the title and list boxes: a quiet line in the text color. */
private const val BOX_BORDER_ALPHA = 0.18f
