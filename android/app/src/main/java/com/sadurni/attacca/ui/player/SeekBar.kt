package com.sadurni.attacca.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Seek bar: a base line with the played part in the accent color and a round thumb (white on
 * dark themes).
 *
 * With a [waveform] (normalized 0..1 loudness bins across the whole file) the track's waveform is
 * drawn above the line, played bars in the accent color: only the top half by default, or
 * mirrored around the line. Parts of the file outside [activeFrom]..[activeTo] (trimmed silence or
 * a playlist clip) are dimmed. [progress] and the seek callback use fractions of the whole bar.
 */
@Composable
fun SeekBar(
    progress: Float,
    accent: Color,
    unplayed: Color,
    thumb: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    waveform: FloatArray? = null,
    mirrored: Boolean = false,
    activeFrom: Float = 0f,
    activeTo: Float = 1f,
) {
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val shown = (dragProgress ?: progress).coerceIn(0f, 1f)
    val seek by rememberUpdatedState(onSeek)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> seek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragProgress = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragProgress?.let(seek)
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                )
            },
    ) {
        val lineWidth = 3.dp.toPx()
        val thumbRadius = 9.dp.toPx()
        val baseline = if (waveform != null && mirrored) size.height / 2 else size.height - thumbRadius
        val playedX = size.width * shown

        if (waveform != null && waveform.isNotEmpty()) {
            // Thin bars: 4 px wide with 3 px gaps at density 3.
            val barWidth = 1.33.dp.toPx()
            val gap = 1.dp.toPx()
            val bars = ((size.width + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
            val maxHeight = (if (mirrored) size.height / 2 else baseline) - lineWidth
            for (i in 0 until bars) {
                val x = i * (barWidth + gap) + barWidth / 2
                val fraction = x / size.width
                val value = waveform[(fraction * waveform.size).toInt().coerceIn(0, waveform.lastIndex)]
                // Slight compression so quiet passages stay visible.
                val h = (value.coerceIn(0f, 1f).pow(0.75f) * maxHeight).coerceAtLeast(1f)
                var color = if (x <= playedX) accent else unplayed
                if (fraction < activeFrom || fraction > activeTo) color = color.copy(alpha = 0.25f)
                drawLine(color, Offset(x, baseline), Offset(x, baseline - h), barWidth)
                if (mirrored) drawLine(color, Offset(x, baseline), Offset(x, baseline + h), barWidth)
            }
        }

        drawLine(unplayed, Offset(0f, baseline), Offset(size.width, baseline), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(0f, baseline), Offset(playedX, baseline), lineWidth, StrokeCap.Round)
        drawCircle(thumb, thumbRadius, Offset(playedX.coerceIn(thumbRadius, size.width - thumbRadius), baseline))
    }
}
