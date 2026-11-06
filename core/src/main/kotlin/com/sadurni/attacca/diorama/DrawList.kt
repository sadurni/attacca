package com.sadurni.attacca.diorama

/**
 * What one frame of the diorama draws, in order: plain shapes in ARGB colours, kept in reused
 * arrays so a frame allocates nothing. Each app paints it with its own canvas.
 */
class DrawList {

    /** Shape kinds. */
    object Kind {
        /** Filled polygon: points x0, y0, x1, y1… */
        const val POLYGON = 0

        /** Filled rectangle: left, top, right, bottom. */
        const val RECT = 1

        /** Filled circle: centre x, y, radius. */
        const val CIRCLE = 2

        /** Open line through points, [width] wide (round ends). */
        const val LINE = 3

        /** Circle outline: centre x, y, radius, [width] wide. */
        const val RING = 4

        /** Rectangle filled from [color] at the top to [color2] at the bottom: left, top, right, bottom. */
        const val GRADIENT = 5

        /**
         * The track's cover: centre x, y, side, turn (radians, clockwise). Painted in [color]'s
         * opacity; without a cover, a square of [color].
         */
        const val COVER = 6

        /** One line of text ([texts]) in [color], centred on x, y: x, y, height, turn (radians, clockwise). */
        const val TEXT = 7
    }

    var size = 0
        private set
    var kinds = IntArray(512)
        private set
    var colors = IntArray(512)
        private set
    var colors2 = IntArray(512)
        private set
    var widths = FloatArray(512)
        private set
    var texts = arrayOfNulls<String>(512)
        private set

    /**
     * The camera, for the whole frame: scaled [zoom] times around [pivotX], [pivotY], then moved
     * by [shiftX], [shiftY] (a shake).
     */
    var zoom = 1f
        private set
    var pivotX = 0f
        private set
    var pivotY = 0f
        private set
    var shiftX = 0f
        private set
    var shiftY = 0f
        private set

    fun camera(zoom: Float, pivotX: Float, pivotY: Float, shiftX: Float, shiftY: Float) {
        this.zoom = zoom
        this.pivotX = pivotX
        this.pivotY = pivotY
        this.shiftX = shiftX
        this.shiftY = shiftY
    }

    /** Where each shape's numbers start in [coords], and how many it has. */
    var starts = IntArray(512)
        private set
    var counts = IntArray(512)
        private set
    var coords = FloatArray(8192)
        private set
    private var used = 0

    /** Opacity applied to every colour added (0 … 1): a whole scene fades in or out with it. */
    var fade = 1f

    // A turn applied to the points added while it is set (see [turn]).
    private var turnCos = 1f
    private var turnSin = 0f
    private var turnAngle = 0f
    private var turnX = 0f
    private var turnY = 0f

    fun clear() {
        size = 0
        used = 0
        fade = 1f
        straight()
        camera(1f, 0f, 0f, 0f, 0f)
    }

    /** Turns what is added from now on by [angle] radians (clockwise) around [x], [y], until [straight]. */
    fun turn(angle: Float, x: Float, y: Float) {
        turnAngle = angle
        turnCos = kotlin.math.cos(angle)
        turnSin = kotlin.math.sin(angle)
        turnX = x
        turnY = y
    }

    fun straight() {
        turnAngle = 0f
        turnCos = 1f
        turnSin = 0f
    }

    private val turned get() = turnAngle != 0f

    private fun shape(kind: Int, color: Int, width: Float = 0f, color2: Int = 0) {
        if (size == kinds.size) {
            val n = size * 2
            kinds = kinds.copyOf(n)
            colors = colors.copyOf(n)
            colors2 = colors2.copyOf(n)
            widths = widths.copyOf(n)
            texts = texts.copyOf(n)
            starts = starts.copyOf(n)
            counts = counts.copyOf(n)
        }
        kinds[size] = kind
        colors[size] = faded(color)
        colors2[size] = faded(color2)
        widths[size] = width
        texts[size] = null
        starts[size] = used
        counts[size] = 0
        size++
    }

    private fun faded(color: Int): Int {
        if (fade >= 1f) return color
        val a = ((color ushr 24) * fade.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private fun put(v: Float) {
        if (used == coords.size) coords = coords.copyOf(coords.size * 2)
        coords[used++] = v
        counts[size - 1]++
    }

    /** A point through the turn, if any. */
    private fun putPoint(x: Float, y: Float) {
        if (!turned) {
            put(x); put(y)
            return
        }
        val dx = x - turnX
        val dy = y - turnY
        put(turnX + dx * turnCos - dy * turnSin)
        put(turnY + dx * turnSin + dy * turnCos)
    }

    fun rect(color: Int, left: Float, top: Float, right: Float, bottom: Float) {
        if (turned) {
            polygon(color)
            point(left, top); point(right, top); point(right, bottom); point(left, bottom)
            return
        }
        shape(Kind.RECT, color)
        put(left); put(top); put(right); put(bottom)
    }

    fun gradient(top: Int, bottom: Int, left: Float, y0: Float, right: Float, y1: Float) {
        shape(Kind.GRADIENT, top, color2 = bottom)
        put(left); put(y0); put(right); put(y1)
    }

    fun circle(color: Int, x: Float, y: Float, radius: Float) {
        if (radius <= 0f) return
        shape(Kind.CIRCLE, color)
        putPoint(x, y); put(radius)
    }

    fun ring(color: Int, x: Float, y: Float, radius: Float, width: Float) {
        shape(Kind.RING, color, width)
        putPoint(x, y); put(radius)
    }

    fun line(color: Int, width: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
        shape(Kind.LINE, color, width)
        putPoint(x0, y0); putPoint(x1, y1)
    }

    /** Starts a polygon; add its points with [point]. */
    fun polygon(color: Int) = shape(Kind.POLYGON, color)

    /** Starts an open line [width] wide; add its points with [point]. */
    fun polyline(color: Int, width: Float) = shape(Kind.LINE, color, width)

    fun point(x: Float, y: Float) = putPoint(x, y)

    /** [value] in [color], [height] high, centred on [x], [y] and turned by [angle] (plus the current turn). */
    fun text(color: Int, x: Float, y: Float, height: Float, value: String, angle: Float = 0f) {
        shape(Kind.TEXT, color)
        texts[size - 1] = value
        putPoint(x, y); put(height); put(angle + turnAngle)
    }

    /** The cover, [side] wide, centred on [x], [y] and turned by [angle] (plus the current turn). */
    fun cover(color: Int, x: Float, y: Float, side: Float, angle: Float = 0f) {
        shape(Kind.COVER, color)
        putPoint(x, y); put(side); put(angle + turnAngle)
    }
}
