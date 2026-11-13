package com.sadurni.attacca.world

import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.tan

/**
 * Where the things of the peak's show that move over the sky are (the Great Wall's dragon), so
 * the eyes can follow them: as a turn from straight ahead (radians, positive to the left as the
 * camera's yaw) and back to a place on the screen.
 *
 * The sky's show is placed on the screen as q = (x across, in units of the vertical half view, 0
 * in the middle). [tanHalf] is tan(the vertical view / 2).
 */
object SkyShow {
    /** The dragon's head across the screen (q) at [timeS] seconds, flying right to left. */
    fun dragonX(timeS: Float, aspect: Float): Float {
        val u = timeS / 9f
        return (1.2f - (u - floor(u)) * 2.4f) * aspect
    }

    /** The turn (yaw) at which something at [q] across is, looking straight ahead. */
    fun turnOf(q: Float, tanHalf: Float): Float = -atan(q * tanHalf)

    /** Where something at turn [at] is across the screen (q) with the eyes turned by [turned]. */
    fun screenX(at: Float, turned: Float, tanHalf: Float): Float =
        -tan((at - turned).coerceIn(-1.45f, 1.45f)) / tanHalf
}
