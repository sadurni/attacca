package com.sadurni.attacca.playback

/**
 * Finds stretches of an MP3 that repeat, byte for byte, frames heard just before, and leaves them
 * out (frames go in one by one, in order, and come out the same unless dropped).
 *
 * Some damaged files (a faulty copy or download) hold, every few seconds, half a second of frames
 * written twice in a row: played as they are, the music jumps back and repeats itself. Real music
 * never encodes to the same frames twice, so a run of [MIN_RUN] frames each equal to the frame
 * [MIN_RUN]..[WINDOW] frames earlier is such a copy and is dropped; the music then goes on exactly
 * where the first copy ended (even the bit reservoir lines up). Silence and dither can repeat in
 * short cycles, so the run's frames must all differ from each other and carry sound.
 *
 * Frames known to be broken (see [walk]) are left out the same way.
 *
 * The frames after a dropped stretch come out earlier by its length, so the times stay those of
 * the sound (the player, its clip ends and the crossfade tails all count the same way).
 */
class FrameRepeats {
    private class Frame(val bytes: ByteArray, val hash: Int, val timeUs: Long, val flags: Int, val lengthUs: Long, val shiftUs: Long)

    /** Frames read before this one, newest last (also the dropped ones). */
    private val history = ArrayDeque<Frame>()

    /** Frames held while a possible repeat is checked, and how far back it repeats (0: none). */
    private val held = ArrayList<Frame>()
    private var distance = 0
    private var confirmed = false
    private var dropped = 0
    private var lastTimeUs = -1L

    /** How much was left out so far (µs). */
    var droppedUs = 0L
        private set

    /**
     * Takes the next frame; [out] gets the frames that go on, with their times moved back. A
     * [broken] frame is left out.
     */
    fun take(bytes: ByteArray, timeUs: Long, flags: Int, out: (ByteArray, Long, Int) -> Unit, broken: Boolean = false) {
        val lengthUs = if (lastTimeUs in 0 until timeUs) timeUs - lastTimeUs else 0L
        lastTimeUs = timeUs
        if (broken) {
            droppedUs += lengthUs
            return
        }
        val frame = Frame(bytes, bytes.contentHashCode(), timeUs, flags, lengthUs, droppedUs)
        if (distance > 0) {
            if (matches(frame, distance)) {
                remember(frame)
                if (confirmed) {
                    drop(frame)
                    return
                }
                held += frame
                if (held.size >= MIN_RUN) {
                    if (allDifferent(held) && held.any { loudestGain(it.bytes) >= LOUD_GAIN }) {
                        confirmed = true
                        for (f in held) drop(f)
                        held.clear()
                    } else {
                        release(out)
                    }
                }
                return
            }
            if (confirmed) {
                android.util.Log.d("RepeatSkip", "left out $dropped frames repeated from $distance frames before, up to ${timeUs / 1000} ms")
            }
            release(out)
        }
        val back = (MIN_RUN..minOf(WINDOW, history.size)).firstOrNull { matches(frame, it) }
        remember(frame)
        if (back != null) {
            distance = back
            held += frame
        } else {
            send(frame, out)
        }
    }

    /** At the end of the input: nothing stays held. */
    fun flush(out: (ByteArray, Long, Int) -> Unit) = release(out)

    /**
     * After a seek: what came before is no longer the previous audio; [droppedBefore] is what was
     * left out before the place reached (see [droppedBefore]).
     */
    fun reset(droppedBefore: Long = 0L) {
        held.clear()
        history.clear()
        distance = 0
        confirmed = false
        dropped = 0
        lastTimeUs = -1L
        droppedUs = droppedBefore
    }

    private fun drop(frame: Frame) {
        dropped++
        droppedUs += frame.lengthUs
    }

    private fun send(frame: Frame, out: (ByteArray, Long, Int) -> Unit) = out(frame.bytes, frame.timeUs - frame.shiftUs, frame.flags)

    /** Sends what was held back (it was no repeat) and ends the check. */
    private fun release(out: (ByteArray, Long, Int) -> Unit) {
        for (f in held) send(f, out)
        held.clear()
        distance = 0
        confirmed = false
        dropped = 0
    }

