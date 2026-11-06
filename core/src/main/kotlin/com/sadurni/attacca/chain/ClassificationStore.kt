package com.sadurni.attacca.chain

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * A collection's classification kept in a file between runs, with a fingerprint of what made it:
 * classifying thousands of tracks (and reading their sound profiles) takes a while, and only has
 * to be done again when the tracks, their measures or the moods given by hand change.
 */
object ClassificationStore {
    private const val MAGIC = 0x41434C53 // "ACLS"

    /** Raised when [Moods.classify] changes its results, so older files are made again. */
    private const val VERSION = 1

    /**
     * The fingerprint of a classification made from [ids] (in order) with [features], [sounds]
     * (a hash of each track's sound profile, 0 without one) and the moods given by hand.
     */
    fun key(
        ids: List<String>,
        features: List<TrackFeatures>,
        sounds: List<Long>,
        corrections: Map<Int, Mood> = emptyMap(),
        spreadFrom: Set<Int> = corrections.keys,
    ): Long {
        val h = Hash().add(VERSION.toLong()).add(ids.size.toLong())
        for (i in ids.indices) {
            h.add(ids[i])
            for (v in features[i].toArray()) h.add(v.toRawBits().toLong())
            h.add(sounds[i])
        }
        for ((i, mood) in corrections.toSortedMap()) h.add(i.toLong()).add(mood.ordinal.toLong()).add(if (i in spreadFrom) 1 else 0)
        return h.value
    }

    /** A hash of a sound profile's bytes for [key] (0 for none). */
    fun soundHash(bytes: ByteArray?): Long {
        if (bytes == null) return 0
        val h = Hash()
        for (b in bytes) h.add(b.toLong())
        return h.value or 1
    }

    fun write(file: File, key: Long, ids: List<String>, c: Classification) {
        val temp = File(file.path + ".part")
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(temp.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(key)
            out.writeInt(ids.size)
            for (id in ids) out.writeUTF(id)
            for (i in ids.indices) {
                out.writeByte(c.moods[i].ordinal)
                out.writeByte(c.secondMoods[i]?.ordinal ?: -1)
                out.writeFloat(c.energy[i])
                out.writeFloat(c.positivity[i])
                out.writeFloat(c.voice[i])
                out.writeInt(c.families[i])
            }
            out.writeInt(c.familyNames.size)
            for (name in c.familyNames) {
                out.writeInt(name.traits.size)
                for ((trait, high) in name.traits) {
                    out.writeByte(trait.ordinal)
                    out.writeBoolean(high)
                }
            }
            val dim = c.vectors.firstOrNull()?.size ?: 0
            out.writeInt(dim)
            for (v in c.vectors) for (x in v) out.writeFloat(x)
        }
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    /**
     * The tracks and classification kept in [file], when made with [key] (any key when null);
     * null when there is none or it does not match.
     */
    fun read(file: File, key: Long?): Pair<List<String>, Classification>? = runCatching {
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            val stored = input.readLong()
            if (key != null && stored != key) return null
            val n = input.readInt()
            val ids = List(n) { input.readUTF() }
            val moods = ArrayList<Mood>(n)
            val second = ArrayList<Mood?>(n)
            val energy = FloatArray(n)
            val positivity = FloatArray(n)
            val voice = FloatArray(n)
            val families = IntArray(n)
            val allMoods = Mood.entries
            for (i in 0 until n) {
                moods += allMoods[input.readByte().toInt()]
                second += input.readByte().toInt().takeIf { it >= 0 }?.let { allMoods[it] }
                energy[i] = input.readFloat()
                positivity[i] = input.readFloat()
                voice[i] = input.readFloat()
                families[i] = input.readInt()
            }
            val names = List(input.readInt()) {
                FamilyName(List(input.readInt()) { Trait.entries[input.readByte().toInt()] to input.readBoolean() })
            }
            val dim = input.readInt()
            val vectors = Array(n) { FloatArray(dim) { input.readFloat() } }
            ids to Classification(moods, second, energy, positivity, voice, families, names, vectors)
        }
    }.getOrNull()

    /** 64-bit FNV-1a. */
    private class Hash {
        var value = -0x340d631b7bdddcdbL
            private set

        fun add(x: Long): Hash {
            var v = x
            repeat(8) {
                value = (value xor (v and 0xFF)) * 0x100000001b3L
                v = v ushr 8
            }
            return this
        }

        fun add(s: String): Hash {
            for (ch in s) add(ch.code.toLong())
            return add(-1)
        }
    }
}
