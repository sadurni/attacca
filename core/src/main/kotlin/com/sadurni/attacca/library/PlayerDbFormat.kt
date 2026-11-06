package com.sadurni.attacca.library

import com.sadurni.attacca.chain.SoundProfile
import com.sadurni.attacca.chain.Moods
import com.sadurni.attacca.chain.Classification
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.FamilyName
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attacca.chain.Trait
import java.io.ByteArrayOutputStream
import java.io.File
import com.sadurni.attacca.dsp.CrossfadePlanner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The library for players (`.bpcr2`): the Rockbox plugin and the web generator make chained lists
 * from it with Attacca's rules, without measuring anything. Everything is worked out here (moods,
 * sound families, percentiles, the similarity vectors, edges in integers), so a player without
 * floating point only adds and compares. Written by Attacca and by Attacca Desktop alike.
 *
 * Little-endian. Header (64 bytes):
 *  0 "BPCR2\0\0\0" · 8 tracks u32 · 12 record size u32 · 16 dictionaries offset · 20 records offset ·
 *  24 edges offset · 28 strings offset · 32 strings size (all u32) · 36 artist spacing u16 ·
 *  38 edge seconds u16 · 40 creation date (string) u32 · 44 dictionaries u32 (4) · 48 music folder
 *  on the device (string) u32 · 52 vector size u16 · 54 edge size in bytes u16 · 56 first year u16 ·
 *  58 last year u16 · 60 lowest BPM ×100 u16 · 62 highest BPM ×100 u16.
 * Dictionaries (artist, genre tag, mood, sound family): count u32, then that many string offsets u32.
 * Record (160 bytes): 0 title · 4 path within the music folder · 8 album (strings, u32) · 12 hash of
 *  the full path on the device · 16 hash of its last two parts (FNV-1a, ASCII lower case) · 20 edge
 *  block u32 (none: 0xFFFFFFFF) · 24 artist u16 · 26 genre u16 (none: 0xFFFF) · 28 seconds u16 ·
 *  30 year u16 (0: none) · 32 BPM ×100 u16 · 34 energy · 36 positivity · 38 voice (percentiles
 *  ×10000, u16) · 40 mood u8 · 41 family u8 · 42 flags u8 (1 included, 2 start edge, 4 end edge) ·
 *  43 second mood + 1 u8 (a track between two moods; 0: none) · 44 similarity vector i16 ×1000 (58 values: the sound profile's main directions and
 *  timbre, see Moods; a library classified by traits only fills the first 10) · 160 how it starts
 *  and ends, for the player's automatic crossfade ([Ends]): start of the sound u16 · silence after
 *  the sound u16 · length of its own fade-out u16 (all ×10 ms) · ending u8 (0 unknown, 1 neither,
 *  2 fades out by itself, 3 ends hard) · flags u8 (1 starts softly). Readers step by the record
 *  size of the header, so those written before (160 bytes) and after read each other's files.
 * Edge block: start then end, each 47 i16: pitch classes centred and scaled to length 16384 (12),
 *  bands ×100 dB (16), level ×100 dB, ln(flux + 0.05) ×1000, timbre coefficients ×100 (13),
 *  log2(tempo) ×1000 (0: none), beat clarity ×10000, key (0–11 major, 12–23 minor; −1: an edge
 *  described before timbre and tempo, scored the old way), key clarity ×10000.
 */
object PlayerDbFormat {
    const val EXTENSION = "bpcr2"
    private const val HEADER = 64
    private const val VECTOR = Moods.SOUND_COMPONENTS + SoundProfile.TIMBRE_SIZE
    private const val RECORD = 44 + VECTOR * 2 + 8

    /** The blend length the endings are judged for (the player's own length may differ). */
    const val REFERENCE_BLEND_MS = 10_000
    private const val EDGE_VALUES = 47
    private const val NONE16 = 0xFFFF
    private const val NONE32 = -1

    /** As Attacca's edge similarity. */
    private const val FLUX_FLOOR = 0.05f

    /** One measured track; [path] is within the music folder, with '/' separators. */
    class Track(
        val path: String,
        val title: String,
        val album: String,
        val artist: String,
        val genre: String,
        val durationMs: Long,
        /** 0: unknown. */
        val year: Int,
        val features: TrackFeatures,
        val head: EdgeDescriptor?,
        val tail: EdgeDescriptor?,
        val included: Boolean,
        /** How it starts and ends; null when not measured. */
        val ends: Ends? = null,
    )

    /**
     * How a track starts and ends, for the player's automatic crossfade (as Attacca's
     * [CrossfadePlanner]): where its sound starts and ends, whether it fades out by itself (over
     * how long), ends hard or neither, and whether it starts softly.
     */
    class Ends(val soundStartMs: Long, val soundEndMs: Long, val ending: CrossfadePlanner.Ending, val softStart: Boolean) {
        companion object {
            /** From a track's waveform (empty when unknown) and silences, for blends of [lengthMs]. */
            fun of(waveform: FloatArray?, durationMs: Long, startMs: Long, endMs: Long, lengthMs: Int): Ends {
                val w = waveform ?: FloatArray(0)
                return Ends(
                    startMs, endMs,
                    CrossfadePlanner.ending(w, durationMs, endMs, lengthMs),
                    CrossfadePlanner.softStart(w, durationMs, startMs, lengthMs),
                )
            }
        }
    }

