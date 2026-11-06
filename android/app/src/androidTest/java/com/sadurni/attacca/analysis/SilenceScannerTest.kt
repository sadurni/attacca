package com.sadurni.attacca.analysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The silences [SilenceScanner] finds from the ends of a file are those of the whole analysis
 * ([AudioAnalyzer]), within one 10 ms window, for every audio file below [MUSIC] (MP3 CBR and VBR,
 * Ogg, FLAC, WAV…). Writes a report to the app's files (silence-report.txt) and to the log.
 */
@RunWith(AndroidJUnit4::class)
class SilenceScannerTest {

    @Test
    fun boundsFromTheEndsMatchTheWholeAnalysis() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(MUSIC).walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("mp3", "ogg", "flac", "wav", "m4a", "opus") }
            .sortedBy { it.path }
            .toList()
        assertTrue("no audio files below $MUSIC", files.isNotEmpty())
        val report = StringBuilder()
        var wholeMs = 0L
        var scanMs = 0L
        val bad = ArrayList<String>()
        for (threshold in listOf(-60f, -45f)) {
            for (file in files) {
                val t0 = System.nanoTime()
                val whole = AudioAnalyzer.analyze(file, threshold)
                val t1 = System.nanoTime()
                val scan = SilenceScanner.scan(file, threshold)
                val t2 = System.nanoTime()
                wholeMs += (t1 - t0) / 1_000_000
                scanMs += (t2 - t1) / 1_000_000
                val line = if (whole == null || scan == null) {
                    "${file.name} @$threshold: whole=${whole != null} scan=${scan != null}"
                } else {
                    val ds = scan.audioStartMs - whole.audioStartMs
                    val de = scan.audioEndMs - whole.audioEndMs
                    val dd = scan.durationMs - whole.durationMs
                    if (abs(ds) > WINDOW_TOLERANCE_MS || abs(de) > WINDOW_TOLERANCE_MS) bad += file.name
                    "${file.name} @$threshold: start ${whole.audioStartMs} / ${scan.audioStartMs} ($ds), end ${whole.audioEndMs} / ${scan.audioEndMs} ($de), " +
                        "duration ${whole.durationMs} / ${scan.durationMs} ($dd), ms ${(t1 - t0) / 1_000_000} / ${(t2 - t1) / 1_000_000}"
                }
                if ((whole == null) != (scan == null)) bad += file.name
                Log.i(TAG, line)
                report.append(line).append('\n')
            }
        }
        val summary = "${files.size} files x 2 thresholds: whole $wholeMs ms, ends $scanMs ms; off by more than a window: $bad"
        Log.i(TAG, summary)
        report.append(summary).append('\n')
        File(context.filesDir, "silence-report.txt").writeText(report.toString())
        assertTrue(summary, bad.isEmpty())
    }

    private companion object {
        const val MUSIC = "/storage/emulated/0/Music"
        const val TAG = "SilenceScannerTest"
        const val WINDOW_TOLERANCE_MS = 10L
    }
}
