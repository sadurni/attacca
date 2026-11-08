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
            if (isBroken || !sideInfoValid(data, offset, size)) brokenAt += frames
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

    /** Broken frames this close to each other make one damaged stretch, left out whole. */
    const val CLUSTER_GAP = 24

    /** Frames left out too on each side of a damaged stretch (bits gone wrong there that no check sees). */
    const val MARGIN = 3

    /** What a repair did: the file written again, the sound left out (ms) of [totalMs], and the frames made silent. */
    class Repair(val data: ByteArray, val cutMs: Int, val silenced: Int, val totalMs: Int) {
        /** So much was left out that a new copy of the file would be better (more than [MUCH_LOST] of it). */
        val muchLost get() = totalMs > 0 && cutMs > totalMs * MUCH_LOST
    }

    /** Part of a track left out from which a repair says a new copy would be better. */
    const val MUCH_LOST = 0.03

    /**
     * [data] written again without its broken and repeated frames: the tags kept, the encoder's
     * information frame with its frame count, size and seek table made new. Null when it is no
     * stream the walk knows.
     */
    fun repaired(data: ByteArray): ByteArray? = repair(data)?.data

    /**
     * The repair of [data] and what it did.
     *
     * Broken frames close together mark a damaged stretch (bits gone wrong all through it, the
     * frames in between too), so the stretch goes whole; a lone broken frame goes alone. A frame
     * may keep part of its sound in the frames just before it (the bit reservoir): the first
     * frames after a cut that reach back past it would play the wrong bytes (a burst of noise),
     * so their own sound is made silent (26 ms each) while their bytes stay for the frames after.
     */
    fun repair(data: ByteArray): Repair? {
        val id3End = (if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            10 + ((data[6].toInt() and 0x7f) shl 21) + ((data[7].toInt() and 0x7f) shl 14) + ((data[8].toInt() and 0x7f) shl 7) + (data[9].toInt() and 0x7f)
        } else {
            0
        }).coerceAtMost(data.size)
        val offsets = ArrayList<Int>()
        val sizes = ArrayList<Int>()
        val times = ArrayList<Long>()
        val brokens = ArrayList<Boolean>()
        val walked = FrameRepeats.walk(data) { offset, size, timeUs, broken ->
            offsets += offset; sizes += size; times += timeUs; brokens += broken || !sideInfoValid(data, offset, size)
        }
        val n = offsets.size
        if (!walked || n == 0) return null
        // The damaged stretches.
        val drop = BooleanArray(n)
        var k = 0
        while (k < n) {
            if (!brokens[k]) { k++; continue }
            var last = k
            var j = k + 1
            while (j < n && j - last <= CLUSTER_GAP) {
                if (brokens[j]) last = j
                j++
            }
            for (i in maxOf(0, k - MARGIN)..minOf(n - 1, last + MARGIN)) drop[i] = true
            k = last + 1
        }
        // Where the stream is cut (bytes skipped as garbage, or frames left out as broken), counted up to each frame.
        val cuts = IntArray(n)
        for (i in 1 until n) {
            val cut = offsets[i] != offsets[i - 1] + sizes[i - 1] || drop[i - 1]
            cuts[i] = cuts[i - 1] + if (cut) 1 else 0
        }
        val kept = ArrayList<ByteArray>()
        val keptIndex = ArrayList<Int>()
        val index = java.util.IdentityHashMap<ByteArray, Int>()
        val repeats = FrameRepeats()
        val collect: (ByteArray, Long, Int) -> Unit = { bytes, _, _ -> kept += bytes; keptIndex += index[bytes] ?: -1 }
        for (i in 0 until n) {
            val bytes = data.copyOfRange(offsets[i], offsets[i] + sizes[i])
            index[bytes] = i
            repeats.take(bytes, times[i], 0, collect, drop[i])
        }
        repeats.flush(collect)
        if (kept.isEmpty()) return null
        // Frames reaching back past a cut play silent.
        var silenced = 0
        var avail = 0
        var before = -1
        for ((p, frame) in kept.withIndex()) {
            val i = keptIndex[p]
            if (i < 0 || before < 0 || cuts[i] != cuts[before]) avail = 0
            val h = FrameRepeats.header(frame, 0)
            val crc = if ((h ushr 16) and 1 == 0) 2 else 0
            val side = sideInfo(h)
            if (frame.size > side) {
                val reach = ((frame[4 + crc].toInt() and 0xff) shl 1) or ((frame[5 + crc].toInt() and 0xff) ushr 7)
                if (reach > avail && crc == 0) {
                    for (b in 4 until side) frame[b] = 0
                    silenced++
                }
                avail = minOf(511, avail + frame.size - side)
            }
            if (i >= 0) before = i
        }
        val lostUs = (n - kept.size) * 1152L * 1_000_000L / 44_100
        val firstAudio = offsets[0]
        val lastEnd = offsets[n - 1] + sizes[n - 1]
        val info = infoFrame(data, id3End, firstAudio)?.copyOf()
        val audioBytes = kept.sumOf { it.size.toLong() }
        if (info != null) patchInfo(info, kept, audioBytes)
        val out = java.io.ByteArrayOutputStream(data.size)
        out.write(data, 0, id3End)
        info?.let { out.write(it) }
        for (frame in kept) out.write(frame)
        // What follows the music (APE, Lyrics3 or ID3 tags) stays as it was.
        if (lastEnd in 0 until data.size) out.write(data, lastEnd, data.size - lastEnd)
        val totalUs = n * 1152L * 1_000_000L / 44_100
        return Repair(out.toByteArray(), (lostUs / 1000).toInt(), silenced, (totalUs / 1000).toInt())
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

    /**
     * Whether the side information of the frame at [offset] can be right: bits gone wrong there
     * give values no encoder writes (more sound bits than the frame and its reservoir hold, more
     * frequency lines than there are, a switched window of the normal kind), which a decoder
     * turns into noise or stops at.
     */
    fun sideInfoValid(data: ByteArray, offset: Int, size: Int): Boolean {
        val h = FrameRepeats.header(data, offset)
        val crc = if ((h ushr 16) and 1 == 0) 2 else 0
        val mono = (h ushr 6) and 3 == 3
        val channels = if (mono) 1 else 2
        val end = sideInfo(h)
        if (size < end || offset + end > data.size) return false
        var bit = (offset + 4 + crc) * 8
        fun get(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((data[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        val reach = get(9)
        get(if (mono) 5 else 3)
        get(4 * channels)
        var bits = 0
        repeat(2) {
            repeat(channels) {
                bits += get(12)
                if (get(9) > 288) return false
                get(8); get(4)
                if (get(1) == 1) {
                    if (get(2) == 0) return false
                    get(1); get(10); get(9)
                } else {
                    get(15); get(4); get(3)
                }
                get(3)
            }
        }
        return bits <= 8 * (reach + size - end)
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
     * Writes [file] again from its data repaired: [keepOriginal] keeps the original beside it with
     * ".bk" added to its name (not taken for music), else it is deleted. When such a copy is there
     * already (a repair before), the repair starts again from it. The repair, or null when it failed.
     */
    fun repairFile(file: java.io.File, keepOriginal: Boolean): Repair? {
        val copy = java.io.File(file.parentFile, file.name + ".bk")
        val fromCopy = copy.isFile
        val fixed = repair((if (fromCopy) copy else file).readBytes()) ?: return null
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        return try {
            tmp.writeBytes(fixed.data)
            if (fromCopy) {
                if (!file.delete()) return null.also { tmp.delete() }
                if (!keepOriginal) copy.delete()
            } else if (keepOriginal) {
                if (!file.renameTo(copy)) return null.also { tmp.delete() }
            } else if (!file.delete()) {
                return null.also { tmp.delete() }
            }
            if (tmp.renameTo(file)) fixed else null
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }
}
