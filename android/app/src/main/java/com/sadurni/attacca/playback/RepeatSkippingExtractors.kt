package com.sadurni.attacca.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.EOFException

/**
 * The default extractors, with MP3 files read through [RepeatSkippingExtractor].
 */
@OptIn(UnstableApi::class)
class RepeatSkippingExtractorsFactory : ExtractorsFactory {
    private val default = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = wrap(default.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(default.createExtractors(uri, responseHeaders), uri.takeIf { it.scheme == "file" }?.path?.let { java.io.File(it) })

    private fun wrap(extractors: Array<Extractor>, file: java.io.File? = null): Array<Extractor> =
        Array(extractors.size) { i -> extractors[i].let { if (it is Mp3Extractor) RepeatSkippingExtractor(it, file) else it } }
}

/**
 * Leaves out of an MP3 the stretches that repeat frames heard just before and the broken frames
 * (see [FrameRepeats]); the crossfade tails ([com.sadurni.attacca.analysis.TailDecoder]) leave out
 * the same ones.
 *
 * A frame is broken when the reader, right after it, has to search for the next frame (its end is
 * garbage: a damaged file), or when it is of another kind than the stream (mono inside stereo,
 * another protection or emphasis: garbage that happened to look like a frame). Decoded, those
 * make bursts of noise and change the decoder's output for a moment.
 */
@OptIn(UnstableApi::class)
class RepeatSkippingExtractor(private val delegate: Extractor, private val file: java.io.File? = null) : Extractor {
    private var output: RepeatSkippingTrackOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                RepeatSkippingTrackOutput(output.track(id, type), file).also { this@RepeatSkippingExtractor.output = it }

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(input, seekPosition)
        if (result == Extractor.RESULT_END_OF_INPUT) output?.flush()
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        // The times go on counting without what was left out before the place reached.
        output?.reset(file?.let { FrameRepeats.droppedBefore(it, timeUs) } ?: 0L)
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/** Left out while playing from which a file counts as damaged (µs). */
private const val NOTICE_US = 300_000L

@OptIn(UnstableApi::class)
private class RepeatSkippingTrackOutput(private val delegate: TrackOutput, private val file: java.io.File? = null) : TrackOutput {
    private val repeats = FrameRepeats()

    /** What was left out up to the last seek, and whether this file was already told damaged. */
    private var baselineUs = 0L
    private var reported = false

    /** Something more was left out than before the last seek: the file is damaged (once per file read). */
    private fun noticeDamage() {
        // A frame or two left out (often at the very end) is no damage anyone hears.
        if (reported || file == null || repeats.droppedUs - baselineUs < NOTICE_US) return
        reported = true
        com.sadurni.attacca.library.DamagedTracks.report(file.absolutePath, com.sadurni.attacca.library.DamagedTracks.Kind.BAD_FRAMES)
    }
    private val send: (ByteArray, Long, Int) -> Unit = { bytes, timeUs, flags -> forward(bytes, timeUs, flags, bytes.size, 0, null) }

    /** The sample being written. */
    private var sample = ByteArray(4096)
    private var sampleSize = 0

    /** Where in the file the last sample ended (-1: unknown), and whether the reader skipped bytes after it. */
    private var lastEnd = -1L
    private var skippedBefore = false

    /** The stream's first frame header (0: none yet). */
    private var firstHeader = 0

    /** The last whole frame, held until it is known whether the next one follows right after it. */
    private var held: ByteArray? = null
    private var heldTimeUs = 0L
    private var heldFlags = 0
    private var heldBroken = false

    override fun format(format: Format) {
        flush()
        delegate.format(format)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        ensure(sampleSize + length)
        val place = (input as? ExtractorInput)?.position ?: -1L
        if (sampleSize == 0) {
            skippedBefore = place >= 0 && lastEnd >= 0 && place != lastEnd
        }
        val read = input.read(sample, sampleSize, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        sampleSize += read
        lastEnd = if (place >= 0) place + read else -1L
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        ensure(sampleSize + length)
        data.readBytes(sample, sampleSize, length)
        sampleSize += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (offset != 0 || size != sampleSize || cryptoData != null) {
            // Not a plain whole frame: pass it on as it came.
            flush()
            forward(sample.copyOf(sampleSize), timeUs - repeats.droppedUs, flags, size, offset, cryptoData)
            sampleSize = 0
            return
        }
        val bytes = sample.copyOf(size)
        sampleSize = 0
        val header = if (size >= 4) FrameRepeats.header(bytes, 0) else 0
        if (firstHeader == 0 && FrameRepeats.frameSize(header) > 0) firstHeader = header
        // The frame before this one was followed by garbage the reader skipped: broken.
        held?.let { repeats.take(it, heldTimeUs, heldFlags, send, heldBroken || skippedBefore) }
        noticeDamage()
        held = bytes
        heldTimeUs = timeUs
        heldFlags = flags
        heldBroken = firstHeader != 0 && FrameRepeats.otherFormat(header, firstHeader)
        skippedBefore = false
    }

    /** Before the end of the input or a new format: nothing stays held. */
    fun flush() {
        held?.let { repeats.take(it, heldTimeUs, heldFlags, send, heldBroken) }
        held = null
        repeats.flush(send)
    }

    /** After a seek; [droppedBefore] was left out before the place reached. */
    fun reset(droppedBefore: Long) {
        repeats.reset(droppedBefore)
        baselineUs = droppedBefore
        // Left out before the place reached: known damaged.
        if (droppedBefore >= NOTICE_US && !reported && file != null) {
            reported = true
            com.sadurni.attacca.library.DamagedTracks.report(file.absolutePath, com.sadurni.attacca.library.DamagedTracks.Kind.BAD_FRAMES)
        }
        sampleSize = 0
        held = null
        lastEnd = -1L
        skippedBefore = false
    }

    private fun forward(bytes: ByteArray, timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        delegate.sampleData(ParsableByteArray(bytes), bytes.size)
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun ensure(capacity: Int) {
        if (sample.size < capacity) sample = sample.copyOf(maxOf(capacity, sample.size * 2))
    }
}
