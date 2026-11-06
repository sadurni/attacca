package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.settings.AppSettings
import com.sadurni.attacca.settings.CoverAction
import com.sadurni.attacca.settings.CoverGesture
import com.sadurni.attacca.ui.theme.LocalAccent

@Composable
fun GestureSettings(settings: AppSettings) {
    val gestures by settings.gestures.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.section_gestures))
    Hint(stringResource(R.string.gestures_hint))
    for (gesture in CoverGesture.entries) {
        ActionPicker(
            label = gestureName(gesture),
            selected = gestures[gesture] ?: CoverAction.NONE,
            onSelect = { settings.setGesture(gesture, it) },
        )
    }

    val rows by settings.rowGestures.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.section_row_gestures))
    Hint(stringResource(R.string.row_gestures_hint))
    for (swipe in com.sadurni.attacca.settings.RowSwipe.entries) {
        RowActionPicker(
            label = stringResource(
                when (swipe) {
                    com.sadurni.attacca.settings.RowSwipe.QUEUE_LEFT -> R.string.row_queue_left
                    com.sadurni.attacca.settings.RowSwipe.QUEUE_RIGHT -> R.string.row_queue_right
                    com.sadurni.attacca.settings.RowSwipe.BROWSE_LEFT -> R.string.row_browse_left
                    com.sadurni.attacca.settings.RowSwipe.BROWSE_RIGHT -> R.string.row_browse_right
                },
            ),
            selected = rows[swipe] ?: com.sadurni.attacca.settings.RowAction.NONE,
            choices = com.sadurni.attacca.settings.RowAction.choicesFor(swipe),
            onSelect = { settings.setRowGesture(swipe, it) },
        )
    }
}

@Composable
private fun RowActionPicker(
    label: String,
    selected: com.sadurni.attacca.settings.RowAction,
    choices: List<com.sadurni.attacca.settings.RowAction>,
    onSelect: (com.sadurni.attacca.settings.RowAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
            Text(com.sadurni.attacca.ui.common.rowActionName(selected), fontSize = AttaccaType.body, fontWeight = FontWeight.Bold, color = LocalAccent.current)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (action in choices) {
                DropdownMenuItem(text = { Text(com.sadurni.attacca.ui.common.rowActionName(action)) }, onClick = {
                    open = false
                    onSelect(action)
                })
            }
        }
    }
}

@Composable
private fun ActionPicker(label: String, selected: CoverAction, onSelect: (CoverAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
            Text(actionName(selected), fontSize = AttaccaType.body, fontWeight = FontWeight.Bold, color = LocalAccent.current)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (action in CoverAction.entries) {
                DropdownMenuItem(text = { Text(actionName(action)) }, onClick = {
                    open = false
                    onSelect(action)
                })
            }
        }
    }
}

@Composable
private fun gestureName(gesture: CoverGesture): String = stringResource(
    when (gesture) {
        CoverGesture.TAP -> R.string.gesture_tap
        CoverGesture.SWIPE_LEFT -> R.string.gesture_swipe_left
        CoverGesture.SWIPE_RIGHT -> R.string.gesture_swipe_right
        CoverGesture.SWIPE_UP -> R.string.gesture_swipe_up
        CoverGesture.SWIPE_DOWN -> R.string.gesture_swipe_down
        CoverGesture.DOUBLE_TAP_TOP -> R.string.gesture_tap_top
        CoverGesture.DOUBLE_TAP_BOTTOM -> R.string.gesture_tap_bottom
        CoverGesture.DOUBLE_TAP_LEFT -> R.string.gesture_tap_left
        CoverGesture.DOUBLE_TAP_RIGHT -> R.string.gesture_tap_right
    },
)

@Composable
private fun actionName(action: CoverAction): String = stringResource(
    when (action) {
        CoverAction.NONE -> R.string.gesture_action_none
        CoverAction.NEXT -> R.string.action_next
        CoverAction.PREVIOUS -> R.string.action_previous
        CoverAction.NEXT_ALBUM -> R.string.action_next_group
        CoverAction.PREVIOUS_ALBUM -> R.string.action_previous_group
        CoverAction.PLAY_PAUSE -> R.string.gesture_action_play_pause
        CoverAction.OPEN_QUEUE -> R.string.nav_queue
        CoverAction.OPEN_LISTS -> R.string.nav_lists
        CoverAction.OPEN_NAVIGATOR -> R.string.action_navigator
        CoverAction.OPEN_SOUND -> R.string.nav_sound
        CoverAction.TOGGLE_FAVORITE -> R.string.action_favorite
        CoverAction.ADD_BOOKMARK -> R.string.gesture_action_bookmark
        CoverAction.CYCLE_SHUFFLE -> R.string.action_shuffle
        CoverAction.CYCLE_REPEAT -> R.string.action_repeat
        CoverAction.SLEEP_TIMER -> R.string.action_sleep_timer
        CoverAction.SHOW_LYRICS -> R.string.lyrics
        CoverAction.GO_TO_FOLDER -> R.string.go_to_folder
        CoverAction.GO_TO_ALBUM -> R.string.go_to_album
        CoverAction.GO_TO_ARTIST -> R.string.go_to_artist
        CoverAction.GO_TO_END -> R.string.go_to_end
        CoverAction.QUICK_LIST -> R.string.quick_list
        CoverAction.RANDOM_LIST -> R.string.random_list
        CoverAction.SHOW_COVER -> R.string.gesture_action_show_cover
        CoverAction.OPEN_ORAMA -> R.string.orama_name
    },
)
