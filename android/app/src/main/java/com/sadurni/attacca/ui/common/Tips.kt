package com.sadurni.attacca.ui.common

import com.sadurni.attacca.ui.theme.AttaccaType
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.BuildConfig
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlin.math.roundToInt

/** One tip: its text and the place on the screen it talks about (see [tipAnchor]), if any. */
data class Tip(val text: Int, val anchor: String? = null)

/** The tips of each screen, shown over it the first time it opens after installing a new build. */
enum class TipsScreen(val tips: List<Tip>) {
    PLAYER(listOf(Tip(R.string.tip_player_1, "player.title"), Tip(R.string.tip_player_cover, "player.cover"), Tip(R.string.tip_cover_tap, "player.cover"), Tip(R.string.tip_mood_menu, "player.title"), Tip(R.string.tip_player_3, "player.navigator"), Tip(R.string.tip_player_crossfade), Tip(R.string.tip_quick_list), Tip(R.string.tip_long_press, "player.favorite"), Tip(R.string.tip_sound_long_press, "player.sound"), Tip(R.string.tip_player_boxes, "player.list"), Tip(R.string.tip_orama, "player.orama"))),
    LISTS(listOf(Tip(R.string.tip_lists_tabs, "lists.tabs"), Tip(R.string.tip_lists_1, "lists.row"), Tip(R.string.tip_lists_2, "lists.handle"), Tip(R.string.tip_lists_listening), Tip(R.string.tip_lists_smart), Tip(R.string.tip_list_tools), Tip(R.string.tip_strip_icons), Tip(R.string.tip_random_list, "lists.random"), Tip(R.string.tip_more_lists, "lists.more"), Tip(R.string.tip_only_included))),
    QUEUE(listOf(Tip(R.string.tip_queue_1, "queue.strip"), Tip(R.string.tip_queue_swipe, "queue.row"), Tip(R.string.tip_queue_undo, "queue.menu"))),
    BROWSER(listOf(Tip(R.string.tip_browser_1, "browser.row"), Tip(R.string.tip_browser_2, "browser.rowMenu"), Tip(R.string.tip_browser_favorites, "browser.favorites"))),
    MUSIC(listOf(Tip(R.string.tip_music_1, "music.row"), Tip(R.string.tip_music_2, "music.menu"), Tip(R.string.tip_music_3), Tip(R.string.tip_music_bridge))),
    SOUND(listOf(Tip(R.string.tip_sound_1, "sound.profile"), Tip(R.string.tip_sound_2, "sound.replaygain"), Tip(R.string.tip_sound_virtual_bass), Tip(R.string.tip_sound_peq))),
    DEVICES(listOf(Tip(R.string.tip_devices_1, "devices.first"))),
    THEME_EDITOR(
        listOf(
            Tip(R.string.tip_theme_preview, "theme.preview"),
            Tip(R.string.tip_theme_undo, "theme.undo"),
            Tip(R.string.tip_theme_use, "theme.use"),
            Tip(R.string.tip_theme_colors, "theme.colors"),
            Tip(R.string.tip_theme_layout),
            Tip(R.string.tip_theme_parts),
        ),
    ),
}

/** Which build's tips each screen has shown, so a new build shows them again. */
object TipsStore {
    private fun prefs(context: Context) = context.getSharedPreferences("tips", Context.MODE_PRIVATE)

    fun pending(context: Context, screen: TipsScreen): Boolean =
        prefs(context).getLong(screen.name, 0L) != BuildConfig.BUILD_TIME

    fun markSeen(context: Context, screen: TipsScreen) {
        prefs(context).edit().putLong(screen.name, BuildConfig.BUILD_TIME).apply()
    }

    /** Shows every screen's tips again from its next opening. */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

/** Where the tip anchors of the screens are, in window coordinates. */
val LocalTipAnchors = staticCompositionLocalOf<SnapshotStateMap<String, Rect>?> { null }

/** Provides the anchor registry the screens and [TipsOverlay] share. */
@Composable
fun ProvideTipAnchors(content: @Composable () -> Unit) {
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    CompositionLocalProvider(LocalTipAnchors provides anchors, content = content)
}

/**
 * Marks this element as the place tip [id] points at. With several elements under one id (rows of
 * a list), the first one composed wins.
 */
fun Modifier.tipAnchor(id: String, enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val anchors = LocalTipAnchors.current ?: return@composed this
    var mine by remember { mutableStateOf(false) }
    DisposableEffect(id) {
        onDispose { if (mine) anchors.remove(id) }
    }
    onGloballyPositioned { coordinates ->
        if (!coordinates.isAttached) return@onGloballyPositioned
        if (!mine && anchors.containsKey(id)) return@onGloballyPositioned
        mine = true
        val bounds = coordinates.boundsInRoot()
        if (anchors[id] != bounds) anchors[id] = bounds
    }
}

/**
 * The tips of [screen] over it, one at a time, while tips are on and this build has not shown
 * them yet. Each tip lights up the place it talks about and grows out of it as a speech bubble;
 * without that place on screen it shows at the bottom. "Do not show tips again" turns them off
 * (they can be turned on in Settings).
 */
@Composable
fun TipsOverlay(screen: TipsScreen) {
    val context = LocalContext.current
    val settings = context.app.settings
    val ui by settings.ui.collectAsStateWithLifecycle()
    var visible by remember(screen) { mutableStateOf(TipsStore.pending(context, screen)) }
    if (!ui.showTips || !visible) return
    var index by remember(screen) { mutableIntStateOf(0) }
    var dontShow by remember { mutableStateOf(false) }
    val anchors = LocalTipAnchors.current
    val tip = screen.tips[index]
    var origin by remember { mutableStateOf(Offset.Zero) }
    val finish = {
        TipsStore.markSeen(context, screen)
        if (dontShow) settings.updateUi { it.copy(showTips = false) }
        visible = false
    }
    // Window rectangle of the anchor, moved into this overlay's coordinates.
    val target = tip.anchor?.let { anchors?.get(it) }?.translate(-origin)
    val accent = LocalAccent.current
    val density = LocalDensity.current
    val grow = remember(index) { Animatable(0f) }
    LaunchedEffect(index) { grow.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(250)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Taps outside the bubble do nothing (the screen below stays untouched).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val margin = with(density) { 8.dp.toPx() }
        val hole = target?.let {
            Rect(it.left - margin, it.top - margin, it.right + margin, it.bottom + margin)
                .intersect(Rect(0f, 0f, width, height))
        }?.takeIf { it.width > 0 && it.height > 0 }

        // The dimmed screen with the anchor cut out and ringed.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = fade.value }) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            if (hole != null) {
                val radius = CornerRadius(16.dp.toPx())
                drawRoundRect(Color.Transparent, hole.topLeft, hole.size, radius, blendMode = BlendMode.Clear)
                drawRoundRect(accent, hole.topLeft, hole.size, radius, style = Stroke(2.dp.toPx()))
            }
        }

