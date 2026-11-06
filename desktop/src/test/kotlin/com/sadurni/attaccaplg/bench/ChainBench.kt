package com.sadurni.attaccaplg.bench

import com.sadurni.attacca.chain.ChainEngine
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.EdgeFeatures
import com.sadurni.attacca.chain.EdgeProfile
import com.sadurni.attacca.chain.LinkEdge
import com.sadurni.attacca.chain.LinkModel
import com.sadurni.attaccaplg.audio.Decoder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/**
 * Development: how well chaining works, measured on real music.
 *
 * Every song is cut in three parts at a third and two thirds of its sound. A part's true
 * successor is the next part of the same song, which continues it perfectly, so:
 * - recognition: for each cut, where the true next part ranks among all part starts by the
 *   link score (top 1, top 5, mean reciprocal rank) — tests the score;
 * - order: chaining all parts shuffled, how many true links come out next to each other — tests
 *   the score and the ordering together;
 * - links between whole songs: chaining the songs as they are, the mean score of the links, of
 *   the worst tenth and the worst one — tests the ordering (only comparable within one score).
 *
 * Each scorer is tried with a few compared lengths (as a crossfade of that length would).
 * Args: music folder, [max songs = all], [report file].
 * Run with `gradlew chainBench -PbenchArgs="folder||report"`.
 */
fun main(args: Array<String>) {
    val folder = File(args.getOrNull(0) ?: error("music folder?"))
    val limit = args.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
    val files = folder.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("mp3", "flac", "ogg", "wav") }
        .sortedBy { it.path }
        .take(limit)
        .toList()
    val started = System.currentTimeMillis()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val futures: List<Future<Song?>> = files.map { file -> pool.submit<Song?> { runCatching { describe(file) }.getOrNull() } }
    val songs = futures.mapNotNull { it.get() }
    pool.shutdown()
    val report = StringBuilder()
    fun line(text: String) {
        println(text)
        report.appendLine(text)
    }
    line("Chaining bench · ${songs.size} of ${files.size} songs · ${(System.currentTimeMillis() - started) / 1000} s to describe")
    // Learnt on the even songs, tested on the odd ones (never on what it learnt from).
    val train = songs.filterIndexed { i, _ -> i % 2 == 0 }
    val test = songs.filterIndexed { i, _ -> i % 2 == 1 }
    for (seconds in listOf(10)) {
        val model = learn(train.map { it.edges(seconds) })
        line("— $seconds s · learnt weights: " + LinkModel.NAMES.indices.joinToString(" ") { "%s %.3f".format(LinkModel.NAMES[it], model.weights[it]) } + " · scales %.2f %.2f".format(model.timbreScale, model.textureScale))
        val edged = test.map { it.edges(seconds) }
        // Fixed blends: harmony, key, bands, timbre, texture, level, meeting, activity, tempo.
        fun fixed(vararg w: Float) = LinkModel(w, model.timbreScale, model.textureScale)
        val old = fixed(0.4f, 0f, 0.35f, 0f, 0f, 0.15f, 0f, 0.1f, 0f)
        val plus = fixed(0.3f, 0f, 0.25f, 0.15f, 0f, 0.1f, 0.1f, 0.1f, 0f)
        val musical = fixed(0.25f, 0.05f, 0.2f, 0.15f, 0f, 0.1f, 0.1f, 0.1f, 0.05f)
        val edgeMeeting = LinkModel(floatArrayOf(0.04f, 0.05f, 0.15f, 0.28f, 0f, 0f, 0.06f, 0.11f, 0.31f).let { w -> FloatArray(w.size) { w[it] / w.sum() } }, 1.11f, 0.28f)
        val edgeOnly = LinkModel(floatArrayOf(0.04f, 0.05f, 0.16f, 0.29f, 0f, 0f, 0f, 0.12f, 0.33f).let { w -> FloatArray(w.size) { w[it] / w.sum() } }, 1.11f, 0.28f)
        val scorers: List<Pair<String, Scorer>> = listOf(
            "old score" to { a, b -> EdgeFeatures.similarity(a.descriptor, b.descriptor) },
            "learnt model" to { a, b -> model.score(a, b) },
            "old + timbre + meeting" to { a, b -> plus.score(a, b) },
            "musical (+ key, tempo)" to { a, b -> musical.score(a, b) },
            "edge-only learnt (no level, meeting)" to { a, b -> edgeOnly.score(a, b) },
            "edge-only + meeting vs edge level" to { a, b -> edgeMeeting.score(local(a), local(b)) },
        )
        for ((name, scorer) in scorers) {
            line("  $name (test half, ${test.size} songs)")
            recognition(edged, scorer, ::line)
            order(edged, scorer, ::line)
            links(test, edged, scorer, ::line)
        }
    }
    args.getOrNull(2)?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report.toString()) }
}

