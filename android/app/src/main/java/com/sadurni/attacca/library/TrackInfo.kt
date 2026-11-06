package com.sadurni.attacca.library

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractTagFrame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File

/** Technical description of the audio stream; null fields are unknown. */
data class AudioDetails(
    val format: String? = null,
    val codec: String? = null,
    val bitrateKbps: Long? = null,
    val variableBitrate: Boolean? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: String? = null,
    val lossless: Boolean? = null,
    val durationMs: Long? = null,
)

/** One picture: embedded in the file ([pictureType] set) or a cover file in the folder ([file] set). */
class CoverDetails(
    val data: ByteArray?,
    val file: File?,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val pictureType: Int?,
    val description: String?,
)

class TrackDetails(
    val file: File,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val audio: AudioDetails,
    /** Every text tag in the file, by its key in the file's own tag format. */
    val tags: List<Pair<String, String>>,
    val covers: List<CoverDetails>,
    /** Why the tags could not be read, when they could not. */
    val tagError: String?,
)

/** Reads everything shown on the track information screen. Blocking. */
object TrackInfo {

    /** [withCovers] false skips the pictures (for the information lines of Now playing). */
    fun read(file: File, withCovers: Boolean = true): TrackDetails {
        val parsed = runCatching { AudioFileIO.read(file) }
        val audioFile = parsed.getOrNull()
        val audio = audioFile?.audioHeader?.let { h ->
            AudioDetails(
                // The tag library names some formats like words ("Mp3"): short names in capitals.
                format = h.format?.takeIf { it.isNotBlank() }?.let { if (it.length <= 4) it.uppercase() else it },
                codec = h.encodingType?.takeIf { it.isNotBlank() },
                bitrateKbps = h.bitRateAsNumber.takeIf { it > 0 },
                variableBitrate = h.isVariableBitRate,
                sampleRateHz = h.sampleRateAsNumber.takeIf { it > 0 },
                bitsPerSample = h.bitsPerSample.takeIf { it > 0 },
                channels = h.channels?.takeIf { it.isNotBlank() },
                lossless = h.isLossless,
                durationMs = (h.preciseTrackLength * 1000).toLong().takeIf { it > 0 },
            )
        } ?: platformDetails(file)

        val tag = audioFile?.tag
        val tags = mutableListOf<Pair<String, String>>()
        tag?.fields?.forEach { field ->
            runCatching {
                if (field.isBinary) return@runCatching
                val key = ((field as? AbstractTagFrame)?.body as? FrameBodyTXXX)?.description
                    ?.takeIf { it.isNotBlank() } ?: field.id
                val value = (field as? TagTextField)?.content ?: field.toString()
                if (value.isNotBlank()) tags += key to value.trim()
            }
        }

        val covers = mutableListOf<CoverDetails>()
        if (withCovers) runCatching {
            tag?.artworkList?.forEach { art ->
                val data = art.binaryData ?: return@forEach
                val bounds = imageBounds(data, null)
                covers += CoverDetails(
                    data = data,
                    file = null,
                    mimeType = art.mimeType?.takeIf { it.isNotBlank() },
                    width = bounds.first,
                    height = bounds.second,
                    sizeBytes = data.size.toLong(),
                    pictureType = art.pictureType,
                    description = art.description?.takeIf { it.isNotBlank() },
                )
            }
        }
        if (withCovers) file.parentFile?.let(AudioFiles::findFolderCover)?.let { cover ->
            val bounds = imageBounds(null, cover)
            covers += CoverDetails(
                data = null,
                file = cover,
                mimeType = when (cover.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                },
                width = bounds.first,
                height = bounds.second,
                sizeBytes = cover.length(),
                pictureType = null,
                description = null,
            )
        }

        return TrackDetails(
            file = file,
            sizeBytes = file.length(),
            modifiedMs = file.lastModified(),
            audio = audio,
            tags = tags,
            covers = covers,
            tagError = parsed.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
        )
    }

    /** Formats the tag library does not know (Opus…): what the platform decoder reports. */
    private fun platformDetails(file: File): AudioDetails {
        val extractor = MediaExtractor()
        val format = runCatching {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        }.getOrNull()
        extractor.release()
        val retriever = MediaMetadataRetriever()
        val bitrate = runCatching {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        }.getOrNull()
        runCatching { retriever.release() }
        fun int(key: String) = format?.takeIf { it.containsKey(key) }?.getInteger(key)
        return AudioDetails(
            codec = format?.getString(MediaFormat.KEY_MIME),
            bitrateKbps = (bitrate ?: int(MediaFormat.KEY_BIT_RATE)?.toLong())?.div(1000)?.takeIf { it > 0 },
            sampleRateHz = int(MediaFormat.KEY_SAMPLE_RATE),
            channels = int(MediaFormat.KEY_CHANNEL_COUNT)?.toString(),
            durationMs = format?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)?.div(1000),
        )
    }

    private fun imageBounds(data: ByteArray?, file: File?): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            if (data != null) BitmapFactory.decodeByteArray(data, 0, data.size, options) else BitmapFactory.decodeFile(file!!.path, options)
        }
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }
}
