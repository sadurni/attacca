package com.sadurni.attacca

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A silent notification for each long job running in the background (the tag scan of the music
 * folder, its analysis for chaining, chaining the current list): how many files out of all, the
 * percentage and the time likely left. Gone when the job ends.
 */
class WorkNotifications(private val app: AttaccaApp, private val scope: CoroutineScope) {

    /**
     * One job's state: files ready out of all (0 while still looking for files) and the time
     * left; [label] tells the step when one notification follows several steps.
     */
    private data class Work(val done: Int, val total: Int, val remainingMs: Long?, val label: Int? = null)

    private val manager = NotificationManagerCompat.from(app)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(app.getString(R.string.work_channel))
                .setShowBadge(false)
                .build(),
        )
        // The music folder is one job for the user: its tags, then (with background analysis) its
        // analysis for chaining, under one notification that tells the step.
        follow(
            ID_SCAN,
            R.string.work_library_title,
            R.string.library_scan_progress,
            kotlinx.coroutines.flow.combine(app.libraryScanner.progress, app.chainAnalyzer.libraryProgress, com.sadurni.attacca.library.DamageCheck.progress) { scan, chain, damage ->
                scan?.let { Work(it.done, it.total, it.remainingMs, R.string.library_scan_progress) }
                    // The check for damaged tracks keeps the app going with the screen off too.
                    ?: damage?.takeIf { chain == null }?.let { Work(it.done, it.total, it.remainingMs, R.string.damaged_checking) }
                    ?: chain?.let { Work(it.done, it.total, it.remainingMs, when (it.pass) {
                            com.sadurni.attacca.chain.ChainAnalyzer.Pass.CHAIN -> R.string.chain_library_progress
                            com.sadurni.attacca.chain.ChainAnalyzer.Pass.SILENCES -> R.string.chain_silence_progress
                            com.sadurni.attacca.chain.ChainAnalyzer.Pass.WHOLE -> R.string.chain_whole_progress
                        }) }
            },
        )
        follow(
            ID_CHAIN_LIST,
            R.string.work_chain_list_title,
            R.string.chain_library_progress,
            app.extras.flatMapLatest { extras -> extras?.chaining ?: flowOf(null) }.mapWork { Work(it.done, it.total, it.remainingMs) },
        )
    }

    private fun <T : Any> Flow<T?>.mapWork(transform: (T) -> Work): Flow<Work?> = map { it?.let(transform) }

    /** Removes every progress notification (the process is about to end). */
    fun cancelAll() {
        listOf(ID_SCAN, ID_CHAIN_LIBRARY, ID_CHAIN_LIST).forEach(manager::cancel)
    }

    /** Shows [work] under notification [id] while it runs, at most once a second. */
    private fun follow(id: Int, title: Int, label: Int, work: Flow<Work?>) {
        scope.launch {
            work.distinctUntilChanged().conflate().collect { w ->
                if (w == null) {
                    if (id == ID_SCAN) WorkService.end(app)
                    manager.cancel(id)
                } else {
                    show(id, title, label, w)
                    // Android drops updates that come too fast; the latest state waits meanwhile.
                    delay(UPDATE_MS)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun show(id: Int, title: Int, label: Int, w: Work) {
        val res = app.resources
        val text = if (w.total == 0) {
            res.getString(R.string.library_scan_finding)
        } else {
            progressText(res, w.label ?: label, w.done, w.total, w.remainingMs, percent = true)
        }
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, com.sadurni.attacca.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(res.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(w.total, w.done, w.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .build()
        // The music folder's jobs keep the app in the foreground, or with the screen off and
        // nothing playing they would crawl (also with notifications off: then it is not shown).
        if (id == ID_SCAN) WorkService.follow(app, notification)
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    companion object {
        private const val CHANNEL = "work"
        const val ID_SCAN = 2001
        private const val ID_CHAIN_LIBRARY = 2002
        private const val ID_CHAIN_LIST = 2003
        private const val UPDATE_MS = 1_000L
    }
}
