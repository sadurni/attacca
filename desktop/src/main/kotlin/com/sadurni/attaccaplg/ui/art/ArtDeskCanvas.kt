package com.sadurni.attaccaplg.ui.art

import com.sadurni.attacca.art.ArtMark
import com.sadurni.attacca.art.ArtPainter
import com.sadurni.attacca.art.ArtSong
import com.sadurni.attacca.art.ArtStyle
import com.sadurni.attacca.art.ArtSubject
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.min

/**
 * Art-tacca's canvas on the desktop, drawn by Skia (the same marks as the phone's, as Skia's own
 * shaders): the pigments multiply on the paper as watercolour does; a mark spreads while wet (drawn
 * each frame over the canvas) and, once dry, is painted into the canvas for good. All of it on one
 * thread of its own ([frame]).
 */
class ArtDeskCanvas {
    val painter = ArtPainter()

    private var canvas: Surface? = null
    private var canvasW = 0
    private var canvasH = 0
    private var paper: Image? = null
    private var paperKey = ""

    /** The canvas painted over (painting over), or null. */
    private var base: Image? = null

    /** Big marks painted at once into the canvas while still wet (too costly to draw each frame). */
    private val early = HashSet<Long>()

    /** Each wet mark painted once, whole, by its shader; while it spreads only this picture is drawn (grown, laid or faded in), and dry it goes into the canvas as it is. */
    private val sprites = HashMap<Long, Image>()
    private val pending = ArrayList<ArtMark>()

    private val markEffect = RuntimeEffect.makeForShader(MARK_SKSL)
    private val paperEffect = RuntimeEffect.makeForShader(PAPER_SKSL)

    /**
     * One frame for a view of [viewW] × [viewH]: a new painting when the song (or the style) changed,
     * the painter's step, the dried marks into the canvas, and the picture to show (paper, canvas,
     * wet marks), at the canvas's size. [onFinished] gets the painting before when one ends.
     */
    fun frame(
        song: ArtSong?,
        songMs: Double,
        playing: Boolean,
        level: Float,
        low: Float,
        high: Float,
        onsetAtMs: Long,
        notes: FloatArray?,
        nowMs: Long,
        style: ArtStyle,
        paintOver: Boolean,
        viewW: Int,
        viewH: Int,
        subject: ArtSubject = ArtSubject.FREE,
        likeness: Float = 1f,
        onFinished: (Image, String) -> Unit,
    ): Image? {
        painter.likeness = likeness
        if (song != null && (song.id != painter.song?.id || style != painter.chosen || subject != painter.subject)) newPainting(song, nowMs, style, paintOver, viewW, viewH, onFinished, subject)
        if (painter.song == null) return null
        // The painting takes the window's shape when it changes much: laid out again.
        val (w, h) = canvasSizeFor(viewW, viewH)
        val rc = canvasW / canvasH.toFloat()
        val rs = w / h.toFloat()
        if (max(rc / rs, rs / rc) > 1.25f) {
            painter.resize(w.toFloat(), h.toFloat())
            clearSprites()
            makeCanvas(w, h)
            repaint(nowMs)
        }
        painter.frame(nowMs, max(0.0, songMs), playing && songMs >= 0, level, low, high, onsetAtMs, notes)
        pending.clear()
        painter.takeDried(pending)
        val c = canvas?.canvas ?: return null
        for (m in pending) {
            val sprite = sprites.remove(keyOf(m))
            if (early.remove(keyOf(m))) { sprite?.close(); continue }
            if (sprite != null) {
                drawSprite(c, m, sprite, 1f)
                sprite.close()
            } else {
                drawMark(c, m, nowMs, wet = false)
            }
        }
        return picture(nowMs, withWet = true)
    }

    /** The painting as it is now (paper, canvas and wet marks), at the canvas's size. */
    fun picture(nowMs: Long, withWet: Boolean): Image? {
        val cs = canvas ?: return null
        val shown = Surface.makeRasterN32Premul(canvasW, canvasH)
        val c = shown.canvas
        c.drawImage(paperImage(), 0f, 0f)
        c.drawImage(cs.makeImageSnapshot(), 0f, 0f, Paint().apply { blendMode = BlendMode.MULTIPLY })
        if (withWet) for (m in painter.wet) if (keyOf(m) !in early) {
            val sprite = sprites.getOrPut(keyOf(m)) { spriteOf(m) }
            drawSprite(c, m, sprite, m.growth(nowMs))
        }
        return shown.makeImageSnapshot()
    }

