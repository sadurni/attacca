package com.sadurni.attacca.ui.player

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.analysis.LoudnessMeter
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attacca.library.TrackDetails
import com.sadurni.attacca.library.TrackInfo
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the file says about itself, read once per track for the information lines. */
private class FileFacts(val details: TrackDetails?, val features: TrackFeatures?)

/** Mood and sound family of a track among the analysed music folder (as for the generated lists). */
private class Classified(val mood: String?, val family: String?)

/**
 * The values of the playing track for [com.sadurni.attacca.settings.InfoTemplate]: tags, audio
 * stream, file, list, loudness, tempo, mood and sound family. The file is only read when [needed]
 * (some line uses it), and the music folder classified only when [classified] (some line shows the
 * mood or the family).
 */
@Composable
fun rememberTrackValues(viewModel: PlayerViewModel, needed: Boolean, classified: Boolean = false): (String) -> String? {
    val context = LocalContext.current
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val listName by viewModel.listName.collectAsStateWithLifecycle()
    val analyses by context.app.analysisResults.collectAsStateWithLifecycle()
    val path = now.mediaId?.takeIf { needed && !it.contains("://") }
    val facts by produceState<FileFacts?>(null, path) {
        value = null
        if (path == null) return@produceState
        value = withContext(Dispatchers.IO) {
            val file = File(path)
            val details = runCatching { TrackInfo.read(file, withCovers = false) }.getOrNull()
            val features = runCatching { context.app.chainAnalyzer.db.getFeatures(listOf(file))[file.absolutePath] }.getOrNull()
            FileFacts(details, features)
        }
    }
    val classifiedPath = now.mediaId?.takeIf { classified && !it.contains("://") }
    val moodVersion by com.sadurni.attacca.library.MoodCorrections.version.collectAsStateWithLifecycle()
    val classes by produceState<Classified?>(null, classifiedPath, moodVersion) {
        value = null
        if (classifiedPath == null) return@produceState
        // What was kept from last time shows at once, while the collection is being made.
        value = withContext(Dispatchers.IO) {
            val (index, k) = context.app.chainLists.keptClassification() ?: return@withContext null
            val i = index[classifiedPath] ?: return@withContext null
            Classified(
                com.sadurni.attacca.ui.chain.moodText(context, k.moods[i], k.secondMoods[i]),
                com.sadurni.attacca.ui.chain.familyLabel(context, k.familyNames[k.families[i]]),
            )
        }
        value = withContext(Dispatchers.IO) {
            // The classified collection is kept once made (also between runs).
            val g = runCatching { context.app.chainLists.collection() }.getOrNull()?.generator
            val index = g?.indexOf(classifiedPath) ?: -1
            if (g == null || index < 0) {
                Classified(null, null)
            } else {
                Classified(
                    com.sadurni.attacca.ui.chain.moodText(context, g, index),
                    com.sadurni.attacca.ui.chain.familyLabel(context, g.classification.familyNames[g.familyOf(index)]),
                )
            }
        }
    }
    val analysis = now.mediaId?.let { analyses[it] }
    val listeningVersion by context.app.listening.version.collectAsStateWithLifecycle()
    val heard = remember(now.mediaId, listeningVersion) { now.mediaId?.let { context.app.listening[it] } }
    return { field ->
        val details = facts?.details
        val audio = details?.audio
        fun tag(keys: Set<String>) = details?.tags?.firstOrNull { it.first.uppercase() in keys }?.second
        when (field.lowercase()) {
            "title" -> now.title.takeIf { now.mediaId != null }
            "artist" -> now.artist
            "album" -> now.album
            "albumartist" -> tag(ALBUM_ARTIST_KEYS)
            "track" -> now.trackNumber?.toString()
            "disc" -> tag(DISC_KEYS)?.substringBefore('/')
            "year" -> now.date
            "genre" -> now.genre
            "composer" -> tag(COMPOSER_KEYS)
            "comment" -> tag(COMMENT_KEYS)
            "format" -> audio?.format
            "codec" -> audio?.codec ?: audio?.format
            "bitrate" -> audio?.bitrateKbps?.let { "$it kbps" + if (audio.variableBitrate == true) " VBR" else "" }
            "samplerate" -> audio?.sampleRateHz?.let { hz ->
                if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
            }
            "bits" -> audio?.bitsPerSample?.let { "$it bit" }
            "channels" -> audio?.channels
            "duration" -> now.durationMs.takeIf { it > 0 }?.let(::formatTime)
            "filename" -> now.mediaId?.let { File(it).name }
            "folder" -> now.mediaId?.let { File(it).parentFile?.name }
            "size" -> details?.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
            "list" -> listName.takeIf { now.count > 0 }
            "position" -> (now.index + 1).takeIf { now.count > 0 }?.toString()
            "count" -> now.count.takeIf { it > 0 }?.toString()
            "next" -> now.nextTitle
            "nextartist" -> now.nextArtist
            "loudness" -> analysis?.loudnessLufs?.let { "%.1f LUFS".format(it) }
            "gain" -> analysis?.loudnessLufs?.let { "%+.1f dB".format(LoudnessMeter.REFERENCE_LUFS - it) }
            "bpm" -> facts?.features?.tempoBpm?.takeIf { it > 0 }?.let { "%.0f BPM".format(it) }
            "mood" -> classes?.mood
            "family" -> classes?.family
            // None until played once, so "[▶ {plays}]" shows only for tracks heard before.
            "plays" -> heard?.plays?.takeIf { it > 0 }?.toString()
            "lastplayed" -> heard?.lastPlayedMs?.takeIf { it > 0 }?.let { formatWhen(context, it) }
            else -> if (field.startsWith("tag:", ignoreCase = true)) tag(setOf(field.substring(4).trim().uppercase())) else null
        }
    }
}

/** Whether a line made from [template] reads the file (anything beyond what the player knows). */
fun readsFile(template: String): Boolean = fieldsOf(template).any { it !in PLAYER_FIELDS && it !in CLASS_FIELDS }

/** Whether a line made from [template] shows the mood or the sound family. */
fun needsClassification(template: String): Boolean = fieldsOf(template).any { it in CLASS_FIELDS }

private fun fieldsOf(template: String) = Regex("\\{([^}]*)\\}").findAll(template).map { it.groupValues[1].trim().lowercase() }

private val CLASS_FIELDS = setOf("mood", "family")

private val PLAYER_FIELDS = setOf("title", "artist", "album", "track", "year", "genre", "duration", "filename", "folder", "list", "position", "count", "loudness", "gain", "plays", "lastplayed")

/** A past moment as the phone shows dates, with the time when it was today. */
fun formatWhen(context: android.content.Context, ms: Long): String {
    val date = java.util.Date(ms)
    val today = android.text.format.DateUtils.isToday(ms)
    return if (today) android.text.format.DateFormat.getTimeFormat(context).format(date)
    else android.text.format.DateFormat.getMediumDateFormat(context).format(date)
}

private val ALBUM_ARTIST_KEYS = setOf("TPE2", "TP2", "ALBUMARTIST", "ALBUM ARTIST", "AART")
private val DISC_KEYS = setOf("TPOS", "TPA", "DISCNUMBER", "DISK")
private val COMPOSER_KEYS = setOf("TCOM", "TCM", "COMPOSER", "©WRT")
private val COMMENT_KEYS = setOf("COMM", "COM", "COMMENT", "DESCRIPTION", "©CMT")
