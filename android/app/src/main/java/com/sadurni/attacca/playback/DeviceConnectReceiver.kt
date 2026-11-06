package com.sadurni.attacca.playback

import android.bluetooth.BluetoothDevice
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.OutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A Bluetooth device connected while the player is not running: if its rule says to start
 * playing on connection, the player is started on its list (the running player handles
 * connections itself, see PlaybackService.onDeviceConnected).
 *
 * The whole app is opened first, with its screen, and then asked to play: an app on screen may
 * start its player. Android only lets an app open its screen from the background when it may
 * display over other apps; without that, starting playback needs the app to be exempt from
 * battery optimization (Android 12+). If it still does not play, a notification offers to play
 * with a tap.
 */
class DeviceConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val app = context.app
        // The player is running: it reacts to the connection itself.
        if (app.extras.value != null) return
        val address = runCatching { device.address }.getOrNull() ?: return
        val rule = app.settings.profiles.state.value.devices.firstOrNull {
            it.type == OutputType.BLUETOOTH && com.sadurni.attacca.settings.DeviceIdentity.matchesAddress(it.key, address)
        } ?: return
        if (rule.resumeList == null || !rule.autoPlay) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                // Media is routed to the device a moment after the link comes up; the player
                // picks the device's list from the current output when it opens.
                // Headphones can take several seconds after the link to set up the audio profile,
                // and the audio system does not always report when routing settles: ask again.
                val routed = withTimeoutOrNull(ROUTING_TIMEOUT_MS) {
                    while (true) {
                        app.outputMonitor.refresh()
                        val current = app.outputMonitor.current.value
                        if (current != null && com.sadurni.attacca.settings.DeviceIdentity.sameDevice(current.key, rule.key)) break
                        delay(ROUTING_POLL_MS)
                    }
                }
                if (routed == null) {
                    Log.i(TAG, "${rule.name} connected but media is not routed to it")
                    return@launch
                }
                openApp(context)
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val controller = MediaController.Builder(context, token).buildAsync().await()
                try {
                    // The player opens on the device's list; a long list takes a while to load.
                    withTimeoutOrNull(LOAD_TIMEOUT_MS) { controller.awaitItems() }
                    controller.play()
                    // Keep the player bound until it plays (and is in the foreground).
                    val playing = withTimeoutOrNull(START_TIMEOUT_MS) { controller.awaitPlaying() } != null
                    if (!playing) {
                        Log.w(TAG, "${rule.name}: playback did not start")
                        offerToPlay(context, rule.name)
                    }
                } finally {
                    controller.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start playback for ${rule.name}", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Opens the app's screen on the player, which then plays the list it opened on. */
    private fun openApp(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !android.provider.Settings.canDrawOverlays(context)) return
        runCatching { context.startActivity(playIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Could not open the app", it) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun offerToPlay(context: Context, deviceName: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.connect_channel))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            playIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.connect_play_title, deviceName))
            .setContentText(context.getString(R.string.connect_play_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(OFFER_TIMEOUT_MS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun MediaController.awaitItems() {
        if (mediaItemCount > 0) return
        suspendCancellableCoroutine { continuation ->
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if (!timeline.isEmpty && continuation.isActive) continuation.resume(Unit)
                }
            }
            addListener(listener)
            continuation.invokeOnCancellation { removeListener(listener) }
        }
    }

    private suspend fun MediaController.awaitPlaying() {
        if (isPlaying) return
        suspendCancellableCoroutine { continuation ->
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && continuation.isActive) continuation.resume(Unit)
                }
            }
            addListener(listener)
            continuation.invokeOnCancellation { removeListener(listener) }
        }
    }

    companion object {
        /** Opens the player and plays the list it opens on. */
        const val ACTION_PLAY = "com.sadurni.attacca.action.PLAY_ON_CONNECT"
        const val NOTIFICATION_ID = 2010

        fun playIntent(context: Context): Intent =
            Intent(context, com.sadurni.attacca.ui.MainActivity::class.java).setAction(ACTION_PLAY)

        private const val TAG = "DeviceConnect"
        private const val CHANNEL = "connect"
        private const val OFFER_TIMEOUT_MS = 10 * 60_000L
        // All together well below the time a receiver may keep running (about a minute).
        private const val ROUTING_TIMEOUT_MS = 15_000L
        private const val ROUTING_POLL_MS = 500L
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val START_TIMEOUT_MS = 15_000L
    }
}
