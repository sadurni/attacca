package com.sadurni.attacca.ui.world

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short log of Attacca World's song changes (what came in, what the new song's land started in),
 * beside the exits log, to send when a change does not look right.
 */
object WorldLog {
    private const val FILE = "attacca-world.txt"
    private const val MAX_BYTES = 64 * 1024

    fun note(text: String) {
        if (!com.sadurni.attacca.Logs.on) return
        runCatching {
            val f = File(com.sadurni.attacca.library.UserLists.directory, FILE)
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
            f.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()) + "  " + text + "\n")
        }
    }
}
