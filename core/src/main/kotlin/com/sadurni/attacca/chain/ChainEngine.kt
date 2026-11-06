package com.sadurni.attacca.chain

/** A track as chaining sees it: how it starts, how it ends and who plays it. */
class ChainTrack(val head: EdgeDescriptor?, val tail: EdgeDescriptor?, val artist: String)

/**
 * Orders tracks so the end of each one sounds like the start of the next: from the first track,
 * always the closest remaining one (greedy nearest neighbour), avoiding an artist heard among the
 * last few tracks while another choice exists; then the order is improved by moving short runs of
 * tracks elsewhere while that makes the links better as a whole (weak links count more), without
 * breaking the artist rule more than before. Tracks that could not be analysed go last, in their
 * current order.
 */
object ChainEngine {

    /** Score of a link from [a] into [b]; tracks without analysis score nothing. */
    fun score(a: ChainTrack, b: ChainTrack): Float {
        val end = a.tail ?: return 0f
        val start = b.head ?: return 0f
        return EdgeFeatures.similarity(end, start)
    }

    /**
     * New order (indices into [tracks]) beginning with [first]. [artistSpacing] is how many
     * following tracks avoid repeating an artist (0 = no rule). [isCancelled] is checked now and
     * then; a cancelled chain returns null.
     */
    fun order(
        tracks: List<ChainTrack>,
        first: Int,
        artistSpacing: Int,
        isCancelled: () -> Boolean = { false },
        improve: Boolean = true,
    ): List<Int>? = order(
        tracks.size, first, artistSpacing,
        score = { a, b -> score(tracks[a], tracks[b]) },
        artists = tracks.map { it.artist },
        analysed = { tracks[it].head != null },
        isCancelled = isCancelled,
        improve = improve,
    )

    /**
     * New order of [count] tracks (indices) beginning with [first], by [score] of the link from
     * one into another; [artists] of each, [analysed] tells those that can be placed (the rest go
     * last, as they are). See [order] above for the rest.
     */
    fun order(
        count: Int,
        first: Int,
        artistSpacing: Int,
        score: (Int, Int) -> Float,
        artists: List<String>,
        analysed: (Int) -> Boolean = { true },
        isCancelled: () -> Boolean = { false },
        improve: Boolean = true,
    ): List<Int>? {
        if (count == 0) return emptyList()
        require(first in 0 until count)
        val keys = artists.map(::artistKey)
        val result = ArrayList<Int>(count)
        result.add(first)
        val placed = (0 until count).filter { it != first && analysed(it) }.toMutableList()
        val unplaced = (0 until count).filter { it != first && !analysed(it) }
        val recentArtists = ArrayDeque<String>()
        fun remember(artist: String) {
            if (artistSpacing <= 0) return
            recentArtists.addLast(artist)
            while (recentArtists.size > artistSpacing) recentArtists.removeFirst()
        }
        remember(keys[first])

        var current = first
        var steps = 0
        while (placed.isNotEmpty()) {
            if (++steps % 32 == 0 && isCancelled()) return null
            var bestAny = -1
            var bestAnyScore = Float.NEGATIVE_INFINITY
            var bestFree = -1
            var bestFreeScore = Float.NEGATIVE_INFINITY
            for (i in placed.indices) {
                val s = score(current, placed[i])
                if (s > bestAnyScore) {
                    bestAnyScore = s
                    bestAny = i
                }
                if (s > bestFreeScore && keys[placed[i]].let { it.isEmpty() || it !in recentArtists }) {
                    bestFreeScore = s
                    bestFree = i
                }
            }
            val next = placed.removeAt(if (bestFree >= 0) bestFree else bestAny)
            result.add(next)
            remember(keys[next])
            current = next
        }
        // Near the end the greedy pass may have only tracks of one artist left and put them
        // together; they are moved to where they keep the spacing, if there is such a place.
        repairSpacing(result, score, keys, artistSpacing)
        if (improve) improveOrder(result, score, keys, artistSpacing, isCancelled) ?: return null
        result.addAll(unplaced)
        return result
    }

