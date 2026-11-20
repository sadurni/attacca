package com.sadurni.attacca.ui.art

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.view.TextureView

/**
 * A GL view drawn as part of the window (a TextureView), not as a surface of its own: what is
 * drawn over it or around it (a fading edge) composes with it like any view, so the painting can
 * melt into World beside it reliably. Runs a [GLSurfaceView.Renderer] (OpenGL ES 3) on a thread of
 * its own, a frame each [requestRender].
 */
class GLTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var renderer: GLSurfaceView.Renderer? = null
    private var thread: RenderThread? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setRenderer(r: GLSurfaceView.Renderer) {
        renderer = r
    }

    fun requestRender() {
        thread?.request()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val r = renderer ?: return
        thread = RenderThread(st, width, height, r).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.quit()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private class RenderThread(
        private val texture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
        private val renderer: GLSurfaceView.Renderer,
    ) : Thread("Art-tacca GL") {
        private val lock = Object()
        private var wanted = true
        private var sized = true
        private var running = true

        fun request() = synchronized(lock) {
            wanted = true
            lock.notifyAll()
        }

        fun resize(w: Int, h: Int) = synchronized(lock) {
            width = w
            height = h
            sized = true
            wanted = true
            lock.notifyAll()
        }

        fun quit() {
            synchronized(lock) {
                running = false
                lock.notifyAll()
            }
            runCatching { join(2000) }
        }

        override fun run() {
            val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            val config = configs[0] ?: return
            val context: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            val surface: EGLSurface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            try {
                renderer.onSurfaceCreated(null, null)
                while (true) {
                    var resized = false
                    synchronized(lock) {
                        while (running && !wanted) lock.wait()
                        if (!running) return
                        wanted = false
                        resized = sized
                        sized = false
                    }
                    if (resized) renderer.onSurfaceChanged(null, width, height)
                    renderer.onDrawFrame(null)
                    EGL14.eglSwapBuffers(display, surface)
                }
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        }

        private companion object {
            const val EGL_OPENGL_ES3_BIT = 0x40
        }
    }
}
