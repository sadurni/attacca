package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Development: the phone layout editor drawn without a screen, before and after dragging one part
 * with the mouse. Args: output folder, then "x1,y1 x2,y2" (from where to where) for the drag.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    System.getProperty("shots.lang")?.let(Lang::apply)
    Themes.own().filter { it.name == "Drag test" }.forEach(Themes::delete)
    var theme by mutableStateOf(Themes.copy(Themes.of("dark"), "Drag test")!!)
    // A box at the end, to drop into.
    val box = com.sadurni.attacca.settings.PlayerBlock(com.sadurni.attacca.settings.BlockType.BOX, children = listOf(com.sadurni.attacca.settings.PlayerBlock(com.sadurni.attacca.settings.BlockType.SPACE)))
    theme = Themes.save(theme, theme.spec.copy(layout = com.sadurni.attacca.settings.PlayerLayout(theme.spec.layout.blocks + box)))
    val scene = ImageComposeScene(700, 1100, Density(1f)) {
        AppTheme("dark", null, false) {
            Column(Modifier.width(680.dp).padding(8.dp)) {
                LayoutSection(theme) { f -> theme = Themes.save(theme, f(theme.spec)) }
            }
        }
    }
    fun shot(name: String) {
        scene.render(0)
        val image = scene.render(1_000_000_000)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("$name.png: " + theme.spec.layout.blocks.joinToString(" | ") { b -> b.type.name + if (b.children.isEmpty()) "" else b.children.joinToString(",", "(", ")") { it.type.name } })
    }
    shot("drag-before")
    if (args.size > 1) {
        val (from, to) = args[1].split(' ').map { p -> p.split(',').let { Offset(it[0].toFloat(), it[1].toFloat()) } }
        val down = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, from)
        scene.sendPointerEvent(PointerEventType.Press, from, buttons = down)
        for (i in 1..20) {
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 20f), buttons = down)
            scene.render(i * 16_000_000L)
        }
        scene.sendPointerEvent(PointerEventType.Release, to)
        shot("drag-after")
    }
    scene.close()
    System.exit(0)
}