    private fun newPainting(song: ArtSong, nowMs: Long, style: ArtStyle, paintOver: Boolean, viewW: Int, viewH: Int, onFinished: (Image, String) -> Unit, subject: ArtSubject) {
        val old = painter.song
        val cs = canvas
        if (old != null && cs != null) {
            painter.dryAll()
            pending.clear()
            painter.takeDried(pending)
            for (m in pending) if (!early.remove(keyOf(m))) drawMark(cs.canvas, m, nowMs, wet = false)
            if (painter.history.size > 12) picture(nowMs, withWet = false)?.let { onFinished(it, old.id) }
        }
        clearSprites()
        base = if (paintOver && old != null && cs != null) cs.makeImageSnapshot() else null
        early.clear()
        val (w, h) = canvasSizeFor(viewW, viewH)
        painter.start(song, w.toFloat(), h.toFloat(), nowMs, veil = base != null, style = style, subject = subject)
        makeCanvas(w, h)
        repaint(nowMs)
    }

    /** The canvas painted again: its ground, what it was painted over, and every dried mark. */
    private fun repaint(nowMs: Long) {
        val cs = canvas ?: return
        val c = cs.canvas
        val g = painter.ground
        c.clear(Color.makeARGB(255, (g[0] * 255).toInt(), (g[1] * 255).toInt(), (g[2] * 255).toInt()))
        base?.let { b -> c.drawImageRect(b, Rect.makeWH(b.width.toFloat(), b.height.toFloat()), Rect.makeWH(canvasW.toFloat(), canvasH.toFloat()), SamplingMode.LINEAR, null, true) }
        pending.clear()
        painter.takeDried(pending)
        early.clear()
        for (m in painter.history) drawMark(c, m, nowMs, wet = false)
    }

    private fun canvasSizeFor(w: Int, h: Int): Pair<Int, Int> {
        val scale = min(1f, MAX_SIDE / max(1, max(w, h)).toFloat())
        return max(1, (w * scale).toInt()) to max(1, (h * scale).toInt())
    }

    private fun makeCanvas(w: Int, h: Int) {
        if (canvas != null && w == canvasW && h == canvasH) return
        canvas?.close()
        canvas = Surface.makeRasterN32Premul(w, h)
        canvasW = w
        canvasH = h
    }

    /** The paper of the painting's style, made once for each size. */
    private fun paperImage(): Image {
        val sheet = when (painter.style) {
            ArtStyle.INK -> floatArrayOf(0.985f, 0.98f, 0.965f)
            ArtStyle.PEN -> floatArrayOf(0.99f, 0.988f, 0.978f)
            else -> painter.song?.palette?.paper ?: floatArrayOf(0.972f, 0.958f, 0.925f)
        }
        val key = "$canvasW×$canvasH ${painter.style} ${painter.squared} ${sheet.joinToString()}"
        paper?.takeIf { paperKey == key }?.let { return it }
        val s = Surface.makeRasterN32Premul(canvasW, canvasH)
        val b = RuntimeShaderBuilder(paperEffect)
        b.uniform("uSize", canvasW.toFloat(), canvasH.toFloat())
        b.uniform("uStyle", painter.style.ordinal.toFloat())
        b.uniform("uSquared", if (painter.squared) 1f else 0f)
        b.uniform("uPaper", sheet[0], sheet[1], sheet[2])
        b.uniform("uGrain", GRAIN / max(canvasW, canvasH).toFloat())
        s.canvas.drawRect(Rect.makeWH(canvasW.toFloat(), canvasH.toFloat()), Paint().apply { shader = b.makeShader() })
        paper?.close()
        paper = s.makeImageSnapshot()
        paperKey = key
        s.close()
        return paper!!
    }

