package com.sadurni.attacca.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import com.sadurni.attacca.dsp.DspEngine
import com.sadurni.attacca.dsp.HeardPosition
import com.sadurni.attacca.dsp.ReplayGainInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds the audio renderer with Attacca's DSP chain. */
@OptIn(UnstableApi::class)
class AttaccaRenderersFactory(
    context: Context,
    private val engine: DspEngine,
    /** Output buffer length in ms, read each time the sink configures (see [TunableBufferSize]). */
    private val bufferMs: () -> Int,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(engine.createProcessorChain())
            .setAudioTrackBufferSizeProvider(TunableBufferSize(bufferMs))
            .build()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            ReplayGainAudioRenderer(
                context,
                codecAdapterFactory,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                audioSink,
                engine,
            ),
        )
    }
}

/**
 * Output buffer of a chosen length. The default ~250 ms buffer runs dry when the screen is off
 * and the system moves the app to slow cores; a longer one only delays setting changes by as much.
 *
 * The sink asks at every configuration, but keeps its AudioTrack across gapless track changes: a
 * new length takes effect when the next AudioTrack is created (after a seek, a skip or a stop).
 */
@OptIn(UnstableApi::class)
private class TunableBufferSize(private val bufferMs: () -> Int) : DefaultAudioSink.AudioTrackBufferSizeProvider {
    private var providerMs = -1
    private var provider: DefaultAudioSink.AudioTrackBufferSizeProvider = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int {
        val ms = bufferMs()
        if (ms != providerMs) {
            providerMs = ms
            provider = DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(ms * 1000)
                .setMaxPcmBufferDurationUs(ms * 1000)
                .build()
        }
        return provider.getBufferSizeInBytes(
            minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed,
        )
    }
}

/**
 * Publishes each track's ReplayGain values to the [DspEngine] exactly when that track's first
 * decoded buffer reaches the audio sink.
 *
 * Gapless playback keeps a single sink across tracks, so the processors never see track
 * boundaries; switching gain from a player listener would be late by the whole audio buffer. The
 * renderer knows the boundary precisely through its output stream offset, which changes right
 * after the last buffer of the previous track has been handed to the sink.
 */
