package com.sadurni.attacca.diorama

import com.sadurni.attacca.festive.Festivity

/**
 * O'rama's festive sky (and the new year's show) alone, as a layer to lay over another picture
 * (Attacca World): the sleigh, the Kings' star, sky lanterns, the crescent, the dragon, cranes;
 * the show's fireworks and the greeting's lights. Nothing of O'rama's land or its sun.
 */
class FestiveLayer {
    val list = DrawList()
    private val moves = Moves()
    private val live = LiveLevels()

    /**
     * Draws a frame [w] × [h] at [nowMs] into [list] (cleared first) and returns whether anything
     * was drawn: [festivity] (null: none) with the Kings ([kings]), and the show [celebration].
     */
    fun frame(w: Float, h: Float, look: SongLook, nowMs: Long, festivity: Festivity?, kings: Boolean, celebration: Celebration?): Boolean {
        list.clear()
        if (festivity == null && celebration == null) return false
        val scene = Scene(list, w, h, look, nowMs, true, live, moves, 0.0)
        scene.overlay = true
        scene.festive = festivity
        scene.kings = kings
        scene.show = celebration
        scene.festiveSky()
        scene.celebration()
        return true
    }
}
