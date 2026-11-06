package com.sadurni.attaccaplg.ui

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.SwingUtilities

/**
 * The keyboard's media keys (play/pause, next, previous, stop) for the player, also while the
 * window is in the background: a system-wide keyboard hook that only looks at those keys.
 * Windows, Linux on X11 and macOS; where it cannot start, the window's own shortcuts remain.
 */
object MediaKeys {

    private var listener: NativeKeyListener? = null

    /** Starts listening; [action] gets "toggle", "next", "previous" or "stop" on the window's thread. False when the system refused. */
    fun start(action: (String) -> Unit): Boolean {
        if (listener != null) return true
        Logger.getLogger(GlobalScreen::class.java.getPackage().name).apply {
            level = Level.OFF
            useParentHandlers = false
        }
        return runCatching {
            GlobalScreen.registerNativeHook()
            val l = object : NativeKeyListener {
                override fun nativeKeyPressed(e: NativeKeyEvent) {
                    val what = when (e.keyCode) {
                        NativeKeyEvent.VC_MEDIA_PLAY -> "toggle"
                        NativeKeyEvent.VC_MEDIA_NEXT -> "next"
                        NativeKeyEvent.VC_MEDIA_PREVIOUS -> "previous"
                        NativeKeyEvent.VC_MEDIA_STOP -> "stop"
                        else -> return
                    }
                    SwingUtilities.invokeLater { action(what) }
                }
            }
            GlobalScreen.addNativeKeyListener(l)
            listener = l
            true
        }.getOrDefault(false)
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching {
            GlobalScreen.removeNativeKeyListener(l)
            GlobalScreen.unregisterNativeHook()
        }
    }
}
