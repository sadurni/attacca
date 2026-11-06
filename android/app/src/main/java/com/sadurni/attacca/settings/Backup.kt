package com.sadurni.attacca.settings

import android.content.Context
import com.sadurni.attacca.library.UserLists
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything the app keeps: settings and profiles (shared preferences), the tag and
 * analysis caches (databases) and the user's own lists.
 *
 * A restore cannot overwrite these while the app runs (it holds them in memory and would write
 * them back), so it is staged and applied at the next start, before anything opens them.
 */
object Backup {

    private const val MARKER = "attacca-backup.txt"
    private const val PENDING = "pending_restore.zip"
    private val SECTIONS = listOf("shared_prefs", "databases")
    private const val LISTS = "lists"
    private const val THEMES = "themes"

    /** Writes the backup as a zip to [out]. Blocking. */
    fun write(context: Context, out: OutputStream) {
        val dataDir = File(context.applicationInfo.dataDir)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("Attacca backup\n".toByteArray())
            zip.closeEntry()
            for (section in SECTIONS) {
                File(dataDir, section).listFiles()?.filter { it.isFile }?.forEach { file ->
                    zip.putNextEntry(ZipEntry("$section/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            UserLists.directory.listFiles()?.filter { it.isFile }?.forEach { file ->
                zip.putNextEntry(ZipEntry("$LISTS/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            ThemeStore.directory.listFiles()?.forEach { entry ->
                // A theme's file, or the folder of its images.
                val files = if (entry.isDirectory) entry.listFiles()?.filter { it.isFile }.orEmpty() else listOf(entry)
                for (file in files) {
                    val name = if (entry.isDirectory) "${entry.name}/${file.name}" else file.name
                    zip.putNextEntry(ZipEntry("$THEMES/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Checks [input] is a backup and keeps it for the next start. False if it is not one. */
    fun stage(context: Context, input: InputStream): Boolean {
        val pending = File(context.filesDir, PENDING)
        input.use { source -> pending.outputStream().use { source.copyTo(it) } }
        val valid = ZipInputStream(pending.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.any { it.name == MARKER }
        }
        if (!valid) pending.delete()
        return valid
    }

    /** Applies a staged restore, if any. Call first thing at app start. */
    fun applyPending(context: Context) {
        val pending = File(context.filesDir, PENDING)
        if (!pending.exists()) return
        val dataDir = File(context.applicationInfo.dataDir)
        runCatching {
            // Caches and settings are replaced as a whole, so nothing stale is mixed in.
            for (section in SECTIONS) File(dataDir, section).listFiles()?.forEach { it.delete() }
            ZipInputStream(pending.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val section = entry.name.substringBefore('/')
                    val name = entry.name.substringAfter('/')
                    // Plain names only (a theme's images one folder down): nothing may land outside the folders it belongs to.
                    val depthAllowed = if (section == THEMES) 1 else 0
                    if (entry.isDirectory || name.isEmpty() || name.count { it == '/' } > depthAllowed || name.contains("..") ||
                        name.startsWith("/") || name.contains('\\')
                    ) return@forEach
                    val target = when (section) {
                        in SECTIONS -> File(dataDir, "$section/$name")
                        LISTS -> File(UserLists.directory, name)
                        THEMES -> File(ThemeStore.directory, name)
                        else -> return@forEach
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        pending.delete()
    }
}