        val arrow = with(density) { 12.dp.toPx() }
        val bubbleWidth = minOf(width - 2 * with(density) { 16.dp.toPx() }, with(density) { 420.dp.toPx() })
        var bubbleHeight by remember(index) { mutableIntStateOf(0) }
        // Below the anchor when it is in the upper half, above it otherwise.
        val below = hole == null || hole.center.y < height / 2
        val left = ((hole?.center?.x ?: (width / 2)) - bubbleWidth / 2).coerceIn(
            with(density) { 16.dp.toPx() },
            width - bubbleWidth - with(density) { 16.dp.toPx() },
        )
        val top = when {
            hole == null -> height - bubbleHeight - with(density) { 24.dp.toPx() }
            below -> hole.bottom + arrow
            else -> hole.top - arrow - bubbleHeight
        }.coerceIn(0f, maxOf(0f, height - bubbleHeight))
        // Where the arrow touches the anchor, relative to the bubble: the bubble grows from it.
        val tipX = ((hole?.center?.x ?: (width / 2)) - left).coerceIn(arrow * 2, bubbleWidth - arrow * 2)
        val shape = bubbleShape(arrowX = tipX, arrowSize = arrow, pointsUp = below, hasArrow = hole != null, corner = with(density) { 20.dp.toPx() })
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(with(density) { bubbleWidth.toDp() })
                .onGloballyPositioned { bubbleHeight = it.size.height }
                .graphicsLayer {
                    alpha = if (bubbleHeight == 0) 0f else grow.value.coerceIn(0f, 1f)
                    scaleX = 0.3f + 0.7f * grow.value
                    scaleY = 0.3f + 0.7f * grow.value
                    transformOrigin = TransformOrigin(tipX / bubbleWidth, if (below) 0f else 1f)
                },
        ) {
            TipBubble(
                text = stringResource(tip.text),
                counter = stringResource(R.string.tip_counter, index + 1, screen.tips.size),
                last = index == screen.tips.lastIndex,
                dontShow = dontShow,
                onDontShow = { dontShow = it },
                onNext = { index++ },
                onFinish = finish,
                shape = shape,
                arrowSpace = if (hole != null) arrow else 0f,
                arrowOnTop = below,
            )
        }
    }
}

/** A rounded box with a small triangle on its top or bottom edge at [arrowX]. */
private fun bubbleShape(arrowX: Float, arrowSize: Float, pointsUp: Boolean, hasArrow: Boolean, corner: Float) =
    GenericShape { size, _ ->
        val top = if (hasArrow && pointsUp) arrowSize else 0f
        val bottom = if (hasArrow && !pointsUp) size.height - arrowSize else size.height
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(0f, top, size.width, bottom), CornerRadius(corner)))
        if (hasArrow) {
            if (pointsUp) {
                moveTo(arrowX - arrowSize, top + 1f)
                lineTo(arrowX, 0f)
                lineTo(arrowX + arrowSize, top + 1f)
            } else {
                moveTo(arrowX - arrowSize, bottom - 1f)
                lineTo(arrowX, size.height)
                lineTo(arrowX + arrowSize, bottom - 1f)
            }
            close()
        }
    }

@Composable
private fun TipBubble(
    text: String,
    counter: String,
    last: Boolean,
    dontShow: Boolean,
    onDontShow: (Boolean) -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    arrowSpace: Float,
    arrowOnTop: Boolean,
) {
    val accent = LocalAccent.current
    val colors = LocalAttaccaColors.current
    val space = with(LocalDensity.current) { arrowSpace.toDp() }
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .clip(shape)
            .background(colors.surface)
            .padding(top = if (arrowOnTop) space else 0.dp, bottom = if (arrowOnTop) 0.dp else space)
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Text(counter, fontSize = AttaccaType.secondary, fontWeight = FontWeight.SemiBold, color = accent, modifier = Modifier.padding(start = 8.dp))
        }
        Text(text, fontSize = AttaccaType.body, lineHeight = 23.sp, color = colors.text, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, end = 8.dp))
        Row(Modifier.fillMaxWidth().clickable { onDontShow(!dontShow) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = dontShow, onCheckedChange = onDontShow, colors = CheckboxDefaults.colors(checkedColor = accent))
            Text(stringResource(R.string.tips_dont_show), fontSize = AttaccaType.secondary, color = colors.textGrayed)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.tips_skip), color = colors.textGrayed) }
                TextButton(onClick = onNext) { Text(stringResource(R.string.tips_next), color = accent) }
            } else {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.tips_done), color = accent) }
            }
        }
    }
}
