package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.UriPaths
import com.sadurni.attacca.settings.Backup
import com.sadurni.attacca.ui.common.OpenDocumentAt
import com.sadurni.attacca.ui.common.PickerMemory
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the welcome screen has to show: first start, nothing set up yet. */
fun needsWelcome(context: android.content.Context): Boolean =
    context.app.settings.musicFolder == null &&
        !context.getSharedPreferences(WELCOME_PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

private fun markWelcomeDone(context: android.content.Context) {
    context.getSharedPreferences(WELCOME_PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
}

/**
 * The very first screen: choose the music folder (scanned once, in the background), or restore a
 * backup, which brings the music folder, the scan and everything else back without scanning again.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = context.app.settings
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    // The music folder chosen: whether to import a library exported elsewhere before scanning it.
    // Kept over the activity being made again while the file picker is open.
    var askImport by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val importing by WelcomeImport.running.collectAsState()
    // After the music folder: the setup guide, from the analysis on.
    var guide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (guide) {
        SetupGuide(start = SetupStep.ANALYSIS, onDone = onDone)
        return
    }
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.MUSIC_FOLDER, uri)
        val path = UriPaths.treePath(uri)
        if (path == null) {
            Toast.makeText(context, R.string.music_folder_unsupported, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        settings.musicFolder = path
        markWelcomeDone(context)
        // A backup from another phone brings everything already (moved to this folder by the scan).
        if (com.sadurni.attacca.settings.PathRemap.pending(context) != null) {
            context.app.libraryScanner.scan(java.io.File(path))
            guide = true
        } else {
            askImport = path
        }
    }
    fun scanAndGo(path: String) {
        askImport = null
        context.app.libraryScanner.scan(java.io.File(path))
        guide = true
    }
    val pickLibrary = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        val path = askImport ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            scanAndGo(path)
            return@rememberLauncherForActivityResult
        }
        PickerMemory.put(context, PickerMemory.BACKUP, uri)
        WelcomeImport.start(context, uri, path)
    }
    // The import over (here or before the activity was made again): the scan, then the guide.
    val importedFor by WelcomeImport.done.collectAsState()
    androidx.compose.runtime.LaunchedEffect(importedFor) {
        val path = importedFor ?: return@LaunchedEffect
        WelcomeImport.done.value = null
        scanAndGo(path)
    }
    askImport?.let { path ->
        val where = stringResource(R.string.settings_title) + " → " + stringResource(R.string.section_backup) + " → " + stringResource(R.string.exchange_section) + " → " + stringResource(R.string.exchange_export)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.welcome_import_title)) },
            text = {
                if (importing) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.welcome_importing), modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    Text(stringResource(R.string.welcome_import_text, where))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = !importing, onClick = {
                    pickLibrary.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "application/vnd.sqlite3", "*/*") to PickerMemory.get(context, PickerMemory.BACKUP))
                }) { Text(stringResource(R.string.welcome_import_yes)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(enabled = !importing, onClick = { scanAndGo(path) }) { Text(stringResource(R.string.welcome_import_no)) }
            },
        )
    }
    val pickBackup = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.BACKUP, uri)
        restoring = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.let { Backup.stage(context, it) } ?: false }.getOrDefault(false)
            }
            if (ok) {
                markWelcomeDone(context)
                Toast.makeText(context, R.string.backup_restore_restart, Toast.LENGTH_LONG).show()
                context.app.shutdown()
            } else {
                restoring = false
                Toast.makeText(context, R.string.backup_invalid, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), fontSize = AttaccaType.display, fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current, fontWeight = FontWeight.Bold, color = accent)
        com.sadurni.attacca.ui.common.AppTagline(Modifier.padding(top = 4.dp), centered = true)
        com.sadurni.attacca.ui.common.AppSlogan(Modifier.padding(top = 10.dp), centered = true)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_title), fontSize = AttaccaType.title, fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        val wide = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        Button(
            onClick = { pickFolder.launch(PickerMemory.get(context, PickerMemory.MUSIC_FOLDER)) },
            enabled = !restoring,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = wide.height(52.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.welcome_choose_folder), fontSize = AttaccaType.body, modifier = Modifier.padding(start = 10.dp))
        }
        Text(
            stringResource(R.string.welcome_choose_folder_hint),
            fontSize = AttaccaType.secondary,
            color = grayed,
            textAlign = TextAlign.Center,
            modifier = wide.padding(top = 8.dp, bottom = 24.dp),
        )

        OutlinedButton(
            onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream") to PickerMemory.get(context, PickerMemory.BACKUP)) },
            enabled = !restoring,
            modifier = wide.height(52.dp),
        ) {
            if (restoring) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(stringResource(R.string.welcome_restore), fontSize = AttaccaType.body, modifier = Modifier.padding(start = 10.dp))
        }
        Text(
            stringResource(R.string.welcome_restore_hint),
            fontSize = AttaccaType.secondary,
            color = grayed,
            textAlign = TextAlign.Center,
            modifier = wide.padding(top = 8.dp, bottom = 24.dp),
        )

        WelcomePermissions(wide)
        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = {
                markWelcomeDone(context)
                onDone()
            },
            enabled = !restoring,
        ) { Text(stringResource(R.string.welcome_later), color = grayed) }
    }
}

/**
 * What Attacca may do, with the state of each permission: files (granted to get here) and
 * notifications; the ones for starting on a Bluetooth connection are asked in Devices.
 */
@Composable
private fun WelcomePermissions(modifier: Modifier) {
    val context = LocalContext.current
    var check by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { check++ }
    val notificationsOk = remember(check) {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val requestNotifications = com.sadurni.attacca.ui.common.rememberPermissionRequest(android.Manifest.permission.POST_NOTIFICATIONS) { check++ }
    Column(modifier) {
        Text(stringResource(R.string.welcome_permissions), fontSize = AttaccaType.secondary, fontWeight = FontWeight.Bold, color = LocalAccent.current)
        PermissionRow(stringResource(R.string.perm_files), stringResource(R.string.perm_files_hint), granted = true, onAllow = {})
        PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_hint), notificationsOk, requestNotifications)
        Text(stringResource(R.string.perm_more_hint), fontSize = AttaccaType.caption, color = LocalAttaccaColors.current.textGrayed, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PermissionRow(title: String, hint: String, granted: Boolean, onAllow: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = AttaccaType.body)
            Text(hint, fontSize = AttaccaType.caption, color = LocalAttaccaColors.current.textGrayed)
        }
        if (granted) {
            Icon(
                androidx.compose.material.icons.Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.perm_granted),
                tint = LocalAccent.current,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            OutlinedButton(onClick = onAllow, modifier = Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.perm_allow)) }
        }
    }
}

private const val WELCOME_PREFS = "welcome"
private const val KEY_DONE = "done"

/**
 * The import of a library offered by the welcome, in the app's scope: the file picker may make the
 * activity again (and the welcome with it) while it runs. [done] tells the folder once it is over.
 */
private object WelcomeImport {
    val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val done = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun start(context: android.content.Context, uri: android.net.Uri, folder: String) {
        if (running.value) return
        running.value = true
        val app = context.app
        app.appScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { com.sadurni.attacca.library.LibraryTransfer.import(app, uri) }.getOrNull() }
            Toast.makeText(
                app,
                if (result == null) app.getString(R.string.exchange_failed)
                else app.getString(R.string.exchange_imported, result.matched, result.total, result.edges, result.features, result.analysis),
                Toast.LENGTH_LONG,
            ).show()
            running.value = false
            done.value = folder
        }
    }
}
