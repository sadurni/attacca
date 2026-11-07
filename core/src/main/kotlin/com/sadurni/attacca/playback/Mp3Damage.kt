package com.sadurni.attacca.playback

/**
 * Damage in an MP3 that is heard, and its repair, as the phone and the PC both check them.
 *
 * The file is walked frame by frame as the player reads it ([FrameRepeats.walk]): frames broken
 * by garbage and stretches written twice are counted. A frame or two broken at either end is no
 * damage (the first and last frames often touch the tags or the encoder's padding), nor is a
 * single broken frame inside (26 ms the player leaves out unheard). From [MIN_BROKEN] broken
 * frames inside the music, or any stretch written twice from [MIN_REPEAT_MS] (the music jumps
 * back), it is damaged.
 */
object Mp3Damage {

    /** What is wrong: frames broken inside the music, or milliseconds written twice. */
    sealed class Damage {
        data class Broken(val frames: Int) : Damage()
        data class Repeats(val ms: Int) : Damage()
    }

    /** Frames at each end where a broken one is no damage. */
    const val EDGE_FRAMES = 4

    /** Broken frames inside the music from which a file is damaged (bursts that can be heard). */
    const val MIN_BROKEN = 3

    /** A stretch written twice from this long. */
    const val MIN_REPEAT_MS = 150

    /** The damage heard in the MP3 [data], or null when it is fine. Throws [NotMp3] when it is no stream the walk knows. */
    fun check(data: ByteArray): Damage? {
        val repeats = FrameRepeats()
        val brokenAt = ArrayList<Int>()
        var frames = 0
        val walked = FrameRepeats.walk(data) { offset, size, timeUs, isBroken ->
            if (isBroken) brokenAt += frames
            frames++
            repeats.take(data.copyOfRange(offset, offset + size), timeUs, 0, { _, _, _ -> }, isBroken)
        }
        if (!walked) throw NotMp3()
        repeats.flush { _, _, _ -> }
        val inside = brokenAt.count { it >= EDGE_FRAMES && it < frames - EDGE_FRAMES }
        val brokenUs = brokenAt.size * 1152L * 1_000_000L / 44_100
        val repeatedMs = ((repeats.droppedUs - brokenUs) / 1000).toInt().coerceAtLeast(0)
        return when {
            repeatedMs >= MIN_REPEAT_MS -> Damage.Repeats(repeatedMs)
            inside >= MIN_BROKEN -> Damage.Broken(inside)
            else -> null
        }
    }

    /** The file is not made of the MPEG-1 Layer III frames the walk reads. */
    class NotMp3 : Exception()

    /**
     * [data] written again without its broken and repeated frames: the tags kept, the encoder's
     * information frame with its frame count, size and seek table made new. Null when it is no
     * stream the walk knows.
     */
    fun repaired(data: ByteArray): ByteArray? {
        val id3End = (if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            10 + ((data[6].toInt() and 0x7f) shl 21) + ((data[7].toInt() and 0x7f) shl 14) + ((data[8].toInt() and 0x7f) shl 7) + (data[9].toInt() and 0x7f)
        } else {
            0
        }).coerceAtMost(data.size)
        val kept = ArrayList<ByteArray>()
        val repeats = FrameRepeats()
        val collect: (ByteArray, Long, Int) -> Unit = { bytes, _, _ -> kept += bytes }
        var firstAudio = -1
        var lastEnd = -1
        val walked = FrameRepeats.walk(data) { offset, size, timeUs, broken ->
            if (firstAudio < 0) firstAudio = offset
            lastEnd = offset + size
            repeats.take(data.copyOfRange(offset, offset + size), timeUs, 0, collect, broken)
        }
        if (!walked || kept.isEmpty() || firstAudio < 0) return null
        repeats.flush(collect)
        val info = infoFrame(data, id3End, firstAudio)?.copyOf()
        val audioBytes = kept.sumOf { it.size.toLong() }
        if (info != null) patchInfo(info, kept, audioBytes)
        val out = java.io.ByteArrayOutputStream(data.size)
        out.write(data, 0, id3End)
        info?.let { out.write(it) }
        for (frame in kept) out.write(frame)
        // What follows the music (APE, Lyrics3 or ID3 tags) stays as it was.
        if (lastEnd in 0 until data.size) out.write(data, lastEnd, data.size - lastEnd)
        return out.toByteArray()
    }

    /** The encoder's information frame ("Xing" / "Info") between the tag and the first sound frame, or null. */
    private fun infoFrame(data: ByteArray, from: Int, to: Int): ByteArray? {
        var i = from
        while (i + 4 <= to) {
            val h = FrameRepeats.header(data, i)
            val size = FrameRepeats.frameSize(h)
            if (size > 0 && i + size <= data.size) {
                val side = sideInfo(h)
                if (i + side + 8 <= data.size) {
                    val tag = String(data, i + side, 4, Charsets.ISO_8859_1)
                    if (tag == "Xing" || tag == "Info") return data.copyOfRange(i, i + size)
                }
            }
            i++
        }
        return null
    }

    /** Where in a frame its side information ends (the information frame's tag starts there). */
    private fun sideInfo(h: Int) = 4 + (if ((h ushr 16) and 1 == 0) 2 else 0) + (if ((h ushr 6) and 3 == 3) 17 else 32)

    /** Sets the information frame's frame count, byte count and seek table for the frames [kept]. */
    private fun patchInfo(info: ByteArray, kept: List<ByteArray>, audioBytes: Long) {
        val h = FrameRepeats.header(info, 0)
        var at = sideInfo(h) + 4
        if (at + 4 > info.size) return
        val flags = FrameRepeats.header(info, at)
        at += 4
        fun put(value: Long) {
            if (at + 4 > info.size) return
            info[at] = (value ushr 24).toByte(); info[at + 1] = (value ushr 16).toByte(); info[at + 2] = (value ushr 8).toByte(); info[at + 3] = value.toByte()
            at += 4
        }
        val total = info.size + audioBytes
        if (flags and 1 != 0) put(kept.size.toLong())
        if (flags and 2 != 0) put(total)
        if (flags and 4 != 0 && at + 100 <= info.size) {
            // Byte place of each percent of the frames, out of 256.
            val starts = LongArray(kept.size)
            var pos = info.size.toLong()
            for ((k, f) in kept.withIndex()) {
                starts[k] = pos
                pos += f.size
            }
            for (p in 0 until 100) {
                val frame = (p.toLong() * kept.size / 100).toInt().coerceIn(0, kept.size - 1)
                info[at + p] = (starts[frame] * 256 / total).coerceIn(0, 255).toByte()
            }
        }
    }

    /**
     * Writes [file] again from [data] repaired: [keepOriginal] keeps the original beside it with
     * ".bk" added to its name (not taken for music), else it is deleted. True when done.
     */
    fun repairFile(file: java.io.File, keepOriginal: Boolean): Boolean {
        val fixed = repaired(file.readBytes()) ?: return false
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        return try {
            tmp.writeBytes(fixed)
            if (keepOriginal) {
                val copy = java.io.File(file.parentFile, file.name + ".bk")
                if (copy.exists()) copy.delete()
                if (!file.renameTo(copy)) return false.also { tmp.delete() }
            } else if (!file.delete()) {
                return false.also { tmp.delete() }
            }
            tmp.renameTo(file)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
