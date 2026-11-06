package com.sadurni.attacca.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.sadurni.attacca.settings.ShakeKind
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calls [onShake] when the phone is shaken the chosen way ([ShakeKind]) beyond a strength
 * (1 light … 3 strong), a few times within a moment:
 * - side to side or back and forth: swings along one axis (the phone's width, or through its
 *   screen), one way and the other in turn; moving it up and down (the "chop" some phones use
 *   for the torch), twisting it, walking or a bump do not count;
 * - any direction: a few strong jolts, however they go.
 * Listens only between [start] and [stop].
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var kind = ShakeKind.SIDEWAYS
    private var strength = 2
    private var listening = false
    private val moves = ArrayDeque<Long>()
    private var lastSide = 0
    private var lastMoveAt = 0L
    private var lastShakeAt = 0L

    /** Whether this phone can tell a shake (it has an accelerometer). */
    val available: Boolean get() = accelerometer != null

    fun start(kind: ShakeKind, strength: Int) {
        if (kind != this.kind) reset()
        this.kind = kind
        this.strength = strength
        if (listening || accelerometer == null) return
        listening = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!listening) return
        sensors.unregisterListener(this)
        listening = false
        reset()
    }

    private fun reset() {
        moves.clear()
        lastSide = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val now = SystemClock.elapsedRealtime()
        val counted = when (kind) {
            ShakeKind.SIDEWAYS -> swing(x, y, z, now)
            ShakeKind.BACK_AND_FORTH -> swing(z, y, x, now)
            ShakeKind.ANY -> jolt(sqrt(x * x + y * y + z * z), now)
        }
        if (!counted) return
        moves.addLast(now)
        while (moves.isNotEmpty() && now - moves.first() > WINDOW_MS) moves.removeFirst()
        val needed = if (kind == ShakeKind.ANY) JOLTS else SWINGS
        if (moves.size >= needed && now - lastShakeAt > COOLDOWN_MS) {
            lastShakeAt = now
            reset()
            onShake()
        }
    }

    /** A new swing along [axis] (m/s²): beyond the strength, mostly along it, the other way from the last. */
    private fun swing(axis: Float, other1: Float, other2: Float, now: Long): Boolean {
        if (abs(axis) < SWING_THRESHOLD[strength - 1]) return false
        // A chop or a twist moves the phone more along the other axes (gravity aside).
        if (abs(axis) < abs(other1) - SensorManager.GRAVITY_EARTH && abs(axis) < abs(other2) - SensorManager.GRAVITY_EARTH) return false
        val side = if (axis > 0) 1 else -1
        if (side == lastSide) return false
        lastSide = side
        lastMoveAt = now
        return true
    }

    /** A new jolt: acceleration beyond the strength (in g), not the same jolt read again. */
    private fun jolt(magnitude: Float, now: Long): Boolean {
        if (magnitude / SensorManager.GRAVITY_EARTH < JOLT_THRESHOLD_G[strength - 1] || now - lastMoveAt < MIN_JOLT_GAP_MS) return false
        lastMoveAt = now
        return true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Swings, one way and the other in turn, that make a shake within [WINDOW_MS]. */
        const val SWINGS = 4
        /** Jolts in any direction that make a shake within [WINDOW_MS]. */
        const val JOLTS = 3
        const val WINDOW_MS = 1_500L
        /** One jolt lasts several readings: they count once. */
        const val MIN_JOLT_GAP_MS = 120L
        /** After a shake, the next one is a new list only after a while. */
        const val COOLDOWN_MS = 4_000L
        /** Light, medium, strong: m/s² along the axis; gravity alone stays below. */
        val SWING_THRESHOLD = floatArrayOf(12f, 16f, 22f)
        /** Light, medium, strong: whole acceleration in g (about 1 at rest). */
        val JOLT_THRESHOLD_G = floatArrayOf(1.9f, 2.4f, 3.0f)
    }
}
