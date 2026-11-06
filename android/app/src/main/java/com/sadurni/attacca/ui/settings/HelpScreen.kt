package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Album
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/** A topic: its title, its text and further points added to it. */
private data class HelpTopic(val icon: ImageVector, val title: Int, val body: Int, val more: List<Int> = emptyList(), val gallery: Boolean = false)

private val topics = listOf(
    HelpTopic(Icons.Rounded.RocketLaunch, R.string.help_start_title, R.string.help_start_body),
    HelpTopic(Icons.Rounded.Explore, R.string.help_nav_title, R.string.help_nav_body),
    HelpTopic(Icons.Rounded.PlayCircle, R.string.help_player_title, R.string.help_player_body, listOf(R.string.help_player_cover, R.string.help_player_cover_view, R.string.help_player_long_press, R.string.help_player_sound, R.string.help_player_boxes, R.string.help_player_plays, R.string.help_orama, R.string.help_orama_more, R.string.help_orama_gates, R.string.help_orama_buttons, R.string.help_orama_surprises)),
    HelpTopic(Icons.Rounded.MusicNote, R.string.help_playback_title, R.string.help_playback_body, listOf(R.string.help_crossfade, R.string.help_listening, R.string.help_playback_more, R.string.help_playback_extra)),
    HelpTopic(Icons.Rounded.LibraryMusic, R.string.help_lists_title, R.string.help_lists_body, listOf(R.string.help_strip_icons, R.string.help_lists_delete, R.string.help_lists_more, R.string.help_lists_tools, R.string.help_lists_quick, R.string.help_lists_random, R.string.help_lists_other, R.string.help_lists_making, R.string.help_lists_count, R.string.help_lists_chain_from, R.string.help_favorites_included, R.string.help_lists_tabs)),
    HelpTopic(Icons.AutoMirrored.Rounded.QueueMusic, R.string.help_queue_title, R.string.help_queue_body, listOf(R.string.help_strip_icons, R.string.help_queue_undo, R.string.help_queue_undo_last)),
    HelpTopic(Icons.Rounded.Folder, R.string.help_browser_title, R.string.help_browser_body, listOf(R.string.help_browser_more, R.string.help_music_bridge)),
    HelpTopic(Icons.Rounded.Album, R.string.help_music_title, R.string.help_music_body, listOf(R.string.help_music_grouped, R.string.help_music_bridge, R.string.help_music_by_year)),
    HelpTopic(Icons.Rounded.GraphicEq, R.string.help_sound_title, R.string.help_sound_body, listOf(R.string.help_sound_more, R.string.help_sound_bass, R.string.help_sound_peq, R.string.help_sound_presets)),
    HelpTopic(Icons.Rounded.Headphones, R.string.help_devices_title, R.string.help_devices_body, listOf(R.string.help_devices_fold, R.string.help_devices_notifications)),
    HelpTopic(Icons.Rounded.AutoAwesome, R.string.help_chain_title, R.string.help_chain_body, listOf(R.string.help_chain_silences, R.string.help_moods)),
    HelpTopic(Icons.Rounded.PriorityHigh, R.string.help_inclusion_title, R.string.help_inclusion_body, listOf(R.string.help_inclusion_default)),
    HelpTopic(Icons.Rounded.Palette, R.string.help_themes_title, R.string.help_themes_body, listOf(R.string.help_themes_layout, R.string.help_themes_images, R.string.help_themes_names, R.string.help_themes_parts, R.string.help_themes_system_mood, R.string.help_themes_desktop), gallery = true),
    HelpTopic(Icons.Rounded.DirectionsCar, R.string.help_car_title, R.string.help_car_body, listOf(R.string.help_car_cover)),
    HelpTopic(Icons.Rounded.Speed, R.string.help_performance_title, R.string.help_performance_body),
    HelpTopic(Icons.Rounded.Settings, R.string.help_settings_title, R.string.help_settings_body, listOf(R.string.help_settings_hints, R.string.help_backup_auto, R.string.help_exchange, R.string.help_player_places, R.string.help_language, R.string.help_about_changelog)),
)

/** How to use the app: one topic per part of it, opened with a tap. */
@Composable
fun HelpScreen(onBack: () -> Unit, onThemeGallery: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.help_title), onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                com.sadurni.attacca.ui.common.AppTagline(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
            item {
                Text(
                    stringResource(R.string.help_intro),
                    fontSize = AttaccaType.secondary,
                    color = LocalAttaccaColors.current.textGrayed,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(topics, key = { it.title }) { HelpTopicRow(it, onThemeGallery) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HelpTopicRow(topic: HelpTopic, onThemeGallery: () -> Unit) {
    var open by rememberSaveable(topic.title) { mutableStateOf(false) }
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(topic.icon, contentDescription = null, tint = if (open) accent else LocalAttaccaColors.current.textGrayed, modifier = Modifier.size(24.dp))
            Text(
                stringResource(topic.title),
                fontSize = AttaccaType.heading,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = LocalAttaccaColors.current.textGrayed,
            )
        }
        if (open) {
            Text(
                (listOf(topic.body) + topic.more).map { stringResource(it) }.joinToString("\n"),
                fontSize = AttaccaType.body,
                lineHeight = 22.sp,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 16.dp),
            )
            if (topic.gallery) {
                androidx.compose.material3.OutlinedButton(onClick = onThemeGallery, modifier = Modifier.padding(start = 56.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.theme_gallery_open))
                }
            }
        }
    }
}
