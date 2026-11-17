package com.sadurni.attacca.library

import java.io.File

/**
 * [SameSong] over the app's tracks: the copies of one recording that a list should leave out,
 * keeping the best. The tags propose copies; only those whose sound was compared and found the
 * same ([RecordingMatch], done by [verify] in the background and kept in [store]) are left out.
 * Copies not compared yet both stay.
 */
object SameSongs {

    /** Where the comparisons are kept (set at app start): pair, files' stamps, verdict. */
    @Volatile
    var store: File? = null

    private val verdicts = HashMap<String, Boolean>()
    private var loaded = false

    /** Grows when a comparison found copies, so lists made from the library are made again. */
    @Volatile
    var version = 0
        private set

    /** Of [ids] (files), the copies to leave out; those in [keep] always stay. Blocking. */
    fun others(ids: Collection<String>, keep: Set<String> = emptySet()): Set<String> {
        val tracks = tracksOf(ids)
        if (tracks.size < 2) return emptySet()
        return SameSong.others(tracks, keep, same = { a, b -> verdict(a.id, b.id) == true })
    }

    /**
     * Compares the sound of the copies the tags propose among [ids] that were not compared yet
     * (decoding half a minute of each). True when it found new copies. Blocking, slow: background only.
     */
    fun verify(ids: Collection<String>, isCancelled: () -> Boolean = { false }): Boolean {
        val tracks = tracksOf(ids)
        val levels = HashMap<String, FloatArray?>()
        fun levelsOf(t: SameSong.Track) = levels.getOrPut(t.id) { decode(t) }
        var found = false
        var changed = false
        for (group in SameSong.groups(tracks)) {
            for (i in group.indices) for (j in i + 1 until group.size) {
                if (isCancelled()) return found.also { if (changed) save() }
                val a = group[i]
                val b = group[j]
                if (verdict(a.id, b.id) != null) continue
                val la = levelsOf(a) ?: continue
                val lb = levelsOf(b) ?: continue
                val likeness = maxOf(RecordingMatch.likeness(la, lb), RecordingMatch.likeness(lb, la))
                if (likeness.isNaN()) continue
                val same = likeness >= RecordingMatch.SAME
                synchronized(verdicts) { verdicts[pairKey(a.id, b.id)] = same }
                changed = true
                if (same) found = true
                android.util.Log.d(TAG, "%s · %s: %.2f".format(java.util.Locale.ROOT, File(a.id).name, File(b.id).name, likeness))
            }
        }
        if (changed) save()
        if (found) version++
        return found
    }

    /** The comparisons still valid (both files unchanged since): the two files and the verdict. */
    fun all(): List<Triple<String, String, Boolean>> {
        load()
        val entries = synchronized(verdicts) { verdicts.entries.toList() }
        return entries.mapNotNull { (key, same) ->
            val parts = key.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val (a, b) = parts
            if (stamp(a) != parts[2] || stamp(b) != parts[3]) return@mapNotNull null
            Triple(a, b, same)
        }
    }

    /** Takes comparisons made elsewhere (another phone, the desktop) for these files as they are here. */
    fun take(entries: List<Triple<String, String, Boolean>>) {
        if (entries.isEmpty()) return
        load()
        synchronized(verdicts) { for ((a, b, same) in entries) verdicts[pairKey(a, b)] = same }
        save()
        if (entries.any { it.third }) version++
    }

    private fun tracksOf(ids: Collection<String>): List<SameSong.Track> {
        val files = ids.filter { !it.contains("://") }.distinct()
        if (files.size < 2) return emptyList()
        TrackSort.warm(files)
        val live = LiveTracks.live(files)
        return files.map { id ->
            val t = TrackSort.tags(id)
            SameSong.Track(id, t.title, t.artist, t.album, t.albumArtist, t.durationMs, id in live)
        }
    }

    /** Half a minute and the search around the middle of [t], as levels; null when it cannot be decoded. */
    private fun decode(t: SameSong.Track): FloatArray? {
        val startUs = RecordingMatch.startMs(t.durationMs) * 1000
        val tail = com.sadurni.attacca.analysis.TailDecoder.decode(File(t.id), startUs, RecordingMatch.SPAN_S * 1_000_000L, leadUs = 1_000_000L) ?: return null
        val frames = minOf(tail.frames - tail.clipEndFrame, RecordingMatch.SPAN_S * tail.sampleRate)
        if (frames <= 0) return null
        return RecordingMatch.envelope(tail.samples, tail.clipEndFrame, frames, tail.channels, tail.sampleRate)
    }

    /** The verdict for two files as they are now (a file changed since is compared again); null: not compared. */
    private fun verdict(a: String, b: String): Boolean? {
        load()
        return synchronized(verdicts) { verdicts[pairKey(a, b)] }
    }

    private fun stamp(path: String): String = File(path).let { "${it.length()}:${it.lastModified()}" }

    private fun pairKey(a: String, b: String): String {
        val (x, y) = if (a < b) a to b else b to a
        return "$x\t$y\t${stamp(x)}\t${stamp(y)}"
    }

    private fun load() {
        synchronized(verdicts) {
            if (loaded) return
            loaded = true
            runCatching {
                store?.takeIf { it.isFile }?.forEachLine { line ->
                    val cut = line.lastIndexOf('\t')
                    if (cut > 0) verdicts[line.substring(0, cut)] = line.substring(cut + 1) == "1"
                }
            }
        }
    }

    private fun save() {
        val target = store ?: return
        val text = synchronized(verdicts) { verdicts.entries.joinToString("\n") { "${it.key}\t${if (it.value) "1" else "0"}" } }
        runCatching {
            val tmp = File(target.path + ".tmp")
            tmp.writeText(text + "\n")
            tmp.renameTo(target)
        }
    }

    private const val TAG = "SameSongs"
}
