package com.sadurni.attaccaplg.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Development: the phone's Now playing of some themes on every screen size, to PNG files. Args: output folder. */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val themes = listOf(Themes.of("dark")) + Themes.gallery
    for (theme in themes) for (landscape in listOf(false, true)) {
        val scene = ImageComposeScene(if (landscape) 2400 else 1800, if (landscape) 420 else 700, Density(1f)) {
            AppTheme("dark", null, false) { PhoneScreensForShots(theme, landscape) }
        }
        scene.render(0)
        Thread.sleep(400)
        scene.render(500_000_000)
        val image = scene.render(1_000_000_000)
        val name = theme.id.substringAfter(':') + if (landscape) "-land" else ""
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("$name.png")
    }
    System.exit(0)
}
