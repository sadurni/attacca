package com.sadurni.attacca.ui.world

import android.opengl.GLES30

/**
 * Attacca World's finer picture ("World in high quality"): the frame is drawn into a 4x
 * antialiased picture (smooth edges), which is then copied into a texture; from it, the
 * brightest light (the sun, torches, lit windows) is taken at a quarter of the size, blurred and
 * laid back over the frame as a soft glow; and the frame gets a film's tones (a gentle S-curve
 * and edges a little darker) on its way to the screen. All on the GPU's thread.
 */
class WorldPost {
    private var w = 0
    private var h = 0
    private var msaaFbo = 0
    private val msaaBuffers = IntArray(2)
    private var sceneFbo = 0
    private var sceneTex = 0
    private val glowFbo = IntArray(2)
    private val glowTex = IntArray(2)
    private var bright = 0
    private var blur = 0
    private var finish = 0

    /** From now on the frame is drawn into the antialiased picture (made for [width] x [height] when needed). */
    fun begin(width: Int, height: Int) {
        if (width != w || height != h || msaaFbo == 0) make(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES30.glViewport(0, 0, w, h)
    }

    /** The frame drawn: to the screen, smoothed, with its glow and tones. [quad]: a buffer of a screen-wide quad (x, y). */
    fun finish(quad: Int, film: Boolean = true) {
        if (msaaFbo == 0) return
        // The antialiased picture into a texture.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFbo)
        GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        val gw = glowW()
        val gh = glowH()
        // The brightest light, at a quarter of the size.
        pass(glowFbo[0], gw, gh, bright, sceneTex) { p ->
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
        }
        // Blurred across, then up and down.
        pass(glowFbo[1], gw, gh, blur, glowTex[0]) { p -> GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uStep"), 1.5f / gw, 0f) }
        pass(glowFbo[0], gw, gh, blur, glowTex[1]) { p -> GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uStep"), 0f, 1.5f / gh) }
        // The frame with its glow and tones, on the screen.
        pass(0, w, h, finish, sceneTex) { p ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glowTex[0])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uGlow"), 1)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uAspect"), w.toFloat() / h)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uFilm"), if (film) 1f else 0f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private inline fun pass(target: Int, pw: Int, ph: Int, program: Int, source: Int, setUp: (Int) -> Unit) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(0, 0, pw, ph)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uScene"), 0)
        setUp(program)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun glowW() = (w / 4).coerceAtLeast(1)
    private fun glowH() = (h / 4).coerceAtLeast(1)

    private fun make(width: Int, height: Int) {
        release()
        w = width
        h = height
        if (bright == 0) {
            bright = program(POST_VS, BRIGHT_FS)
            blur = program(POST_VS, BLUR_FS)
            finish = program(POST_VS, FINISH_FS)
        }
        GLES30.glGenRenderbuffers(2, msaaBuffers, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaBuffers[0])
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, 4, GLES30.GL_RGBA8, w, h)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaBuffers[1])
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, 4, GLES30.GL_DEPTH_COMPONENT24, w, h)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        msaaFbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msaaBuffers[0])
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, msaaBuffers[1])
        val made = target(w, h)
        sceneFbo = made.first
        sceneTex = made.second
        for (k in 0..1) {
            val g = target(glowW(), glowH())
            glowFbo[k] = g.first
            glowTex[k] = g.second
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** A framebuffer drawing into a texture of [tw] x [th] (smoothly sampled): its id and the texture's. */
    private fun target(tw: Int, th: Int): Pair<Int, Int> {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        return f[0] to t[0]
    }

    private fun release() {
        if (msaaFbo != 0) {
            GLES30.glDeleteFramebuffers(4, intArrayOf(msaaFbo, sceneFbo, glowFbo[0], glowFbo[1]), 0)
            GLES30.glDeleteRenderbuffers(2, msaaBuffers, 0)
            GLES30.glDeleteTextures(3, intArrayOf(sceneTex, glowTex[0], glowTex[1]), 0)
        }
        msaaFbo = 0
    }

    /** A new surface: what was made on the GPU is gone (made again at the next frame). */
    fun surfaceLost() {
        msaaFbo = 0
        bright = 0
        w = 0
        h = 0
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it) }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        return p
    }

    private companion object {
        const val POST_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        // The light brighter than most of the picture (four texels at once, as it is made smaller).
        const val BRIGHT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec2 uTexel;
out vec4 color;
void main() {
    vec3 c = (texture(uScene, vUv + uTexel * vec2(-1.0, -1.0)).rgb + texture(uScene, vUv + uTexel * vec2(1.0, -1.0)).rgb
            + texture(uScene, vUv + uTexel * vec2(-1.0, 1.0)).rgb + texture(uScene, vUv + uTexel * vec2(1.0, 1.0)).rgb) * 0.25;
    float l = max(max(c.r, c.g), c.b);
    // Only what is nearly white-hot (the sun, flames, lit windows), not a pale sky.
    // (Pale, colourless light, as a hazy sky, counts half.)
    float sat = l - min(min(c.r, c.g), c.b);
    color = vec4(c * smoothstep(0.95, 1.0, l) * (0.5 + 0.5 * smoothstep(0.1, 0.4, sat)), 1.0);
}
"""

        const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec2 uStep;
out vec4 color;
void main() {
    vec3 c = texture(uScene, vUv).rgb * 0.227;
    c += (texture(uScene, vUv + uStep).rgb + texture(uScene, vUv - uStep).rgb) * 0.195;
    c += (texture(uScene, vUv + uStep * 2.0).rgb + texture(uScene, vUv - uStep * 2.0).rgb) * 0.122;
    c += (texture(uScene, vUv + uStep * 3.0).rgb + texture(uScene, vUv - uStep * 3.0).rgb) * 0.054;
    c += (texture(uScene, vUv + uStep * 4.0).rgb + texture(uScene, vUv - uStep * 4.0).rgb) * 0.016;
    color = vec4(c, 1.0);
}
"""

        // The frame, its glow added, a gentle S-curve (a little more depth in the light and shade)
        // and the edges a touch darker, as on film.
        const val FINISH_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uGlow;
uniform float uAspect;
uniform float uFilm;
out vec4 color;
void main() {
    vec3 c = texture(uScene, vUv).rgb + texture(uGlow, vUv).rgb * 0.45;
    c = min(c, vec3(1.0));
    // Film tones, when chosen: a gentle S-curve and the edges a little darker.
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.28 * uFilm);
    vec2 d = (vUv - 0.5) * vec2(uAspect, 1.0);
    c *= 1.0 - 0.18 * uFilm * smoothstep(0.35, 0.95, length(d) * 1.25);
    color = vec4(c, 1.0);
}
"""
    }
}