    /** One mark onto [c]: pigments multiply, lines keep the darker, thick paint and the veil lie over. */
    private fun drawMark(c: Canvas, m: ArtMark, nowMs: Long, wet: Boolean, dry: Boolean = false) {
        val g = if (wet && !dry) m.growth(nowMs) else 1f
        val ext = extentOf(m)
        val paint = Paint().apply {
            shader = shaderOf(m, ext, g)
            isAntiAlias = false
            blendMode = blendOf(m)
        }
        c.save()
        c.translate(m.x, m.y)
        c.rotate(Math.toDegrees(m.angle.toDouble()).toFloat())
        c.scale(m.rx * ext[0], m.ry * ext[1])
        c.drawRect(Rect.makeLTRB(-1f, -1f, 1f, 1f), paint)
        c.restore()
    }

    private fun keyOf(m: ArtMark): Long = m.bornMs * 31L + (m.seed * 1_000_000f).toLong() * 17L + m.kind

    private fun clearSprites() {
        for (i in sprites.values) i.close()
        sprites.clear()
    }

    private fun extentOf(m: ArtMark): FloatArray = when (m.kind) {
        ArtMark.BLOOM -> floatArrayOf(1.45f, 1.45f)
        ArtMark.DROP -> floatArrayOf(1.15f, 1.15f)
        ArtMark.GLAZE -> floatArrayOf(1.25f, 1.25f)
        ArtMark.LINE, ArtMark.INK -> floatArrayOf(1f, 1.8f)
        ArtMark.DAB, ArtMark.OIL -> floatArrayOf(1f, 1.3f + 0.3f * m.rx / max(m.ry, 0.001f))
        ArtMark.DOT -> floatArrayOf(1.2f, 1.2f)
        ArtMark.PEN -> floatArrayOf(1f, 2.2f)
        ArtMark.PENCIL -> floatArrayOf(1.25f, 1.25f)
        else -> floatArrayOf(1f, 1f)
    }

    /** [m] whole (dry), in its own square, at the canvas's scale. */
    private fun spriteOf(m: ArtMark): Image {
        val ext = extentOf(m)
        val w = (2f * m.rx * ext[0]).toInt().coerceIn(2, 4096)
        val h = (2f * m.ry * ext[1]).toInt().coerceIn(2, 4096)
        val s = Surface.makeRasterN32Premul(w, h)
        val c = s.canvas
        c.translate(w / 2f, h / 2f)
        c.scale(w / 2f, h / 2f)
        val paint = Paint().apply {
            shader = shaderOf(m, ext, 1f)
            isAntiAlias = false
            blendMode = BlendMode.SRC
        }
        c.drawRect(Rect.makeLTRB(-1f, -1f, 1f, 1f), paint)
        val image = s.makeImageSnapshot()
        s.close()
        return image
    }

    /** [sprite] of [m] onto [c] at growth [g]: blots and washes spread, strokes are laid from one end, the rest fade in. */
    private fun drawSprite(c: Canvas, m: ArtMark, sprite: Image, g: Float) {
        val ext = extentOf(m)
        var scale = 1f
        var alpha = 1f
        var reveal = 2f
        when (m.kind) {
            ArtMark.BLOOM -> { scale = 0.35f + 0.65f * (1f - (1f - g) * (1f - g)); alpha = 0.6f + 0.4f * g }
            ArtMark.DROP -> { scale = 0.5f + 0.5f * g; alpha = 0.7f + 0.3f * g }
            ArtMark.GLAZE -> { scale = 0.55f + 0.45f * g; alpha = 0.5f + 0.5f * g }
            ArtMark.DAB, ArtMark.OIL, ArtMark.PEN -> reveal = -1f + 2.2f * g
            ArtMark.PENCIL -> reveal = -1.2f + 2.6f * g
            else -> alpha = g
        }
        if (reveal <= -1f || alpha <= 0f) return
        val paint = Paint().apply {
            this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            blendMode = blendOf(m)
        }
        c.save()
        var x = m.x
        var y = m.y
        if (m.kind == ArtMark.PEN && g < 1f) {
            val f = kotlin.math.floor(g * 7f) + m.seed * 50f
            x += tremble(f) * m.ry * 2.5f
            y += tremble(f + 3.7f) * m.ry * 2.5f
        }
        c.translate(x, y)
        c.rotate(Math.toDegrees(m.angle.toDouble()).toFloat())
        val hw = m.rx * ext[0] * scale
        val hh = m.ry * ext[1] * scale
        if (reveal < 1f) c.clipRect(Rect.makeLTRB(-hw, -hh, hw * reveal, hh))
        c.drawImageRect(sprite, Rect.makeWH(sprite.width.toFloat(), sprite.height.toFloat()), Rect.makeLTRB(-hw, -hh, hw, hh), SamplingMode.LINEAR, paint, false)
        c.restore()
    }

