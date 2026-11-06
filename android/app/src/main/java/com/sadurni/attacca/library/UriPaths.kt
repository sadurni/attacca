package com.sadurni.attacca.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Turns the content:// URIs other apps hand over ("Open with Attacca") into real file paths.
 * With a real path, playlists keep resolving their relative entries and the file can be
 * remembered in Lists; Attacca has all-files access, so the path is readable.
 */
object UriPaths {

    fun resolve(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.canRead() }
        if (uri.scheme != "content") return null
        return fromDocument(context, uri)
            ?: fromDataColumn(context, uri)
            ?: fromPathSegments(uri)
    }

    /** The name the providing app gives the file (to know its extension). */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Copies a playlist whose path cannot be found into Music/Attacca/Imported. */
    fun importCopy(context: Context, uri: Uri): File? = runCatching {
        val name = displayName(context, uri) ?: "Imported.m3u8"
        val target = File(File(UserLists.directory, "Imported"), name)
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
        target.takeIf { it.exists() }
    }.getOrNull()

    private fun fromDocument(context: Context, uri: Uri): File? {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val id = DocumentsContract.getDocumentId(uri)
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val volume = id.substringBefore(':')
                val relative = id.substringAfter(':', "")
                @Suppress("DEPRECATION")
                val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory() else File("/storage/$volume")
                File(root, relative)
            }
            "com.android.providers.downloads.documents" ->
                id.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")?.let(::File)
            else -> null
        }?.takeIf { it.canRead() }
    }

    /** MediaStore and several file managers still expose the path in the "_data" column. */
    private fun fromDataColumn(context: Context, uri: Uri): File? = runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.let(::File)?.takeIf { it.canRead() }

    /** File-manager providers often embed the real path: content://app.provider/root/storage/emulated/0/… */
    private fun fromPathSegments(uri: Uri): File? {
        val path = Uri.decode(uri.path ?: return null)
        for (marker in listOf("/storage/", "/sdcard/", "/mnt/")) {
            val index = path.indexOf(marker)
            if (index >= 0) return File(path.substring(index)).takeIf { it.canRead() }
        }
        return null
    }

    /**
     * Real path of a folder picked with the system folder picker ("primary:Music" →
     * "/storage/emulated/0/Music", "1234-ABCD:Music" → "/storage/1234-ABCD/Music").
     */
    fun treePath(uri: android.net.Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        val folder = if (relative.isEmpty()) java.io.File(root) else java.io.File(root, relative)
        return folder.takeIf { it.isDirectory }?.absolutePath
    }
}
