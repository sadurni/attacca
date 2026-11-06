package com.sadurni.attaccaplg.beets

import com.sadurni.attaccaplg.generate.Library
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * beets, the music library manager, alongside Attacca Desktop: finding it and its library,
 * importing albums with it (in a terminal, as it asks while it tags), running its commands, and
 * writing what Attacca measured into its library as flexible attributes (attacca_mood,
 * attacca_family, attacca_bpm, attacca_energy, attacca_positivity), so beets can query by them
 * (`beet ls attacca_mood:calm`) and make lists with them.
 */
object Beets {

    /** Where beets is: its command, its library file and its music folder. */
    class Setup(val command: String, val library: File?, val directory: File?)

    /** The `beet` command, from [configured] or the PATH; null when there is none. */
    fun find(configured: String?): Setup? {
        val candidates = listOfNotNull(configured?.takeIf { it.isNotBlank() }, "beet")
        for (command in candidates) {
            val config = run(command, listOf("config"), 20) ?: continue
            fun value(key: String) = config.lineSequence().firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.trim('\'', '"')?.takeIf { it.isNotBlank() }
            val directory = value("directory")?.let(::expandHome)?.let(::File)
            val library = value("library")?.let(::expandHome)?.let(::File)
            return Setup(command, library, directory)
        }
        return null
    }

    private fun expandHome(path: String) = if (path.startsWith("~")) System.getProperty("user.home") + path.substring(1) else path

    /** Runs `beet ARGS` and returns what it printed (null when it could not run). */
    fun run(command: String, args: List<String>, timeoutSeconds: Long = 600): String? = runCatching {
        // beets is Python: told to print UTF-8 (on Windows it would print the console's code page).
        val process = ProcessBuilder(listOf(command) + args).redirectErrorStream(true)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8"; environment()["PYTHONUTF8"] = "1" }.start()
        val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        out
    }.getOrNull()

    /** Opens a terminal running `beet import FOLDERS` (it asks questions while tagging). */
    fun importInTerminal(command: String, folders: List<File>): Boolean {
        val args = listOf(command, "import") + folders.map { it.path }
        val windows = System.getProperty("os.name").lowercase().contains("win")
        return runCatching {
            if (windows) {
                val line = args.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
                ProcessBuilder("cmd", "/c", "start", "\"beets\"", "cmd", "/k", line).start()
            } else {
                val terminals = listOf(
                    listOf("x-terminal-emulator", "-e"), listOf("gnome-terminal", "--"), listOf("konsole", "-e"),
                    listOf("xfce4-terminal", "-x"), listOf("xterm", "-e"),
                )
                val terminal = terminals.firstOrNull { t -> File("/usr/bin/${t[0]}").exists() } ?: return false
                ProcessBuilder(terminal + args).start()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Writes Attacca's measures of [library]'s tracks into the beets library [db] (matched by the
     * full path of each file), as flexible attributes. Returns how many tracks got them.
     * beets should not be running meanwhile.
     */
    fun writeAttributes(db: File, library: Library): Int {
        val root = library.root ?: return 0
        val byFile = library.all.associateBy { File(root, it.path).canonicalPath }
        var written = 0
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.autoCommit = false
            val items = c.createStatement().executeQuery("SELECT id, path FROM items").use { rs ->
                buildList { while (rs.next()) add(rs.getInt(1) to pathOf(rs.getBytes(2))) }
            }
            c.prepareStatement("INSERT OR REPLACE INTO item_attributes (entity_id, key, value) VALUES (?, ?, ?)").use { insert ->
                for ((id, path) in items) {
                    val track = path?.let { runCatching { File(it).canonicalPath }.getOrNull() }?.let(byFile::get) ?: continue
                    val index = library.indexOf(track.path) ?: continue
                    val values = buildMap {
                        library.moodOf(track.path)?.let { put("attacca_mood", it.name.lowercase()) }
                        library.familyOf(track.path)?.let { put("attacca_family", "${it + 1}") }
                        track.features?.tempoBpm?.let { put("attacca_bpm", "%.0f".format(java.util.Locale.ROOT, it)) }
                        val k = library.generator.classification
                        put("attacca_energy", "%.0f".format(java.util.Locale.ROOT, k.energy[index] * 100))
                        put("attacca_positivity", "%.0f".format(java.util.Locale.ROOT, k.positivity[index] * 100))
                    }
                    for ((key, value) in values) {
                        insert.setInt(1, id)
                        insert.setString(2, key)
                        insert.setString(3, value)
                        insert.addBatch()
                    }
                    written++
                }
                insert.executeBatch()
            }
            c.commit()
        }
        return written
    }

    /** beets keeps paths as bytes (the file system's encoding: UTF-8 on Linux and macOS, and on Windows). */
    private fun pathOf(bytes: ByteArray?): String? = bytes?.toString(Charsets.UTF_8)
}
