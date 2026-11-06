package com.sadurni.attacca.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Where each of the system's file and folder pickers was last used, so each one opens where it
 * was left instead of wherever another picker went last (the system keeps a single place per app).
 */
object PickerMemory {

    const val MUSIC_FOLDER = "music_folder"
    const val LISTS_FOLDER = "lists_folder"
    const val EXPORT_LIST = "export_list"
    const val BACKUP = "backup"
    const val INCLUSION_LIST = "inclusion_list"
    const val PLAYER_DB = "player_db"
    const val THEME_ZIP = "theme_zip"
    const val COVER_IMAGE = "cover_image"
    const val PEQ_FILE = "peq_file"
    const val BACKUP_FOLDER = "backup_folder"

    private const val PREFS = "pickers"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun get(context: Context, picker: String): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(picker, null)?.let(Uri::parse)

    /** Remembers the folder of [uri], a folder picked as a tree or a file picked or created. */
    fun put(context: Context, picker: String, uri: Uri) {
        val place = runCatching {
            if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            } else {
                folderOf(uri) ?: uri
            }
        }.getOrDefault(uri)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(picker, place.toString()).apply()
    }

    /**
     * The document of a folder of the device storage given by its path ("/storage/emulated/0/Music"
     * → "primary:Music", "/storage/1234-ABCD/Lists" → "1234-ABCD:Lists"), to open a picker there.
     */
    fun folderUri(path: String): Uri? {
        val parts = path.trimEnd('/').removePrefix("/storage/").split('/', limit = 2)
        if (!path.startsWith("/storage/") || parts.isEmpty()) return null
        val (volume, rest) = when (parts[0]) {
            "emulated" -> {
                val inner = parts.getOrNull(1)?.split('/', limit = 2) ?: return null
                "primary" to inner.getOrNull(1).orEmpty()
            }
            else -> parts[0] to parts.getOrNull(1).orEmpty()
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "$volume:$rest")
    }

    /** The folder holding a file of the device storage ("primary:Music/a.zip" → "primary:Music"). */
    private fun folderOf(uri: Uri): Uri? {
        if (uri.authority != EXTERNAL_STORAGE) return null
        val id = DocumentsContract.getDocumentId(uri)
        val slash = id.lastIndexOf('/')
        val parent = if (slash >= 0) id.substring(0, slash) else id.substringBefore(':') + ":"
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, parent)
    }
}

/** [ActivityResultContracts.CreateDocument] opening at a given place (file name, place). */
class CreateDocumentAt(mimeType: String) : ActivityResultContract<Pair<String, Uri?>, Uri?>() {
    private val base = ActivityResultContracts.CreateDocument(mimeType)

    override fun createIntent(context: Context, input: Pair<String, Uri?>): Intent =
        base.createIntent(context, input.first).apply {
            input.second?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = base.parseResult(resultCode, intent)
}

/** [ActivityResultContracts.OpenDocument] opening at a given place (MIME types, place). */
class OpenDocumentAt : ActivityResultContract<Pair<Array<String>, Uri?>, Uri?>() {
    private val base = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Pair<Array<String>, Uri?>): Intent =
        base.createIntent(context, input.first).apply {
            input.second?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = base.parseResult(resultCode, intent)
}
