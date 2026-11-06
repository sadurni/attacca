package com.sadurni.attacca.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.RowAction
import com.sadurni.attacca.settings.RowSwipe
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

@Composable
fun rowActionName(action: RowAction): String = stringResource(
    when (action) {
        RowAction.NONE -> R.string.gesture_action_none
        RowAction.PLAY_NEXT -> R.string.queue_play_next
        RowAction.ADD_TO_QUEUE -> R.string.add_to_queue
        RowAction.ADD_TO_FAVORITES -> R.string.add_to_favorites
        RowAction.REMOVE -> R.string.queue_remove
    },
)

private fun RowAction.icon(): ImageVector? = when (this) {
    RowAction.NONE -> null
    RowAction.PLAY_NEXT -> Icons.Rounded.QueuePlayNext
    RowAction.ADD_TO_QUEUE -> Icons.AutoMirrored.Rounded.PlaylistAdd
    RowAction.ADD_TO_FAVORITES -> Icons.Rounded.Favorite
    RowAction.REMOVE -> Icons.Rounded.Delete
}

/**
 * A row that does the actions set in Settings → Gestures when swiped sideways: [queue] rows use
 * the current list's, other rows those of folders and lists. The row springs back after the
 * action; while swiping, the action shows behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(
    itemKey: Any,
    queue: Boolean,
    enabled: Boolean,
    onAction: (RowAction) -> Unit,
    content: @Composable () -> Unit,
) {
    // A fresh swipe state per item: after a removal the next item takes this place in the list
    // and must not inherit a half-done swipe.
    androidx.compose.runtime.key(itemKey) { SwipeRowContent(queue, enabled, onAction, content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRowContent(
    queue: Boolean,
    enabled: Boolean,
    onAction: (RowAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val settings = androidx.compose.ui.platform.LocalContext.current.app.settings
    val gestures by settings.rowGestures.collectAsStateWithLifecycle()
    // Swiping towards the end (right in left-to-right text) is the "right" swipe.
    val right = gestures[if (queue) RowSwipe.QUEUE_RIGHT else RowSwipe.BROWSE_RIGHT] ?: RowAction.NONE
    val left = gestures[if (queue) RowSwipe.QUEUE_LEFT else RowSwipe.BROWSE_LEFT] ?: RowAction.NONE
    val act by rememberUpdatedState(onAction)
    // Once per swipe: the check may come more than once while the row settles.
    val fired = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && fired.compareAndSet(false, true)) {
                act(if (value == SwipeToDismissBoxValue.StartToEnd) right else left)
            }
            // Never stays dismissed: the row comes back (a removal updates the list itself).
            false
        },
    )
    androidx.compose.runtime.LaunchedEffect(state.dismissDirection) {
        if (state.dismissDirection == SwipeToDismissBoxValue.Settled) fired.set(false)
    }
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled && right != RowAction.NONE,
        enableDismissFromEndToStart = enabled && left != RowAction.NONE,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> right
                SwipeToDismissBoxValue.EndToStart -> left
                SwipeToDismissBoxValue.Settled -> RowAction.NONE
            }
            if (action != RowAction.NONE) {
                val tint = if (action == RowAction.REMOVE) colors.text else accent
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(tint.copy(alpha = 0.16f))
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    action.icon()?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
                    Text(
                        rowActionName(action),
                        fontSize = AttaccaType.secondary,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        },
    ) {
        // An opaque row over the action while it shows; else see-through (background images).
        val swiping = state.dismissDirection != SwipeToDismissBoxValue.Settled
        Box(if (swiping) Modifier.background(colors.background) else Modifier) { content() }
    }
}
