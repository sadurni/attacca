package com.sadurni.attaccaplg.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The navigation icons, drawn like the app's own (bars, lines, the slur), on a 24-unit grid. */
object NavIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = block)

    private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, pathBuilder = block)

    private fun PathBuilder.bar(x: Float, top: Float, bottom: Float, w: Float = 2.4f) {
        moveTo(x, top)
        lineTo(x + w, top)
        lineTo(x + w, bottom)
        lineTo(x, bottom)
        close()
    }

    /** Library: the waveform bars under the slur. */
    val Library = icon("Library") {
        fill {
            bar(3f, 14f, 20f)
            bar(7f, 10f, 20f)
            bar(11f, 7f, 20f)
            bar(15f, 10f, 20f)
            bar(19f, 14f, 20f, 2f)
        }
        stroke {
            moveTo(6f, 5f)
            quadTo(12f, 0.5f, 18f, 5f)
        }
    }

    /** Tracks: lines of a table. */
    val Tracks = icon("Tracks") {
        stroke {
            for (y in listOf(5f, 10f, 15f, 20f)) {
                moveTo(3f, y)
                lineTo(5f, y)
                moveTo(8f, y)
                lineTo(21f, y)
            }
        }
    }

    /** Lists: lines joined by the slur (a chained list). */
    val Lists = icon("Lists") {
        stroke {
            moveTo(3f, 9f)
            lineTo(13f, 9f)
            moveTo(3f, 14f)
            lineTo(13f, 14f)
            moveTo(3f, 19f)
            lineTo(13f, 19f)
            moveTo(15f, 19f)
            quadTo(22f, 13f, 15f, 6f)
        }
        fill {
            moveTo(13.5f, 3.5f)
            lineTo(18f, 5.5f)
            lineTo(14.5f, 8.5f)
            close()
        }
    }

    /** Exchange with Attacca: two arrows. */
    val Exchange = icon("Exchange") {
        stroke {
            moveTo(4f, 8f)
            lineTo(19f, 8f)
            moveTo(15f, 4f)
            lineTo(19f, 8f)
            lineTo(15f, 12f)
            moveTo(20f, 16f)
            lineTo(5f, 16f)
            moveTo(9f, 12f)
            lineTo(5f, 16f)
            lineTo(9f, 20f)
        }
    }

    /** Settings: sliders. */
    val Settings = icon("Settings") {
        stroke {
            for ((y, knob) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                moveTo(3f, y)
                lineTo(21f, y)
                moveTo(knob, y - 2.5f)
                lineTo(knob, y + 2.5f)
            }
        }
    }
}
