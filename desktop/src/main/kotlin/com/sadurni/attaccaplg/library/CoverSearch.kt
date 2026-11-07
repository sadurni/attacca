package com.sadurni.attaccaplg.library

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Album covers found online, in an open music database and the cover archive that goes with it:
 * the releases matching an album and artist, each with its front cover. What is found is kept in
 * a cache of the program's own ([cached]); writing it into the album's folder as cover.jpg is a
 * separate step the user decides on ([saveToFolder]).
 */
object CoverSearch {

    /** A release that may be the album, with its front cover's address (small and large). */
    class Candidate(val title: String, val artist: String, val year: String?, val country: String?, val thumbUrl: String, val imageUrl: String)

    private const val SEARCH = "https://musicbrainz.org/ws/2/release/"
    private const val ARCHIVE = "https://coverartarchive.org/release/"

    /** The database asks every program to say who it is, with a way to reach its makers. */
    private const val AGENT = "AttaccaDesktop/1.1 ( https://github.com/sadurni/attacca )"

    /** At most one search a second, as the database asks. */
    private const val GAP_MS = 1_100L
    private var lastAsk = 0L

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    }

    /** Where found covers are kept: one per album folder. */
    val CACHE: File get() = File(LibraryDb.HOME, "covers")

    /**
     * Releases that may be [album] by [artist], those with a front cover in the archive, best
     * matches first; at most [limit]. Throws when the network cannot be reached.
     */
    fun search(artist: String, album: String, limit: Int = 8): List<Candidate> {
        val terms = buildList {
            if (album.isNotBlank()) add("release:\"${escape(album)}\"")
            if (artist.isNotBlank()) add("artist:\"${escape(artist)}\"")
        }
        if (terms.isEmpty()) return emptyList()
        val query = URLEncoder.encode(terms.joinToString(" AND "), Charsets.UTF_8)
        val body = get("$SEARCH?query=$query&fmt=json&limit=25")?.toString(Charsets.UTF_8) ?: return emptyList()
        val releases = JSONObject(body).optJSONArray("releases") ?: return emptyList()
        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            // Only releases the archive has a front cover of.
            val art = r.optJSONObject("cover-art-archive")
            if (art != null && !art.optBoolean("front", false)) continue
            val id = r.optString("id")
            if (id.isEmpty()) continue
            val title = r.optString("title")
            val credit = r.optJSONArray("artist-credit")?.let { a -> (0 until a.length()).joinToString("") { k -> a.getJSONObject(k).let { it.optString("name") + it.optString("joinphrase") } } }.orEmpty()
            val year = r.optString("date").take(4).ifEmpty { null }
            // The same edition again (same title, artist and year) adds nothing to choose from.
            if (!seen.add("$title|$credit|$year|${r.optString("country")}")) continue
            result += Candidate(title, credit, year, r.optString("country").ifEmpty { null }, "$ARCHIVE$id/front-250", "$ARCHIVE$id/front-1200")
            if (result.size >= limit) break
        }
        return result
    }

    /** The picture at [url], or null. */
    fun download(url: String): ByteArray? = get(url, polite = url.startsWith(SEARCH))

    private fun get(url: String, polite: Boolean = true): ByteArray? {
        if (polite) synchronized(this) {
            val wait = lastAsk + GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastAsk = System.currentTimeMillis()
        }
        val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", AGENT).header("Accept", "application/json, image/*").timeout(Duration.ofSeconds(30)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return response.body().takeIf { response.statusCode() in 200..299 && it.isNotEmpty() }
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The cover found online for the album in [folder], if one was kept. */
    fun cached(folder: File): File? = cacheFile(folder).takeIf { it.isFile }

    /** Keeps [bytes] as the cover found for the album in [folder]. */
    fun keep(folder: File, bytes: ByteArray) {
        CACHE.mkdirs()
        cacheFile(folder).writeBytes(bytes)
    }

    /** Forgets the cover found for [folder]. */
    fun forget(folder: File) {
        cacheFile(folder).delete()
    }

    /**
     * Writes [bytes] as cover.jpg in the album's [folder] (replacing one there). Returns the file,
     * or null when the folder cannot be written.
     */
    fun saveToFolder(folder: File, bytes: ByteArray): File? = runCatching {
        File(folder, "cover.jpg").also { it.writeBytes(bytes) }
    }.getOrNull()

    private fun cacheFile(folder: File): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(folder.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(CACHE, "$digest.jpg")
    }
}