@OptIn(UnstableApi::class)
private class ReplayGainAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val engine: DspEngine,
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink,
) {
    // Only touched on the playback thread.
    private val gainsByStreamOffset = HashMap<Long, ReplayGainInfo>()
    private val periodByStreamOffset = HashMap<Long, Any>()
    private var publishedOffset: Long? = null

    /** Stream offsets and their periods, to tell which track is being heard (it lags the output). */
    private val heardStreams = java.util.TreeMap<Long, Any>()

    /*
     * A damaged MP3 holds stretches of garbage the decoder takes for frames of another kind: mono,
     * dual channel, 32 or 48 kHz. The platform decoder then changes its output format on its own,
     * and back; reconfiguring the sink for that drained the whole chain in the middle of the song
     * (the crossfade took it for the end of the track) and could leave the rest in mono. Instead,
     * within one track the sink keeps the track's format: such frames are brought to its channels
     * here (a mono frame goes to both sides) and played at its rate (a few milliseconds of what is
     * garbage anyway).
     */
    private var sinkChannels = 0
    private var sinkRate = 0
    private var sampleBytes = 2

    /** The track's own format (as its extractor gave it) when the sink was configured. */
    private var trackChannels = 0
    private var trackRate = 0

    /** The decoder's channels / rate while they differ from the sink's, or 0. */
    private var decoderChannels = 0
    private var decoderRate = 0
    private var converted: ByteBuffer? = null
    private var convertedIndex = -1
    private var convertedTimeUs = C.TIME_UNSET

    /*
     * Frames left out of a song (see RepeatSkippingExtractor) make it shorter than its time says:
     * the decoder times its output by the samples it gives, so the next track's buffers come later
     * than the sink expects. The sink takes a jump of over 0.2 s within gapless playback for a
     * discontinuity and drains the whole chain (the crossfade would take it for the end of a
     * track). So the times from such a jump on are moved back to close it, until the next seek;
     * the position reported stays the file's time.
     */
    private var gapShiftUs = 0L
    private var expectedTimeUs = C.TIME_UNSET
    private var gapCheckedIndex = -1
    private var gapCheckedTimeUs = C.TIME_UNSET

    /** From each sink time on (key), how far the times were moved back (value). */
    private val gapShifts = java.util.TreeMap<Long, Long>()

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val decoded = mediaFormat?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
        val rate = mediaFormat?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0
        // The same track, and the decoder changed its output by its own doing (or changed it back).
        // A new track in the same format still goes through: its gapless trims come with it.
        val sameTrack = sinkChannels > 0 && format.channelCount == trackChannels && format.sampleRate == trackRate
        val differs = decoded != sinkChannels || rate != sinkRate
        val wasDiffering = decoderChannels != 0 || decoderRate != 0
        if (sameTrack && decoded in 1..8 && rate > 0 && (differs || wasDiffering)) {
            decoderChannels = if (decoded == sinkChannels) 0 else decoded
            decoderRate = if (rate == sinkRate) 0 else rate
            android.util.Log.d("Renderer", "decoder now gives $decoded ch $rate Hz; the sink keeps $sinkChannels ch $sinkRate Hz")
            return
        }
        decoderChannels = 0
        decoderRate = 0
        trackChannels = format.channelCount
        trackRate = format.sampleRate
        sinkChannels = decoded.takeIf { it > 0 } ?: format.channelCount
        sinkRate = rate.takeIf { it > 0 } ?: format.sampleRate
        sampleBytes = when (mediaFormat?.takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }?.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            android.media.AudioFormat.ENCODING_PCM_32BIT -> 4
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        super.onOutputFormatChanged(format, mediaFormat)
    }

    /** [buffer] in the sink's channels; the same object while the codec offers the same buffer. */
    private fun inSinkChannels(buffer: ByteBuffer, index: Int, timeUs: Long): ByteBuffer {
        val cached = converted
        if (cached != null && index == convertedIndex && timeUs == convertedTimeUs) return cached
        val inCh = decoderChannels
        val outCh = sinkChannels
        val b = sampleBytes
        val frames = buffer.remaining() / (inCh * b)
        val need = frames * outCh * b
        val out = cached?.takeIf { it.capacity() >= need } ?: ByteBuffer.allocateDirect(maxOf(need, 16384)).order(ByteOrder.nativeOrder())
        out.clear()
        val start = buffer.position()
        for (f in 0 until frames) {
            val base = start + f * inCh * b
            for (c in 0 until outCh) {
                val from = base + minOf(c, inCh - 1) * b
                for (k in 0 until b) out.put(buffer.get(from + k))
            }
        }
        out.flip()
        converted = out
        convertedIndex = index
        convertedTimeUs = timeUs
        return out
    }

    override fun getPositionUs(): Long {
        val sinkUs = super.getPositionUs()
        val positionUs = sinkUs + (gapShifts.floorEntry(sinkUs)?.value ?: 0L)
        // What is heard right now, in its own period's time (a file's time, also when clipped).
        heardStreams.floorEntry(positionUs)?.let { (offset, period) ->
            engine.heard = HeardPosition(period, positionUs - offset, System.nanoTime(), playbackParameters.speed, positionUs)
        }
        return positionUs
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        gainsByStreamOffset[offsetUs] = ReplayGainInfo.fromFormats(formats)
        periodByStreamOffset[offsetUs] = mediaPeriodId.periodUid
        heardStreams[offsetUs] = mediaPeriodId.periodUid
        while (heardStreams.size > KEPT_GENERATIONS) heardStreams.pollFirstEntry()
        // After a seek a new stream may reuse an offset; force it to be published again.
        if (publishedOffset == offsetUs) publishedOffset = null
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        publishIfStreamChanged()
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        // The sample format can carry tags missing from the track selection format.
        val info = ReplayGainInfo.fromMetadata(formatHolder.format?.metadata)
        val offset = streamOffsetUs
        if (!info.isEmpty && offset != C.TIME_UNSET) {
            gainsByStreamOffset[offset] = info
            if (publishedOffset == offset) publishedOffset = null
        }
        return super.onInputFormatChanged(formatHolder)
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean {
        publishIfStreamChanged()
        engine.presentationUs = bufferPresentationTimeUs
        if (buffer != null && buffer.hasRemaining()) closeGap(buffer, bufferIndex, bufferPresentationTimeUs)
        val toSink = if (decoderChannels > 0 && buffer != null && buffer.hasRemaining()) inSinkChannels(buffer, bufferIndex, bufferPresentationTimeUs) else buffer
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            toSink,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs - gapShiftUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format,
        )
    }

    /** Notes a jump forward before this buffer (once per buffer: the codec offers it until taken). */
    private fun closeGap(buffer: ByteBuffer, index: Int, timeUs: Long) {
        if (index == gapCheckedIndex && timeUs == gapCheckedTimeUs) return
        gapCheckedIndex = index
        gapCheckedTimeUs = timeUs
        if (expectedTimeUs != C.TIME_UNSET && timeUs - expectedTimeUs > MAX_GAP_US) {
            gapShiftUs += timeUs - expectedTimeUs
            gapShifts[timeUs - gapShiftUs] = gapShiftUs
            while (gapShifts.size > KEPT_GAPS) gapShifts.pollFirstEntry()
            android.util.Log.d("Renderer", "closed a gap of ${(timeUs - expectedTimeUs) / 1000} ms at ${timeUs / 1000} ms")
        }
        val channels = if (decoderChannels > 0) decoderChannels else sinkChannels
        val rate = if (decoderRate > 0) decoderRate else sinkRate
        expectedTimeUs = if (channels > 0 && rate > 0) {
            timeUs + buffer.remaining() / (channels * sampleBytes) * 1_000_000L / rate
        } else {
            C.TIME_UNSET
        }
    }

    private fun resetGaps() {
        gapShiftUs = 0L
        expectedTimeUs = C.TIME_UNSET
        gapCheckedIndex = -1
        gapShifts.clear()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        resetGaps()
        super.onPositionReset(positionUs, joining)
    }

    override fun onDisabled() {
        resetGaps()
        sinkChannels = 0
        decoderChannels = 0
        decoderRate = 0
        convertedIndex = -1
        gainsByStreamOffset.clear()
        periodByStreamOffset.clear()
        heardStreams.clear()
        engine.heard = null
        publishedOffset = null
        super.onDisabled()
    }

    private fun publishIfStreamChanged() {
        val offset = outputStreamOffsetUs
        if (offset == C.TIME_UNSET || offset == publishedOffset) return
        val info = gainsByStreamOffset[offset] ?: return
        publishedOffset = offset
        // Tags first; without them, the loudness the analysis measured (if it is ready).
        engine.currentGainFromTags = !info.isEmpty
        engine.currentReplayGain = if (info.isEmpty) {
            periodByStreamOffset[offset]?.let { engine.measuredGains[it] } ?: info
        } else {
            info
        }
        android.util.Log.d("ReplayGain", "track starts: ${if (info.isEmpty) "measured" else "tags"} ${engine.currentReplayGain}")
        val generation = engine.streamGeneration + 1
        periodByStreamOffset[offset]?.let { engine.periodOfGeneration[generation] = it }
        engine.periodOfGeneration.keys.removeAll { it < generation - KEPT_GENERATIONS }
        engine.streamGeneration = generation
        gainsByStreamOffset.keys.removeAll { it < offset }
        periodByStreamOffset.keys.removeAll { it < offset }
    }

    private companion object {
        const val KEPT_GENERATIONS = 8

        /** A longer jump forward between two buffers is a gap to close. */
        const val MAX_GAP_US = 100_000L
        const val KEPT_GAPS = 64
    }
}
