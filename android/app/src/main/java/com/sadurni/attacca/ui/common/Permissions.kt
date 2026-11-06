package com.sadurni.attacca.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.sadurni.attacca.R

/**
 * Asking for permissions so that a button never does nothing: once Android stops showing a
 * permission's dialog (after it was refused), or a phone lacks one of the special settings
 * screens, Attacca's own page in the system settings opens instead, with a note of what to
 * turn on.
 */
object AppPermissions {

    /** Attacca's page in the system settings (permissions, battery…). */
    fun openAppSettings(context: Context) {
        Toast.makeText(context, R.string.permission_open_settings, Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Opens a special settings screen ([intent]), or Attacca's settings page where it does not exist. */
    fun openOrAppSettings(context: Context, intent: Intent) {
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) openAppSettings(context)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A request for [permission] that falls back to Attacca's settings page when Android no longer
 * shows the dialog. [onResult] gets whether it was granted.
 */
@Composable
fun rememberPermissionRequest(permission: String, onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // Refused for good (or the dialog was not even shown): only the settings can change it.
            val activity = context.findActivity()
            if (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                AppPermissions.openAppSettings(context)
            }
        }
        result(granted)
    }
    return { launcher.launch(permission) }
}
