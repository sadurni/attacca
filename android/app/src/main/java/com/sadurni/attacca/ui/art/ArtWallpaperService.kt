package com.sadurni.attacca.ui.art

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.sadurni.attacca.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Art-tacca as the phone's wallpaper: the playing song paints the home screen. It draws only while
 * the wallpaper shows (nothing with the screen off or an app on top), at 30 frames a second while
 * music plays and seldom otherwise; with nothing playing it shows the last painting finished. It
 * keeps a painting of its own, apart from the app's.
 */
class ArtWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ArtEngine()

    inner class ArtEngine : Engine() {
        private val feed = ArtFeed()
        private val state = ArtCanvasState()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var view: WallView? = null
        private var loop: Job? = null
        private var listening = false

        /** A GL view drawing on the wallpaper's surface instead of a window's. */
        private inner class WallView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun gone() = super.onDetachedFromWindow()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            val context = this@ArtWallpaperService
            // Its own last painting, apart from the app's (which may be another shape, split).
            feed.onFinished = { pic -> ArtStore.keepLast(context, pic.bitmap, ArtStore.WALL_LAST) }
            view = WallView(context).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                preserveEGLContextOnPause = true
                setRenderer(ArtRenderer(feed, strip = false, state = state))
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            scope.launch {
                feed.rest = withContext(Dispatchers.IO) { ArtStore.last(context, ArtStore.WALL_LAST) }
                view?.requestRender()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                view?.onResume()
                start()
            } else {
                stop()
                view?.onPause()
            }
        }

        override fun onDestroy() {
            stop()
            scope.cancel()
            view?.gone()
            view = null
            super.onDestroy()
        }

        private fun start() {
            if (loop?.isActive == true) return
            val app = this@ArtWallpaperService.app
            ArtShared.listeners++
            listening = true
            app.dspEngine.notesWanted = true
            loop = scope.launch {
                val levels = FloatArray(3)
                val notes = FloatArray(12)
                var shownId: String? = null
                var notesAt = 0L
                var wasPlaying = ArtNow.playing
                while (isActive) {
                    // The song, as soon as the player tells another.
                    val id = ArtNow.mediaId
                    if (id != null && id != shownId) {
                        shownId = id
                        launch {
                            val analysis = app.analysisResults.value[id]
                            val end = ArtNow.clipEndMs ?: analysis?.durationMs ?: 0L
                            feed.song = withContext(Dispatchers.IO) { artSongOf(this@ArtWallpaperService, id, analysis, ArtNow.clipStartMs, end, ArtNow.title) }
                        }
                    }
                    feed.positionFor = id
                    feed.positionMs = ArtNow.positionMs
                    feed.positionAtNs = ArtNow.atNs
                    feed.playing = ArtNow.playing
                    // The music stopped: the painting as it is now is the one to stay.
                    if (wasPlaying && !ArtNow.playing) {
                        val context = this@ArtWallpaperService
                        feed.snapshot = { pic -> ArtStore.keepLast(context, pic.bitmap, ArtStore.WALL_LAST) }
                    }
                    wasPlaying = ArtNow.playing
                    val ui = app.settings.ui.value
                    feed.paintOver = ui.artPaintOver
                    feed.style = com.sadurni.attacca.art.ArtStyle.of(ui.artStyle)
                    feed.subject = com.sadurni.attacca.art.ArtSubject.of(ui.artSubject)
                    feed.likeness = ui.artLikeness
                    val heard = app.dspEngine.heardLevels(levels)
                    if (heard) System.arraycopy(levels, 0, feed.levels, 0, 3)
                    feed.heard = heard
                    val now = System.currentTimeMillis()
                    if (now - notesAt > 120) {
                        notesAt = now
                        val known = withContext(Dispatchers.Default) { app.dspEngine.heardNotes(notes) }
                        if (known) System.arraycopy(notes, 0, feed.notes, 0, 12)
                        feed.notesKnown = known
                    }
                    view?.requestRender()
                    // Smooth while the music plays; now and then otherwise (wet paint still drying).
                    delay(if (ArtNow.playing) 33L else 400L)
                }
            }
        }

        private fun stop() {
            loop?.cancel()
            loop = null
            if (listening) {
                listening = false
                ArtShared.listeners--
                if (ArtShared.listeners <= 0) {
                    val app = this@ArtWallpaperService.app
                    app.dspEngine.notesWanted = false
                    app.dspEngine.notes.clear()
                }
            }
        }
    }
}