    /**
     * Writes [tracks] (track i is track i of [classification]) for a device whose music folder is
     * [base] (e.g. "/<microSD1>/Music"). Returns the tracks written.
     */
    fun write(tracks: List<Track>, classification: Classification, base: String, spacing: Int, edgeSeconds: Int, file: File): Int {
        require(tracks.size == classification.moods.size)
        val k = classification
        val strings = Strings()
        val artists = Dict(strings)
        val genres = Dict(strings)
        val moods = Dict(strings).apply { Mood.entries.forEach { add(it.name.lowercase()) } }
        val families = Dict(strings).apply { k.familyNames.indices.forEach { add(familyName(k.familyNames, it)) } }
        val root = base.trim().trimEnd('/', '\\').replace('\\', '/')

        val records = ByteBuffer.allocate(tracks.size * RECORD).order(ByteOrder.LITTLE_ENDIAN)
        val edges = ByteBuffer.allocate(tracks.size * 2 * EDGE_VALUES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var edgeBlocks = 0
        for ((i, t) in tracks.withIndex()) {
            val full = if (root.isEmpty()) "/" + t.path else "$root/${t.path}"
            val start = records.position()
            records.putInt(strings.add(t.title.ifBlank { File(t.path).nameWithoutExtension }))
            records.putInt(strings.add(t.path))
            records.putInt(strings.add(t.album))
            records.putInt(hash(full))
            records.putInt(hash(tail(full)))
            val hasEdges = t.head != null || t.tail != null
            records.putInt(if (hasEdges) edgeBlocks else NONE32)
            records.putShort(t.artist.takeIf { it.isNotBlank() }?.let(artists::add)?.toShort() ?: NONE16.toShort())
            records.putShort(t.genre.takeIf { it.isNotBlank() }?.let(genres::add)?.toShort() ?: NONE16.toShort())
            records.putShort((t.durationMs / 1000).coerceIn(0, 65535).toShort())
            records.putShort(t.year.coerceIn(0, 65535).toShort())
            records.putShort(bpm100(t).toShort())
            records.putShort(percentile(k.energy[i]))
            records.putShort(percentile(k.positivity[i]))
            records.putShort(percentile(k.voice[i]))
            records.put(k.moods[i].ordinal.toByte())
            records.put(k.families[i].toByte())
            records.put(((if (t.included) 1 else 0) or (if (t.head != null) 2 else 0) or (if (t.tail != null) 4 else 0)).toByte())
            records.put(((k.secondMoods[i]?.ordinal ?: -1) + 1).toByte())
            val v = k.vectors[i]
            for (d in 0 until VECTOR) records.putShort((v.getOrElse(d) { 0f } * 1000).roundToInt().coerceIn(-32767, 32767).toShort())
            putEnds(records, t)
            check(records.position() - start == RECORD)
            if (hasEdges) {
                putEdge(edges, t.head)
                putEdge(edges, t.tail)
                edgeBlocks++
            }
        }
        val dicts = ByteArrayOutputStream()
        for (d in listOf(artists, genres, moods, families)) dicts.write(d.bytes())
        val created = strings.add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()))
        val baseString = strings.add(root)

