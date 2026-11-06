package com.sadurni.attacca.settings

import com.sadurni.attacca.library.UserLists
import org.json.JSONObject
import java.io.File

/** The user's themes: one JSON file each in `Music/Attacca/Themes`. */
object ThemeStore {

    val directory: File get() = File(UserLists.directory, "Themes")

    /** Built-in themes first, then the user's by name. Blocking. */
    fun all(): List<ThemeSpec> = ThemeSpec.BUILT_INS + userThemes()

    private fun userThemes(): List<ThemeSpec> =
        directory.listFiles { f -> f.isFile && f.extension.equals("json", true) }
            ?.mapNotNull { file -> runCatching { ThemeSpec.fromJson(file.nameWithoutExtension, JSONObject(file.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun find(id: String?): ThemeSpec? {
        if (id == null) return null
        ThemeSpec.BUILT_INS.firstOrNull { it.id == id }?.let { return it }
        val file = File(directory, "$id.json")
        return runCatching { ThemeSpec.fromJson(id, JSONObject(file.readText())) }.getOrNull()
    }

    /**
     * The themes that come with Attacca (assets `themes/`), read-only: ids `gallery:<name>`, their
     * pictures unpacked in [galleryRoot] (again after each new build, [stamp]). They are used through
     * a copy among the user's themes ([adopt]), so the user's copies can be edited or deleted and an
     * update of the app never touches them. Blocking.
     */
    fun gallery(context: android.content.Context, stamp: String): List<ThemeSpec> {
        val root = File(context.cacheDir, "theme-gallery")
        galleryRoot = root
        val assets = context.assets
        val names = assets.list(SAMPLES)?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") }.orEmpty()
        val marker = File(root, ".stamp")
        if (!marker.isFile || marker.readText() != stamp) {
            root.deleteRecursively()
            root.mkdirs()
            for (name in names) {
                assets.open("$SAMPLES/$name.json").use { input -> File(root, "$name.json").outputStream().use { input.copyTo(it) } }
                val images = assets.list("$SAMPLES/$name").orEmpty()
                if (images.isNotEmpty()) File(root, name).mkdirs()
                for (image in images) {
                    assets.open("$SAMPLES/$name/$image").use { input -> File(File(root, name), image).outputStream().use { input.copyTo(it) } }
                }
            }
            marker.writeText(stamp)
        }
        return names.mapNotNull { name ->
            runCatching { ThemeSpec.fromJson(GALLERY + name, JSONObject(File(root, "$name.json").readText())) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    /** Where [gallery] unpacked the themes that come with the app. */
    @Volatile
    private var galleryRoot: File? = null

    /**
     * A copy of the gallery theme [spec] among the user's themes, called [name] (in the app's
     * language; "Name 2"… when taken), saved. It remembers where it came from and how it was, so
     * while untouched it keeps showing the gallery name in whatever language the app is in.
     */
    fun adopt(spec: ThemeSpec, name: String): ThemeSpec {
        val id = freeId(name)
        imagesDir(spec.id).takeIf { it.isDirectory }?.copyRecursively(imagesDir(id), overwrite = true)
        val copy = spec.copy(id = id, name = id, origin = spec.id.removePrefix(GALLERY), originPrint = null)
        // The print of the copy as it will be read back from its file.
        val print = ThemeSpec.fromJson(id, copy.toJson()).contentPrint()
        return save(copy.copy(originPrint = print))
    }

    /**
     * Once, when the themes that come with the app became a gallery: the copies installed earlier
     * and never edited go away (they are in the gallery), except the one in use [inUse], which is
     * brought up to date. Edited copies stay as they are. Returns whether anything changed. Blocking.
     */
    fun tidySamples(context: android.content.Context, inUse: String?): Boolean {
        val prefs = context.getSharedPreferences("themes", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("gallery", false)) return false
        val assets = context.assets
        val samples = assets.list(SAMPLES)?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") }.orEmpty()
        var changed = false
        runCatching {
            for (id in samples) {
                val file = File(directory, "$id.json")
                if (!file.isFile) continue
                val shipped = assets.open("$SAMPLES/$id.json").use { it.readBytes() }
                val hash = sha256(file.readBytes())
                if (hash != sha256(shipped) && hash !in OUTDATED_SAMPLES) continue
                changed = true
                if (id == inUse) {
                    if (hash != sha256(shipped)) {
                        file.writeBytes(shipped)
                        val images = assets.list("$SAMPLES/$id").orEmpty()
                        if (images.isNotEmpty()) imagesDir(id).mkdirs()
                        for (image in images) {
                            assets.open("$SAMPLES/$id/$image").use { input -> File(imagesDir(id), image).outputStream().use { input.copyTo(it) } }
                        }
                        // Pictures of the earlier version it no longer uses.
                        val used = find(id)?.imageFiles.orEmpty()
                        imagesDir(id).listFiles()?.filter { it.isFile && it.name !in used }?.forEach { it.delete() }
                    }
                } else {
                    file.delete()
                    imagesDir(id).deleteRecursively()
                }
            }
        }.onFailure { return false }
        prefs.edit().putBoolean("gallery", true).apply()
        return changed
    }

    private const val SAMPLES = "themes"
    const val GALLERY = ThemeSpec.GALLERY

    /** SHA-256 of sample theme files as earlier versions shipped them (copies still like that were never edited). */
    private val OUTDATED_SAMPLES = setOf(
        // Aurora before its landscape layout moved the information box under the cover.
        "8476e4b6612937432cf6d51e318467b422d3ffe830ee5f87225998a36fc14dfe",
        // Estelar, Autumn and Minicadena before the navigator got its usual icon back (and Minicadena its menu key).
        "18e3e444873b50ad10998137894e39291846bd1fcb452326fc4e5d45a661c7fd",
        "a6b5decd48c5ea29a571c349187ba9d44c8ab0a549b979574d04a02781c06c79",
        "c268d501870db84b0a07f914a0b5e261b17bd1698669a9d94f008429e02c33e7",
        // Marítimo before the cliff with the lighthouse (night and day).
        "7677062d4d23975c97b79360b1466886b6359aedcefccbd253e20dde21b3f204",
        // Rayos before the cover went on top in portrait.
        "cdc4612611d3df1b4b95e182907fa6f98b3528163221a07ef44f12d2617544d7",
        // Every sample before the quick list button.
        "94709dc2bf3698189852170651b7ba06a02d14a63c340771c0f8e6982543f9fd",
        "5b276554915b980fe1da83ee5c0e099bef38b063433715e2550d89aa1a5fa1e7",
        "78f9da919977b38b720dfc366fcad4eef2a1312a686038c00cd8252303d444ee",
        "8da0850804332ed38b849abd3da6f91cf34f41050016194037f78be995715e05",
        "c8f8d67d1345b616d5b0dd3b9d80fb43641663e3b5fed7da1ae46fb71f60c884",
        "c048f894dee971cd153b2ceecab25fcce07d3d718fbff2e91e8f597abecbd99f",
        "3273d20ee2c9448ad501e719a22d9e859013a271d992d6f1771acefe9f0e21f6",
        "fbeb0c94a0e3bf2b0426a6c487e034de7dc68e67f1b84b49adcc230d4aa7d8af",
        "a6a192d13eb0b34d2ec55aa176503351826f0613e683c9dc5b05baf3cf0c762c",
        "46f30bc961593f280024a33e5641b8f78c6b72ae021131be0c4719828fb5bf0b",
    )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * [spec] as a zip: `<name>.json` and its pictures and fonts in `<name>/`, as in the themes
     * folder. Any theme, also the built-in and gallery ones. Blocking.
     */
    fun exportZip(spec: ThemeSpec, out: java.io.OutputStream) {
        val name = safeName(spec.name)
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("$name.json"))
            zip.write(spec.copy(id = name).toJson().toString(2).toByteArray())
            zip.closeEntry()
            if (!spec.builtIn) {
                for (file in spec.imageFiles) {
                    val source = File(imagesDir(spec.id), file).takeIf { it.isFile } ?: continue
                    zip.putNextEntry(java.util.zip.ZipEntry("$name/$file"))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * A theme from a zip made by [exportZip] (or by hand: one JSON file, its pictures next to it or
     * in a folder) or a plain theme JSON, saved among the user's themes under a free name. Null when
     * there is no theme in it. Blocking.
     */
    fun importZip(input: java.io.InputStream): ThemeSpec? {
        val bytes = input.readBytes()
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) {
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
            val spec = ThemeSpec.fromJson("import", json)
            val id = freeId(spec.name)
            return save(spec.copy(id = id, name = if (id == safeName(spec.name)) spec.name else id, backgrounds = emptyMap(), icons = emptyMap(), textFont = null, titleFont = null))
        }
        var json: JSONObject? = null
        val files = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                // Plain names only; nothing outside the theme's folder.
                if (name.isEmpty() || name.startsWith(".") || entry.name.contains("..")) continue
                val data = zip.readBytes()
                if (data.size > MAX_IMPORTED_FILE) continue
                if (name.endsWith(".json", ignoreCase = true) && json == null) {
                    json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
                } else {
                    files[name] = data
                }
            }
        }
        val parsed = ThemeSpec.fromJson("import", json ?: return null)
        val id = freeId(parsed.name)
        // "Name 2" when the name is taken, so the two can be told apart.
        val spec = parsed.copy(id = id, name = if (id == safeName(parsed.name)) parsed.name else id)
        imagesDir(id).mkdirs()
        for (file in spec.imageFiles) files[file]?.let { File(imagesDir(id), file).writeBytes(it) }
        return save(spec)
    }

    private const val MAX_IMPORTED_FILE = 16 * 1024 * 1024

    private fun safeName(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Theme" }

    /** The folder of a theme's images: next to its file, with its name (gallery themes: where they were unpacked). */
    fun imagesDir(id: String): File =
        if (id.startsWith(GALLERY)) File(galleryRoot ?: File(directory, ".gallery"), id.removePrefix(GALLERY)) else File(directory, id)

    /**
     * Saves [spec] (its id is its file name); returns it with the id it got. Images of its folder
     * it no longer uses go away. Blocking.
     */
    fun save(spec: ThemeSpec): ThemeSpec {
        directory.mkdirs()
        val id = if (spec.builtIn || spec.original) freeId(spec.name) else spec.id
        val saved = spec.copy(id = id)
        File(directory, "$id.json").writeText(saved.toJson().toString(2))
        val used = saved.imageFiles
        imagesDir(id).listFiles()?.filter { it.isFile && it.name !in used }?.forEach { it.delete() }
        return saved
    }

    /** A copy of [spec] under a new name, with its images, saved. */
    fun duplicate(spec: ThemeSpec, name: String): ThemeSpec {
        val id = freeId(name)
        if (!spec.builtIn) imagesDir(spec.id).takeIf { it.isDirectory }?.copyRecursively(imagesDir(id), overwrite = true)
        return save(spec.copy(id = id, name = name))
    }

    fun delete(spec: ThemeSpec) {
        if (spec.builtIn || spec.original) return
        File(directory, "${spec.id}.json").delete()
        imagesDir(spec.id).deleteRecursively()
    }

    /** A file name for a theme called [name] not used yet. */
    fun freeId(name: String): String {
        val base = safeName(name)
        var id = base
        var n = 2
        while (File(directory, "$id.json").exists() || File(directory, id).exists()) id = "$base ${n++}"
        return id
    }
}
