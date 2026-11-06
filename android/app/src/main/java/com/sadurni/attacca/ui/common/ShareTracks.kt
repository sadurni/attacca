package com.sadurni.attacca.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sadurni.attacca.R
import java.io.File

/** Sends tracks (their files) to another app: a messenger, a file manager, a mail. */
object ShareTracks {

    /** Shares the files of [paths] (web addresses and missing files are left out). */
    fun share(context: Context, paths: List<String>) {
        val files = paths.filter { !it.contains("://") }.map(::File).filter { it.isFile }.distinct().take(MAX_FILES)
        if (files.isEmpty()) {
            Toast.makeText(context, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
        val types = files.map { typeOf(it) }.distinct()
        val type = types.singleOrNull() ?: "audio/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The receiving app gets read access to every file through the clip.
        intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        val title = if (files.size == 1) files[0].nameWithoutExtension else context.getString(R.string.share_count, files.size)
        runCatching {
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(context, R.string.share_nothing, Toast.LENGTH_SHORT).show() }
    }

    private fun typeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "audio/*"

    private const val MAX_FILES = 200
}