    /**
     * Moves each track of [path] that comes too soon after another of its artist to the place
     * where it keeps the spacing and loses least in links (its first track stays first). A track
     * with no such place stays where it is.
     */
    private fun repairSpacing(path: MutableList<Int>, score: (Int, Int) -> Float, keys: List<String>, artistSpacing: Int) {
        if (artistSpacing <= 0 || path.size < 3) return
        fun tooSoon(at: Int): Boolean {
            val a = keys[path[at]]
            return a.isNotEmpty() && (maxOf(0, at - artistSpacing) until at).any { keys[path[it]] == a }
        }
        // Whether [track] fits between positions at-1 and at (before the track now at [at]).
        fun fits(track: Int, at: Int): Boolean {
            val a = keys[track]
            if (a.isEmpty()) return true
            for (k in maxOf(0, at - artistSpacing) until at) if (keys[path[k]] == a) return false
            for (k in at until minOf(path.size, at + artistSpacing)) if (keys[path[k]] == a) return false
            return true
        }
        fun link(a: Int?, b: Int?): Float = if (a == null || b == null) 0f else worth(score(a, b))
        var i = 1
        var moves = 0
        while (i < path.size) {
            if (!tooSoon(i) || moves > 2 * path.size) {
                i++
                continue
            }
            val track = path.removeAt(i)
            var bestAt = -1
            var best = Float.NEGATIVE_INFINITY
            for (at in 1..path.size) {
                if (!fits(track, at)) continue
                val before = path[at - 1]
                val after = path.getOrNull(at)
                val gain = link(before, track) + link(track, after) - link(before, after)
                if (gain > best) {
                    best = gain
                    bestAt = at
                }
            }
            if (bestAt < 0) {
                path.add(i, track)
                i++
            } else {
                path.add(bestAt, track)
                moves++
                // What follows moved up one: look again from here.
                if (bestAt > i) continue
                i++
            }
        }
    }

    /**
     * How much a link of score [s] is worth: its score, and a weak one (below [WEAK]) loses more,
     * so a better whole never hides a jarring transition.
     */
    private fun worth(s: Float): Float = if (s >= WEAK) s else s - WEAK_PENALTY * (WEAK - s)

    /**
     * Improves [path] in place (its first track stays first): runs of 1 to [MAX_RUN] tracks are
     * moved between two others when the links gain worth and the artist rule is not broken more.
     * Long paths are improved in overlapping windows. Null when cancelled.
     */
    private fun improveOrder(path: MutableList<Int>, score: (Int, Int) -> Float, keys: List<String>, artistSpacing: Int, isCancelled: () -> Boolean): Unit? {
        if (path.size < 4) return Unit
        var from = 0
        while (true) {
            val to = minOf(path.size, from + WINDOW)
            improveWindow(path, from, to, score, keys, artistSpacing, isCancelled) ?: return null
            if (to == path.size) return Unit
            from += WINDOW / 2
        }
    }

    private fun improveWindow(path: MutableList<Int>, from: Int, to: Int, score: (Int, Int) -> Float, keys: List<String>, artistSpacing: Int, isCancelled: () -> Boolean): Unit? {
        val ids = path.subList(from, to).toIntArray()
        val n = ids.size
        // Worth of every link between tracks of the window, local indices.
        val worth = Array(n) { a -> FloatArray(n) { b -> if (a == b) 0f else worth(score(ids[a], ids[b])) } }
        val artists = ids.map { keys[it] }
        // The window's ends stay where they are: its first track (the chain's first, or the link
        // from before the window) and, unless it reaches the end, its last.
        val last = if (to == path.size) n else n - 1
        val order = (0 until n).toMutableList()
        fun broken(): Int = if (artistSpacing <= 0) 0 else order.indices.count { i ->
            val a = artists[order[i]]
            a.isNotEmpty() && (maxOf(0, i - artistSpacing) until i).any { artists[order[it]] == a }
        }
        var rules = broken()
        for (pass in 0 until MAX_PASSES) {
            if (isCancelled()) return null
            var improved = false
            for (run in 1..MAX_RUN) {
                var i = 1
                while (i + run <= last) {
                    val s0 = order[i]
                    val s1 = order[i + run - 1]
                    val before = order[i - 1]
                    val after = order.getOrNull(i + run)
                    // Taking the run out joins its neighbours.
                    val removal = -worth[before][s0] + (if (after != null) worth[before][after] - worth[s1][after] else 0f)
                    var best = 1e-4f
                    var bestAt = -1
                    for (j in 0 until last) {
                        if (j in i - 1 until i + run) continue
                        val c = order[j]
                        val d = order.getOrNull(j + 1)
                        val gain = removal - (if (d != null) worth[c][d] else 0f) + worth[c][s0] + (if (d != null) worth[s1][d] else 0f)
                        if (gain > best) {
                            best = gain
                            bestAt = j
                        }
                    }
                    if (bestAt >= 0) {
                        val moved = order.subList(i, i + run).toList()
                        val previous = order.toList()
                        repeat(run) { order.removeAt(i) }
                        val at = if (bestAt > i) bestAt - run + 1 else bestAt + 1
                        order.addAll(at, moved)
                        val now = broken()
                        if (now <= rules) {
                            rules = now
                            improved = true
                        } else {
                            order.clear()
                            order.addAll(previous)
                        }
                    }
                    i++
                }
            }
            if (!improved) break
        }
        for (k in 0 until n) path[from + k] = ids[order[k]]
        return Unit
    }

    /** Links scoring less than this are weak: they weigh more when improving an order. */
    private const val WEAK = 0.5f
    private const val WEAK_PENALTY = 1.5f
    private const val MAX_RUN = 3
    private const val MAX_PASSES = 30
    /** Tracks improved at once; longer chains go window by window. */
    private const val WINDOW = 600

    private fun artistKey(artist: String): String = artist.trim().lowercase()
}