    /** The frame equals the one [back] frames before it. */
    private fun matches(frame: Frame, back: Int): Boolean {
        if (back > history.size) return false
        val earlier = history[history.size - back]
        return earlier.hash == frame.hash && earlier.bytes.contentEquals(frame.bytes)
    }

    private fun remember(frame: Frame) {
        history.addLast(frame)
        if (history.size > WINDOW) history.removeFirst()
    }

    private fun allDifferent(frames: List<Frame>): Boolean {
        for (i in frames.indices) for (j in 0 until i) {
            if (frames[i].hash == frames[j].hash && frames[i].bytes.contentEquals(frames[j].bytes)) return false
        }
        return true
    }

    companion object {
        private val BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val RATES = intArrayOf(44100, 48000, 32000)

        /** Per file (path and change time): the file times where something was left out, and the total so far. */
        private val maps = object : LinkedHashMap<String, LongArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LongArray>?) = size > 32
        }

        /**
         * How much of [file] is left out before its time [timeUs] (the file's own time, as a seek
         * reaches it). Reads the whole file once (kept for the last files asked about).
         */
        fun droppedBefore(file: java.io.File, timeUs: Long): Long {
            if (timeUs <= 0) return 0
            val key = file.path + "@" + file.lastModified()
            val map = synchronized(maps) { maps[key] } ?: scan(file).also { synchronized(maps) { maps[key] = it } }
            var total = 0L
            var i = 0
            while (i + 1 < map.size && map[i] <= timeUs) {
                total = map[i + 1]
                i += 2
            }
            return total
        }

        /** Pairs (file time, left out up to it) of [file], walking its frames as the player does. */
        private fun scan(file: java.io.File): LongArray {
            val data = runCatching { file.readBytes() }.getOrNull() ?: return LongArray(0)
            val repeats = FrameRepeats()
            val marks = ArrayList<Long>()
            walk(data) { offset, size, timeUs, broken ->
                val before = repeats.droppedUs
                repeats.take(data.copyOfRange(offset, offset + size), timeUs, 0, { _, _, _ -> }, broken)
                if (repeats.droppedUs != before) {
                    marks += timeUs
                    marks += repeats.droppedUs
                }
            }
            return marks.toLongArray()
        }

        /** Header fields every frame of a stream shares (sync, version, layer, rate), as the player checks them. */
        private const val STREAM_MASK = 0xFFFE0C00.toInt()

        /** Header fields an encoder never changes within a file: the above, protection and emphasis. */
        private const val FORMAT_MASK = 0xFFFF0C03.toInt()

        fun header(data: ByteArray, at: Int): Int =
            ((data[at].toInt() and 0xff) shl 24) or ((data[at + 1].toInt() and 0xff) shl 16) or
                ((data[at + 2].toInt() and 0xff) shl 8) or (data[at + 3].toInt() and 0xff)

        /** Size of the MPEG-1 Layer III frame with header [h], or 0 when it is none. */
        fun frameSize(h: Int): Int {
            if ((h ushr 21) and 0x7ff != 0x7ff || (h ushr 19) and 3 != 3 || (h ushr 17) and 3 != 1) return 0
            val bitrate = (h ushr 12) and 15
            val rate = (h ushr 10) and 3
            if (bitrate == 0 || bitrate == 15 || rate == 3) return 0
            return 144 * BITRATES[bitrate] * 1000 / RATES[rate] + ((h ushr 9) and 1)
        }

        /** A frame of another kind than the stream's [first] one (mono in stereo, other protection or emphasis): garbage. */
        fun otherFormat(h: Int, first: Int): Boolean =
            h and FORMAT_MASK != first and FORMAT_MASK || ((h ushr 6) and 3 == 3) != ((first ushr 6) and 3 == 3)

        /**
         * Walks the frames of an MPEG-1 Layer III file as the player reads them: [out] gets each
         * frame's place, size, time and whether it is broken, that is followed by bytes that are no
         * frame (its end is garbage: the player has to search for the next one) or of another kind
         * than the stream. False (nothing walked) when the file is no such stream.
         */
        fun walk(data: ByteArray, out: (offset: Int, size: Int, timeUs: Long, broken: Boolean) -> Unit): Boolean {
            var i = if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
                10 + ((data[6].toInt() and 0x7f) shl 21) + ((data[7].toInt() and 0x7f) shl 14) +
                    ((data[8].toInt() and 0x7f) shl 7) + (data[9].toInt() and 0x7f)
            } else {
                0
            }
            val tagged = data.size >= 128 && data[data.size - 128] == 'T'.code.toByte() &&
                data[data.size - 127] == 'A'.code.toByte() && data[data.size - 126] == 'G'.code.toByte()
            val end = if (tagged) data.size - 128 else data.size

            /** The frame header at [at] (of the stream [stream], or any) is followed right after by another one. */
            fun chained(at: Int, stream: Int): Boolean {
                if (at + 4 > end) return false
                val h = header(data, at)
                val size = frameSize(h)
                if (size <= 0 || (stream != 0 && h and STREAM_MASK != stream and STREAM_MASK)) return false
                val next = at + size
                if (next + 4 > end) return true
                val n = header(data, next)
                return frameSize(n) > 0 && n and STREAM_MASK == h and STREAM_MASK
            }
            while (i + 4 <= end && !chained(i, 0)) i++
            if (i + 4 > end) return false
            val first = header(data, i)
            val rate = RATES[(first ushr 10) and 3]
            // The first frame may be the encoder's information frame, not sound.
            val side = 4 + (if ((first ushr 16) and 1 == 0) 2 else 0) + (if ((first ushr 6) and 3 == 3) 17 else 32)
            if (i + side + 4 <= end) {
                val tag = String(data, i + side, 4, Charsets.ISO_8859_1)
                if (tag == "Xing" || tag == "Info") i += frameSize(first)
            }
            var index = 0L
            while (i + 4 <= end) {
                val h = header(data, i)
                val size = frameSize(h)
                if (size <= 0 || h and STREAM_MASK != first and STREAM_MASK || i + size > end) {
                    // Searching for the next frame, as the player does after a broken one.
                    i++
                    while (i + 4 <= end && !chained(i, first)) i++
                    continue
                }
                val next = i + size
                val followed = next + 4 > end || header(data, next).let { frameSize(it) > 0 && it and STREAM_MASK == first and STREAM_MASK }
                out(i, size, index * 1152 * 1_000_000L / rate, !followed || otherFormat(h, first))
                index++
                i = next
            }
            return true
        }

        /** Frames in a row that make a repeat (about 0.2 s). */
        const val MIN_RUN = 8

        /** How far back a repeat may come from, in frames (about 3.3 s). */
        const val WINDOW = 128

        /** Lowest global gain (side info) of a frame with sound; silence and dither stay below. */
        const val LOUD_GAIN = 140

        /** Highest global gain among an MPEG-1 Layer III frame's granules, or 255 when unsure. */
        fun loudestGain(frame: ByteArray): Int {
            if (frame.size < 40) return 255
            val h = ((frame[0].toInt() and 0xff) shl 24) or ((frame[1].toInt() and 0xff) shl 16) or
                ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
            val mpeg1 = (h ushr 19) and 3 == 3
            val layer3 = (h ushr 17) and 3 == 1
            if (!mpeg1 || !layer3) return 255
            val mono = (h ushr 6) and 3 == 3
            val crc = (h ushr 16) and 1 == 0
            val side = 4 + if (crc) 2 else 0
            var pos = 9 + (if (mono) 5 else 3) + (if (mono) 4 else 8)
            var loudest = 0
            repeat(if (mono) 2 else 4) {
                loudest = maxOf(loudest, bits(frame, side, pos + 21, 8))
                pos += 59
            }
            return loudest
        }

        /** [count] bits from bit [at] after byte [start]. */
        private fun bits(bytes: ByteArray, start: Int, at: Int, count: Int): Int {
            var value = 0
            for (k in 0 until count) {
                val bit = at + k
                val b = bytes[start + bit / 8].toInt() and 0xff
                value = (value shl 1) or ((b ushr (7 - bit % 8)) and 1)
            }
            return value
        }
    }
}
