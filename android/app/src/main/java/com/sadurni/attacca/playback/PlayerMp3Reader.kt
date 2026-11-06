package com.sadurni.attacca.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.File

/**
 * The frames of an MP3 file, with their times, exactly as the player reads them: the same
 * extractor and the same checks ([RepeatSkippingExtractor]), so a damaged file gives the same
 * frames at the same times (the crossfade tails must line up with what was played). Close it.
 */
@OptIn(UnstableApi::class)
class PlayerMp3Reader(private val file: File) : AutoCloseable {
    private val extractor = RepeatSkippingExtractor(Mp3Extractor(), file)
    private val frames = ArrayDeque<Pair<ByteArray, Long>>()
    // The whole file at once: reading it in the extractor's small steps from storage was slow.
    private val source = ByteArrayDataSource(file.readBytes())
    private var input: ExtractorInput = open(0)
    private val position = PositionHolder()
    private var ended = false

    init {
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = Collector()
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
    }

    /** The next frame and its time (µs), or null at the end. */
    fun next(): Pair<ByteArray, Long>? {
        while (frames.isEmpty() && !ended) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> ended = true
                Extractor.RESULT_SEEK -> {
                    source.close()
                    input = open(position.position)
                }
            }
        }
        return frames.removeFirstOrNull()
    }

    private fun open(at: Long): ExtractorInput {
        val length = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(at).build())
        return DefaultExtractorInput(source, at, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else at + length)
    }

    override fun close() {
        runCatching { extractor.release() }
        runCatching { source.close() }
    }

    /** Gathers the frames the extractor gives. */
    private inner class Collector : TrackOutput {
        private var sample = ByteArray(4096)
        private var size = 0

        override fun format(format: Format) {}

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (sample.size < size + length) sample = sample.copyOf(maxOf(size + length, sample.size * 2))
            val read = input.read(sample, size, length)
            if (read == C.RESULT_END_OF_INPUT) return read
            size += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (sample.size < size + length) sample = sample.copyOf(maxOf(size + length, sample.size * 2))
            data.readBytes(sample, size, length)
            size += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            frames.addLast(sample.copyOfRange(this.size - offset - size, this.size - offset) to timeUs)
            this.size = 0
        }
    }
}