private typealias Scorer = (LinkEdge, LinkEdge) -> Float

/** A song: its artist (the folder above its album), body level and its three parts. */
private class Song(val artist: String, val bodyDb: Float, val parts: List<Part>) {
    fun edges(seconds: Int): List<PartEdges> = parts.map { p ->
        PartEdges(
            head = p.head.edge(p.head.startMs, p.head.startMs + seconds * 1000L, first = true, bodyDb),
            tail = p.tail.edge(p.tail.endMs - seconds * 1000L, p.tail.endMs, first = false, bodyDb),
        )
    }
}

/** A part's first and last [EdgeProfile.PROFILE_SECONDS] of sound. */
private class Part(val head: EdgeProfile, val tail: EdgeProfile)

private class PartEdges(val head: LinkEdge, val tail: LinkEdge)

private fun EdgeProfile.edge(fromMs: Long, toMs: Long, first: Boolean, bodyDb: Float): LinkEdge {
    // A quiet stretch (a long fade) has no frames to compare: the whole profile then.
    val d: EdgeDescriptor = window(fromMs, toMs) ?: window(startMs, endMs)!!
    val meeting = if (first) blockDb(((fromMs - startMs) / blockMs).toInt()) else blockDb(((toMs - startMs) / blockMs).toInt() - 1)
    val (timbre, spread) = timbre(fromMs, toMs) ?: timbre(startMs, endMs)!!
    return LinkEdge(d, tempoBpm, pulse, d.loudnessDb - bodyDb, (meeting ?: d.loudnessDb) - bodyDb, timbre, spread)
}

private const val SILENCE = 0.001f
private const val MIN_SECONDS = 90

private fun describe(file: File): Song? {
    var rate = 0
    var channelCount = 1
    var mono = FloatArray(1 shl 22)
    var count = 0
    Decoder.decode(file, { format ->
        rate = format.sampleRate
        channelCount = format.channels
        count = 0
    }) { samples, frames ->
        val channels = channelCount
        if (mono.size < count + frames) mono = mono.copyOf(maxOf(count + frames, mono.size * 2))
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            mono[count + f] = sum / channels
        }
        count += frames
    } ?: return null
    val first = (0 until count).firstOrNull { abs(mono[it]) > SILENCE } ?: return null
    val last = (count - 1 downTo first).first { abs(mono[it]) > SILENCE } + 1
    if (last - first < MIN_SECONDS * rate) return null
    var power = 0.0
    for (i in first until last) power += mono[i].toDouble() * mono[i]
    val bodyDb = (10 * log10(power / (last - first) + 1e-20)).toFloat()
    val profile = EdgeProfile.PROFILE_SECONDS * rate
    fun ms(index: Int) = index * 1000L / rate
    val cuts = listOf(first, first + (last - first) / 3, first + 2 * (last - first) / 3, last)
    val parts = (0 until 3).map { i ->
        val from = cuts[i]
        val to = cuts[i + 1]
        // The tail's blocks end exactly at the part's end.
        val tailFrom = to - profile
        Part(
            EdgeProfile.measure(mono, from, minOf(to, from + profile), rate, ms(from)) ?: return null,
            EdgeProfile.measure(mono, tailFrom, to, rate, ms(tailFrom)) ?: return null,
        )
    }
    return Song(file.parentFile?.parentFile?.name.orEmpty(), bodyDb, parts)
}

/** For each cut, the rank of the true next part's start among every part's start but its own. */
private fun recognition(songs: List<List<PartEdges>>, score: Scorer, line: (String) -> Unit) {
    val parts = songs.flatten()
    var top1 = 0
    var top5 = 0
    var reciprocal = 0.0
    var cuts = 0
    for (song in songs) {
        for (i in 0 until song.size - 1) {
            val from = song[i]
            val truth = score(from.tail, song[i + 1].head)
            // Against other songs only: another part of the same song is no bad link.
            val better = parts.count { it !in song && score(from.tail, it.head) > truth }
            if (better == 0) top1++
            if (better < 5) top5++
            reciprocal += 1.0 / (better + 1)
            cuts++
        }
    }
    line("  recognition vs other songs: top 1 %.1f %% · top 5 %.1f %% · MRR %.3f".format(100.0 * top1 / cuts, 100.0 * top5 / cuts, reciprocal / cuts))
}

/** Chains with [score] by building a matrix the engine reads through dummy tracks. */
private fun chain(edges: List<PartEdges>, artists: List<String>, first: Int, spacing: Int, score: Scorer): List<Int> {
    val n = edges.size
    val matrix = Array(n) { a -> FloatArray(n) { b -> if (a == b) 0f else score(edges[a].tail, edges[b].head) } }
    return ChainEngine.order(n, first, spacing, { a, b -> matrix[a][b] }, artists)!!
}

