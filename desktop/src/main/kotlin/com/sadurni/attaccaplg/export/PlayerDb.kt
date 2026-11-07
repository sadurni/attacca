package com.sadurni.attaccaplg.export

import com.sadurni.attacca.library.Inclusion
import com.sadurni.attacca.library.PlayerDbFormat
import com.sadurni.attaccaplg.generate.Library
import java.io.File

/** The library for players (`.bpcr2`, see [PlayerDbFormat]): the Rockbox plugin and the web generator read it. */
object PlayerDb {
    const val EXTENSION = PlayerDbFormat.EXTENSION

    /** Writes [library] for a device whose music folder is [base] (e.g. "/<microSD1>/Music"). Returns the tracks written. */
    fun write(
        library: Library,
        inclusion: Inclusion?,
        base: String,
        spacing: Int,
        edgeSeconds: Int,
        file: File,
        /** Every inclusion set, by name, for the player to choose from. */
        sets: List<Pair<String, Inclusion>> = emptyList(),
    ): Int {
        val kept = sets.take(com.sadurni.attacca.library.InclusionSet.MAX_FOR_PLAYERS)
        // The measured tracks, in the order of the classification.
        val tracks = library.all.filter { it.features != null }.map { t ->
            PlayerDbFormat.Track(
                path = t.path,
                title = t.tags.title,
                album = t.tags.album,
                artist = t.tags.artist,
                genre = t.tags.genre,
                durationMs = t.durationMs,
                year = t.tags.year ?: 0,
                features = t.features!!,
                head = t.head,
                tail = t.tail,
                included = inclusion?.includes(t.path) ?: true,
                sets = kept.foldIndexed(0) { k, bits, (_, set) -> if (set.includes(t.path)) bits or (1 shl k) else bits },
                // How it starts and ends, for the player's automatic crossfade.
                ends = t.takeIf { it.audioEndMs > it.audioStartMs }?.let {
                    PlayerDbFormat.Ends.of(it.waveform, it.durationMs, it.audioStartMs, it.audioEndMs, PlayerDbFormat.REFERENCE_BLEND_MS)
                },
            )
        }
        return PlayerDbFormat.write(tracks, library.generator.classification, base, spacing, edgeSeconds, file, kept.map { it.first })
    }
}