        val years = tracks.map { it.year }.filter { it > 0 }
        val bpms = tracks.map(::bpm100)
        val dictOff = HEADER
        val recOff = dictOff + dicts.size()
        val edgeOff = recOff + records.position()
        val strOff = edgeOff + edges.position()
        val stringBytes = strings.bytes()
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.put("BPCR2".toByteArray()).put(ByteArray(3))
        header.putInt(tracks.size).putInt(RECORD).putInt(dictOff).putInt(recOff).putInt(edgeOff).putInt(strOff).putInt(stringBytes.size)
        header.putShort(spacing.toShort()).putShort(edgeSeconds.toShort()).putInt(created).putInt(4).putInt(baseString)
        header.putShort(VECTOR.toShort()).putShort((EDGE_VALUES * 2).toShort())
        header.putShort((years.minOrNull() ?: 0).toShort()).putShort((years.maxOrNull() ?: 0).toShort())
        header.putShort((bpms.minOrNull() ?: 0).toShort()).putShort((bpms.maxOrNull() ?: 0).toShort())
        file.absoluteFile.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(header.array())
            out.write(dicts.toByteArray())
            out.write(records.array(), 0, records.position())
            out.write(edges.array(), 0, edges.position())
            out.write(stringBytes)
        }
        return tracks.size
    }

    /** A sound family's name in words, e.g. "fast-bright-vocal" (as the players show it). */
    fun familyName(names: List<FamilyName>, family: Int): String =
        names.getOrNull(family)?.traits?.joinToString("-") { (t, high) -> traitWord(t, high) } ?: "family${family + 1}"

    fun traitWord(t: Trait, high: Boolean): String = when (t) {
        Trait.TEMPO -> if (high) "fast" else "slow"
        Trait.BRIGHTNESS -> if (high) "bright" else "dark"
        Trait.PERCUSSION -> if (high) "percussive" else "smooth"
        Trait.BASS -> if (high) "bassy" else "light"
        Trait.DISTORTION -> if (high) "distorted" else "clean"
        Trait.DYNAMICS -> if (high) "dynamic" else "compressed"
        Trait.VOICE -> if (high) "vocal" else "instrumental"
        Trait.LOUDNESS -> if (high) "loud" else "quiet"
    }

    private fun percentile(p: Float): Short = (p * 10000).roundToInt().coerceIn(0, 10000).toShort()

    private fun bpm100(t: Track): Int = (t.features.tempoBpm * 100).roundToInt().coerceIn(0, 65535)

    private fun putEnds(out: ByteBuffer, t: Track) {
        val e = t.ends
        fun cs(ms: Long) = (ms / 10).coerceIn(0, 65535).toInt().toShort()
        if (e == null) {
            repeat(8) { out.put(0) }
            return
        }
        out.putShort(cs(e.soundStartMs))
        out.putShort(cs(t.durationMs - e.soundEndMs))
        val ending = e.ending
        out.putShort(if (ending is CrossfadePlanner.Ending.Fades) cs(ending.fadeMs) else 0)
        out.put(
            when (ending) {
                is CrossfadePlanner.Ending.Fades -> 2
                CrossfadePlanner.Ending.Hard -> 3
                CrossfadePlanner.Ending.Other -> 1
            }.toByte(),
        )
        out.put((if (e.softStart) 1 else 0).toByte())
    }

    private fun putEdge(out: ByteBuffer, e: EdgeDescriptor?) {
        if (e == null) {
            repeat(EDGE_VALUES) { out.putShort(0) }
            return
        }
        // Pearson's correlation of two chroma vectors = the dot product of them centred and scaled to length 1.
        val mean = e.chroma.average().toFloat()
        val centred = FloatArray(e.chroma.size) { e.chroma[it] - mean }
        val length = sqrt(centred.sumOf { (it * it).toDouble() }).toFloat()
        for (c in centred) out.putShort(if (length > 0) (c / length * 16384).roundToInt().toShort() else 0)
        for (b in e.bands) out.putShort((b * 100).roundToInt().coerceIn(-32767, 32767).toShort())
        out.putShort((e.loudnessDb * 100).roundToInt().coerceIn(-32767, 32767).toShort())
        out.putShort((ln(e.flux + FLUX_FLOOR) * 1000).roundToInt().coerceIn(-32767, 32767).toShort())
        val timbre = e.timbre
        if (timbre == null) {
            repeat(com.sadurni.attacca.chain.Cepstrum.COEFFICIENTS + 2) { out.putShort(0) }
            out.putShort(-1)
            out.putShort(0)
            return
        }
        for (c in timbre) out.putShort((c * 100).roundToInt().coerceIn(-32767, 32767).toShort())
        out.putShort(if (e.tempoBpm > 0f) (ln(e.tempoBpm) / ln(2f) * 1000).roundToInt().toShort() else 0)
        out.putShort((e.pulse.coerceIn(0f, 1f) * 10000).roundToInt().toShort())
        out.putShort(e.key.first.toShort())
        out.putShort((e.key.second.coerceIn(0f, 1f) * 10000).roundToInt().toShort())
    }

    /** FNV-1a over the UTF-8 bytes, ASCII letters in lower case (as the plugin). */
    fun hash(path: String): Int {
        var h = 0x811c9dc5.toInt()
        for (byte in path.toByteArray(Charsets.UTF_8)) {
            var b = byte.toInt() and 0xFF
            if (b in 'A'.code..'Z'.code) b += 32
            h = (h xor b) * 0x01000193
        }
        return h
    }

    /** "Album/track.mp3" of "/Music/Artist/Album/track.mp3" (the plugin's tail_of). */
    fun tail(path: String): String {
        val last = path.lastIndexOf('/')
        if (last < 0) return path
        val before = path.lastIndexOf('/', last - 1)
        return path.substring(before + 1)
    }

    /** Strings section: each string once, NUL-terminated. */
    private class Strings {
        private val out = ByteArrayOutputStream()
        private val offsets = HashMap<String, Int>()
        fun add(s: String): Int = offsets.getOrPut(s) {
            val off = out.size()
            out.write(s.toByteArray(Charsets.UTF_8))
            out.write(0)
            off
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Dict(private val strings: Strings) {
        private val index = LinkedHashMap<String, Int>()
        fun add(s: String): Int = index.getOrPut(s) { index.size }
        fun bytes(): ByteArray {
            val b = ByteBuffer.allocate(4 + index.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(index.size)
            for (s in index.keys) b.putInt(strings.add(s))
            return b.array()
        }
    }
}
