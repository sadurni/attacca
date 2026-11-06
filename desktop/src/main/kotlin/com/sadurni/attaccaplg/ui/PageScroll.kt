package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The page's scrolling list answers Page Up / Page Down (and Ctrl+Home / Ctrl+End) from anywhere
 * in the window: lists take no keyboard focus, so the window passes the keys to the list shown
 * last (the page's main one).
 */
object PageKeys {
    var target: ((Key) -> Unit)? = null

    /** True when a list took the key. */
    fun handle(key: Key): Boolean {
        val t = target ?: return false
        if (key != Key.PageUp && key != Key.PageDown && key != Key.MoveHome && key != Key.MoveEnd) return false
        t(key)
        return true
    }
}

/** Scrollbars that can be seen and grabbed in every theme. */
@Composable
fun scrollbarStyle(): ScrollbarStyle {
    val c = MaterialTheme.colorScheme.onSurface
    return ScrollbarStyle(
        minimalHeight = 40.dp,
        thickness = 10.dp,
        shape = RoundedCornerShape(5.dp),
        hoverDurationMillis = 200,
        unhoverColor = c.copy(alpha = 0.28f),
        hoverColor = c.copy(alpha = 0.55f),
    )
}

@Composable
private fun RegisterPageKeys(key: Any, action: suspend (Key) -> Unit) {
    val scope = rememberCoroutineScope()
    DisposableEffect(key) {
        val f: (Key) -> Unit = { k -> scope.launch { action(k) } }
        PageKeys.target = f
        onDispose { if (PageKeys.target === f) PageKeys.target = null }
    }
}

@Composable
fun PageScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
    RegisterPageKeys(state) { k ->
        val page = state.layoutInfo.viewportSize.height * 0.9f
        when (k) {
            Key.PageDown -> state.scrollBy(page)
            Key.PageUp -> state.scrollBy(-page)
            Key.MoveHome -> state.scrollToItem(0)
            else -> state.scrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
}

@Composable
fun PageScrollbar(state: LazyGridState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
    RegisterPageKeys(state) { k ->
        val page = state.layoutInfo.viewportSize.height * 0.9f
        when (k) {
            Key.PageDown -> state.scrollBy(page)
            Key.PageUp -> state.scrollBy(-page)
            Key.MoveHome -> state.scrollToItem(0)
            else -> state.scrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
}

@Composable
fun PageScrollbar(state: ScrollState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
    RegisterPageKeys(state) { k ->
        val page = state.viewportSize * 0.9f
        when (k) {
            Key.PageDown -> state.scrollBy(page)
            Key.PageUp -> state.scrollBy(-page)
            Key.MoveHome -> state.scrollTo(0)
            else -> state.scrollTo(state.maxValue)
        }
    }
}

/** A page's content that scrolls, with its scrollbar. */
@Composable
fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(end = 14.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        PageScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