/** Every part shuffled and chained (no artist rule): share of true links kept, from a few starts. */
private fun order(songs: List<List<PartEdges>>, score: Scorer, line: (String) -> Unit) {
    val random = Random(11)
    val total = songs.sumOf { it.size - 1 }
    val kept = (0 until 1).map { run ->
        val items = songs.flatMapIndexed { s, song -> song.mapIndexed { p, part -> Triple(s, p, part) } }.shuffled(random)
        val firstIndex = items.indexOfFirst { it.second == 0 && it.first == run % songs.size }.coerceAtLeast(0)
        val result = chain(items.map { it.third }, items.map { "" }, firstIndex, 0, score)
        result.zipWithNext().count { (a, b) -> items[a].first == items[b].first && items[a].second + 1 == items[b].second }
    }
    line("  order: true links kept %.1f %%".format(100.0 * kept.average() / total))
}

/** Whole songs chained as the app does (artist rule 4): link scores, from a few starts. */
private fun links(songs: List<Song>, edged: List<List<PartEdges>>, score: Scorer, line: (String) -> Unit) {
    val whole = edged.map { PartEdges(it.first().head, it.last().tail) }
    val scores = (0 until 2).flatMap { run ->
        val result = chain(whole, songs.map { it.artist }, (run * 37) % whole.size, 4, score)
        result.zipWithNext().map { (a, b) -> score(whole[a].tail, whole[b].head) }
    }.sorted()
    val tenth = scores.take((scores.size / 10).coerceAtLeast(1))
    line("  links: mean %.3f · worst tenth %.3f · worst %.3f".format(scores.average(), tenth.average(), scores.first()))
}

/**
 * Weights of the similarities, learnt from the cuts of [songs] (each a true link) against the
 * starts of other songs (30 per cut, at random): a logistic fit with weights kept positive, then
 * scaled to add up to 1. The timbre scales are the median distances between different songs.
 */
private fun learn(songs: List<List<PartEdges>>): LinkModel {
    val random = Random(5)
    val starts = songs.flatMapIndexed { s, song -> song.map { s to it.head } }
    val pairs = ArrayList<Triple<LinkEdge, LinkEdge, Float>>()
    for ((s, song) in songs.withIndex()) {
        for (i in 0 until song.size - 1) {
            pairs.add(Triple(song[i].tail, song[i + 1].head, 1f))
            repeat(30) {
                var other = starts[random.nextInt(starts.size)]
                while (other.first == s) other = starts[random.nextInt(starts.size)]
                pairs.add(Triple(song[i].tail, other.second, 0f))
            }
        }
    }
    val negatives = pairs.filter { it.third < 0.5f }
    fun median(values: List<Float>) = values.sorted()[values.size / 2]
    val timbreScale = median(negatives.map { LinkModel.distance(it.first.timbreMean, it.second.timbreMean) })
    val textureScale = median(negatives.map { LinkModel.distance(it.first.timbreSpread, it.second.timbreSpread) })
    val x = pairs.map { LinkModel.similarities(it.first, it.second, timbreScale, textureScale) }
    val labels = pairs.map { it.third }
    val k = LinkModel.SIZE
    val positive = labels.count { it > 0.5f }.toFloat()
    val weightOf = { y: Float -> if (y > 0.5f) (labels.size - positive) / positive else 1f }
    val w = FloatArray(k) { 1f }
    var b = 0f
    val rate = 0.5f
    repeat(3000) {
        val gw = FloatArray(k)
        var gb = 0f
        var total = 0f
        for (n in x.indices) {
            var z = b
            for (j in 0 until k) z += w[j] * x[n][j]
            val p = 1f / (1f + kotlin.math.exp(-z))
            val g = (p - labels[n]) * weightOf(labels[n])
            for (j in 0 until k) gw[j] += g * x[n][j]
            gb += g
            total += weightOf(labels[n])
        }
        for (j in 0 until k) w[j] = (w[j] - rate * gw[j] / total).coerceAtLeast(0f)
        b -= rate * gb / total
    }
    val sum = w.sum().coerceAtLeast(1e-6f)
    return LinkModel(FloatArray(k) { w[it] / sum }, timbreScale, textureScale)
}

/** The edge with its meeting level taken against the edge's own level (what an edge alone can tell). */
private fun local(e: LinkEdge): LinkEdge =
    LinkEdge(e.descriptor, e.tempoBpm, e.pulse, 0f, e.meetingDb - e.relativeDb, e.timbreMean, e.timbreSpread)