    private fun blendOf(m: ArtMark): BlendMode = when (m.kind) {
        ArtMark.VEIL, ArtMark.OIL, ArtMark.DOT -> BlendMode.SRC_OVER
        ArtMark.INK, ArtMark.LINE, ArtMark.PEN -> BlendMode.DARKEN
        else -> BlendMode.MULTIPLY
    }

    private fun shaderOf(m: ArtMark, ext: FloatArray, g: Float): org.jetbrains.skia.Shader {
        val b = RuntimeShaderBuilder(markEffect)
        b.uniform("uExt", ext[0], ext[1])
        b.uniform("uRadii", m.rx, m.ry)
        b.uniform("uCentre", m.x, m.y)
        b.uniform("uAngle", m.angle)
        b.uniform("uColour", m.r, m.g, m.b, m.alpha)
        b.uniform("uParams", m.kind.toFloat(), m.seed, g)
        b.uniform("uGrain", GRAIN / max(canvasW, canvasH).toFloat())
        return b.makeShader()
    }

    private fun tremble(n: Float): Float {
        val v = kotlin.math.sin(n * 12.9898f) * 43758.5453f
        return v - kotlin.math.floor(v) - 0.5f
    }

    fun close() {
        canvas?.close()
        paper?.close()
    }

    companion object {
        /** The canvas's longest side, pixels (drawn by the processor). */
        const val MAX_SIDE = 1280f
        const val GRAIN = 700f

        private const val NOISE = """
float hash(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float vnoise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) { float s = 0.0; float a = 0.5; for (int i = 0; i < 4; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; } return s; }
float paper(float2 c) { float2 q = c * uGrain; return fbm(q * 0.35) * 0.6 + vnoise(q * 1.7) * 0.4; }
"""

        /** Each mark (the same as the phone's, in Skia's shading language; coord: the mark's own square, -1 … 1). */
        val MARK_SKSL = """
uniform float2 uExt;
uniform float2 uRadii;
uniform float2 uCentre;
uniform float uAngle;
uniform float4 uColour;
uniform float3 uParams;
uniform float uGrain;
$NOISE
float oilH(float2 p, float seed, float r1, float r2, float r3, float bend, float g) {
    float x = p.x;
    float yb = p.y - bend * (x * x - 0.33);
    float w = pow(max(0.0, 1.0 - pow(abs(x), 2.0 + 4.0 * r2)), 0.5) * (0.82 + 0.18 * vnoise(float2(x * 4.0, seed)));
    w *= mix(1.0, 0.55 + 0.45 * (1.0 - x) * 0.5 + 0.2, r3);
    float rough = (vnoise(float2(x * (6.0 + 10.0 * r1) + seed, seed * 2.0)) - 0.5) * 0.28;
    float across = abs(yb) / max(w + rough, 0.01);
    float ridges = vnoise(float2(x * 1.2 + seed, yb * (6.0 + 6.0 * r2) + seed * 3.0));
    return ridges * 0.75 + (1.0 - across * across) * 0.45;
}
half4 main(float2 coord) {
    float2 p = coord * uExt;
    float cs = cos(uAngle);
    float sn = sin(uAngle);
    float2 off = p * uRadii;
    float2 vCanvas = uCentre + float2(off.x * cs - off.y * sn, off.x * sn + off.y * cs);
    float kind = uParams.x;
    float seed = uParams.y * 97.0;
    float g = uParams.z;
    float r1 = fract(uParams.y * 7.13);
    float r2 = fract(uParams.y * 13.71);
    float r3 = fract(uParams.y * 31.17);
    float bend = (r1 - 0.5) * 1.3 * uRadii.x / max(uRadii.y, 0.001) * 0.25;
    float a = 0.0;
    if (kind > 5.5 && kind < 6.5) {
        return half4(half3(uColour.rgb * uColour.a), half(uColour.a));
    }
    if (kind > 7.5 && kind < 9.5) {
        // Oil: thick paint, its ridges catching a light from the top left.
        float h;
        float edge;
        if (kind < 8.5) {
            float x = p.x;
            if (x > -1.0 + 2.2 * g) return half4(0.0);
            float yb = p.y - bend * (x * x - 0.33);
            float w = pow(max(0.0, 1.0 - pow(abs(x), 2.0 + 4.0 * r2)), 0.5) * (0.82 + 0.18 * vnoise(float2(x * 4.0, seed)));
            w *= mix(1.0, 0.55 + 0.45 * (1.0 - x) * 0.5 + 0.2, r3);
            float rough = (vnoise(float2(x * (6.0 + 10.0 * r1) + seed, seed * 2.0)) - 0.5) * 0.28;
            float across = abs(yb) / max(w + rough, 0.01);
            if (across > 1.0) return half4(0.0);
            float ridges = vnoise(float2(x * 1.2 + seed, yb * (6.0 + 6.0 * r2) + seed * 3.0));
            if (ridges < smoothstep(0.45, 1.0, x) * 0.65) return half4(0.0);
            h = ridges * 0.75 + (1.0 - across * across) * 0.45;
            float h2 = oilH(p + float2(-0.03, 0.03 * uRadii.x / max(uRadii.y, 0.001)), seed, r1, r2, r3, bend, g);
            float light = clamp(0.5 + (h - h2) * 3.0, 0.0, 1.0);
            edge = across;
            float3 col = uColour.rgb * (0.8 + 0.4 * light) + (h - 0.5) * 0.05;
            float al = uColour.a * (1.0 - smoothstep(0.86, 1.0, edge));
            return half4(half3(clamp(col, 0.0, 1.0) * al), half(al));
        } else {
            float ang = atan(p.y, p.x);
            float d = length(p) / (1.0 + 0.16 * (vnoise(float2(ang * 1.6, seed)) - 0.5));
            if (d > 1.0) return half4(0.0);
            h = (1.0 - d * d) * 0.8 + vnoise(p * 3.0 + seed) * 0.3;
            float light = clamp(0.5 + (0.6 - d) * 0.6 - p.x * 0.3 - p.y * 0.3, 0.0, 1.0);
            float3 col = uColour.rgb * (0.8 + 0.4 * light) + (h - 0.5) * 0.05;
            float al = uColour.a * (1.0 - smoothstep(0.86, 1.0, d));
            return half4(half3(clamp(col, 0.0, 1.0) * al), half(al));
        }
    }
    if (kind > 9.5 && kind < 10.5) {
        // A ballpoint: an even line drawn from one end, pressing harder and lighter, skipping a little.
        float across = abs(p.y);
        if (abs(p.x) > 1.0 || across > 1.0) return half4(0.0);
        if (p.x > -1.0 + 2.2 * max(g, 0.0001)) return half4(0.0);
        float press = 0.72 + 0.28 * vnoise(float2(p.x * 3.0 + seed * 7.0, seed));
        float skip = step(0.1, vnoise(float2(p.x * 22.0 + seed * 13.0, 0.3)));
        a = (1.0 - smoothstep(0.4, 1.0, across)) * press * mix(0.55, 1.0, skip);
    } else if (kind > 10.5) {
        // Coloured pencil: close hatching over an area, its grain showing, its edge loose.
        if (p.x > -1.2 + 2.6 * g) return half4(0.0);
        float d = length(p) + (fbm(p * 1.4 + seed) - 0.5) * 0.4;
        if (d > 1.0) return half4(0.0);
        float2 px = p * uRadii;
        float ang = r1 * 3.14159;
        float u = px.x * cos(ang) + px.y * sin(ang);
        float v = abs(fract(u / 4.0) - 0.5) * 2.0;
        a = smoothstep(0.35, 0.75, v) * (0.45 + 0.75 * paper(vCanvas)) * (1.0 - smoothstep(0.7, 1.0, d));
    } else if (kind < 0.5 || (kind > 3.5 && kind < 4.5)) {
        // A blot: spreads as it is wet, its rim uneven; pigment pools at the edge as it dries.
        bool blot = kind < 0.5;
        float grow = blot ? 0.35 + 0.65 * (1.0 - (1.0 - g) * (1.0 - g)) : 0.5 + 0.5 * g;
        float ang = atan(p.y, p.x);
        float amp = blot ? 0.14 + 0.32 * r1 : 0.08 + 0.12 * r1;
        float rim = 1.0 + amp * (fbm(float2(cos(ang), sin(ang)) * (1.0 + 2.4 * r2) + seed) - 0.5) * 2.0;
        float d = length(p) / (grow * rim);
        if (d > 1.0) return half4(0.0);
        float body = 0.45 + 0.55 * fbm(p * (1.4 + 2.0 * r3) + seed);
        float edge = smoothstep(0.55 + 0.1 * r2, 0.98, d) * (blot ? 0.95 : 0.6) * g;
        if (blot && r3 > 0.62) {
            float ring = 0.4 + 0.25 * r2 + (fbm(p * 3.0 + seed * 1.7) - 0.5) * 0.25;
            edge += 0.7 * g * (1.0 - smoothstep(0.0, 0.06, abs(d - ring)));
        }
        float soft = 1.0 - smoothstep(0.9, 1.0, d);
        a = (body * 0.65 + edge) * soft * (0.7 + 0.6 * paper(vCanvas));
    } else if (kind < 1.5) {
        // A brush stroke, laid from one end: bent, tapered its own way, its bristles in streaks.
        float x = p.x;
        float y = p.y - bend * (x * x - 0.33);
        if (x > -1.0 + 2.2 * g) return half4(0.0);
        float taper = pow(max(0.0, 1.0 - x * x), 0.3 + 0.6 * r2) * (0.75 + 0.25 * vnoise(float2(x * 3.0, seed)));
        taper *= mix(1.0, 1.25 - 0.45 * (x * 0.5 + 0.5), r3);
        float rough = (vnoise(float2(x * (5.0 + 9.0 * r1) + seed, seed)) - 0.5) * 0.36;
        float across = abs(y) / max(taper + rough, 0.01);
        if (across > 1.0) return half4(0.0);
        float bristle = vnoise(float2(x * 1.3 + seed, y * 7.0 + seed * 3.0));
        float dry = smoothstep(0.35, 1.0, x);
        if (bristle < dry * 0.75) return half4(0.0);
        a = (0.5 + 0.5 * bristle) * (0.75 + 0.5 * paper(vCanvas)) * (1.0 + 0.35 * smoothstep(0.55, 1.0, across));
        a *= 1.0 - smoothstep(0.85, 1.0, across);
    } else if (kind < 2.5) {
        // A dry-brush line: broken where the brush skips over the paper's grain.
        float x = p.x;
        if (abs(x) > 1.0) return half4(0.0);
        float w = 1.0 - 0.35 * abs(x);
        float across = abs(p.y) / w;
        if (across > 1.0) return half4(0.0);
        float skip = vnoise(float2(x * 7.0 + seed * 5.0, 0.5)) + paper(vCanvas) * 0.5 - 0.25;
        a = smoothstep(0.3, 0.5, skip) * (1.0 - smoothstep(0.5, 1.0, across));
    } else if (kind < 3.5) {
        // A wash: broad, its edge lost in clouds of pigment.
        float d = length(p) / (0.55 + 0.45 * g);
        float n = fbm(p * 1.3 + seed);
        float shape = 1.0 - smoothstep(0.3, 1.0, d + (n - 0.5) * 0.55);
        a = shape * (0.55 + 0.55 * fbm(p * 3.0 + seed * 2.0)) * (0.8 + 0.4 * paper(vCanvas));
    } else if (kind < 5.5) {
        // The seal: a square stamp with its mark cut in, grainy as stamped ink.
        float m = max(abs(p.x), abs(p.y));
        if (m > 1.0) return half4(0.0);
        float2 q = p * 0.5 + 0.5;
        float border = step(0.8, m);
        float2 cell = floor(q * float2(3.0, 4.0));
        float on = step(0.45, hash(cell + seed));
        float2 f = fract(q * float2(3.0, 4.0));
        float inner = step(m, 0.66) * on * step(0.18, f.x) * step(f.x, 0.82) * step(0.15, f.y) * step(f.y, 0.85);
        a = max(border, inner) * (0.5 + 0.7 * vnoise(vCanvas * 0.8)) * (0.3 + 0.7 * g);
    } else {
        // The pen: an even line with soft sides, ink a little uneven on the paper.
        float across = abs(p.y);
        if (abs(p.x) > 1.0 || across > 1.0) return half4(0.0);
        a = (1.0 - smoothstep(0.55, 1.0, across)) * (0.8 + 0.35 * paper(vCanvas));
    }
    a = clamp(a * uColour.a, 0.0, 1.0);
    return half4(half3(mix(float3(1.0), uColour.rgb, a)), 1.0);
}
"""

        /** The paper of each style: watercolour paper, an oil canvas's weave, ink paper's fibres, a notebook's page. */
        val PAPER_SKSL = """
uniform float2 uSize;
uniform float uStyle;
uniform float uSquared;
uniform float3 uPaper;
uniform float uGrain;
$NOISE
half4 main(float2 c) {
    float2 v = c / uSize - 0.5;
    float vignette = 1.0 - 0.1 * dot(v, v) * 2.0;
    if (uStyle > 1.5 && uStyle < 2.5) {
        float k = 1.7 * max(uSize.x, uSize.y) / 1600.0;
        float weave = 0.5 + 0.25 * sin(c.x * 1.9 / k) + 0.25 * sin(c.y * 1.9 / k + 1.3 * sin(c.x * 0.05));
        return half4(half3(float3(0.93 + 0.09 * weave) * vignette), 1.0);
    }
    float3 sheet = uPaper * (0.955 + 0.06 * paper(c));
    if (uStyle > 2.5 && uStyle < 3.5) sheet *= 0.975 + 0.035 * vnoise(float2(c.x * 0.015, c.y * 0.6));
    if (uStyle > 3.5) {
        float sp = max(uSize.x, uSize.y) / 42.0;
        float3 blue = float3(0.6, 0.72, 0.9);
        float dy = abs(c.y - floor(c.y / sp + 0.5) * sp);
        sheet = mix(sheet, sheet * blue, (1.0 - smoothstep(0.5, 1.4, dy)) * 0.6);
        if (uSquared > 0.5) {
            float dx = abs(c.x - floor(c.x / sp + 0.5) * sp);
            sheet = mix(sheet, sheet * blue, (1.0 - smoothstep(0.5, 1.4, dx)) * 0.5);
        } else {
            float dm = abs(c.x - uSize.x * 0.11);
            sheet = mix(sheet, sheet * float3(0.96, 0.55, 0.55), (1.0 - smoothstep(0.6, 1.6, dm)) * 0.75);
        }
    }
    return half4(half3(sheet * vignette), 1.0);
}
"""
    }
}

/** The painting's signature: [title] written small in an italic hand, "Art-tacca" smaller under it (the pixels' alpha is the ink). */
fun titleImageOf(title: String?): com.sadurni.attacca.art.ArtCover? = runCatching {
    val t = title?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 48) it.take(47).trimEnd() + "…" else it } ?: return null
    val by = "Art-tacca"
    val font = java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 22)
    val smallFont = font.deriveFont(17f)
    val probe = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics()
    val fm = probe.getFontMetrics(font)
    val sm = probe.getFontMetrics(smallFont)
    val w = maxOf(fm.stringWidth(t), sm.stringWidth(by)) + 4
    val line = fm.ascent + fm.descent
    val h = line + sm.ascent + sm.descent + 2
    probe.dispose()
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = java.awt.Color.WHITE
    // Both lines end at the right, by the seal.
    g.font = font
    g.drawString(t, w - 2 - fm.stringWidth(t), fm.ascent + 1)
    g.font = smallFont
    g.drawString(by, w - 2 - sm.stringWidth(by), line + sm.ascent)
    g.dispose()
    com.sadurni.attacca.art.ArtCover(w, h, img.getRGB(0, 0, w, h, null, 0, w))
}.getOrNull()
