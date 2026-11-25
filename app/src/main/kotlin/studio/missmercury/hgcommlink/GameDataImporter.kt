package studio.missmercury.hgcommlink

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import chummer.core.data.GameDataFingerprint
import chummer.core.data.GameDataImportException
import chummer.core.data.GameDataZip
import java.io.File

/**
 * The app bundles the latest stable Chummer data, and a player who prefers another version can import it instead. They give the app
 * their own copy of Chummer 5a, as the release zip or as the unpacked folder, and this copies the files the rules engine reads into app storage, where [GameDataStore] looks for them. Nothing leaves
 * the phone and nothing is downloaded. (The zip and folder readers are the same two things, one on a file and one on a folder tree.)
 *
 * The copy is made beside the live data and swapped in at the end, so a choice that fails half way never leaves the app without data.
 */
object GameDataImporter {
    /** What the app reads, as folders under the app's files directory (the same names the bundled copy unpacks into). */
    const val DATA = "chummer-data"
    const val LANG = "chummer-lang"
    const val CUSTOM = "chummer-customdata"

    /** A file every Chummer data folder has; its presence is how a folder is recognised and how the app knows data is installed. */
    const val MARKER = "metatypes.xml"

    private const val SEARCH_DEPTH = 3

    /** What was installed: how many files, Chummer's build number if the changelog was there, and the fingerprint of the data. */
    class Imported(val files: Int, val build: String?, val fingerprint: String)

    class Found(val data: DocumentFile, val lang: DocumentFile?, val custom: DocumentFile?, val changelog: DocumentFile?)

    /** Imported data lives apart from the bundled copy, so the bundled one is never overwritten and "use the bundled data" is just deleting this. */
    fun root(context: Context) = File(context.filesDir, "imported-game-data")

    fun installed(context: Context): Boolean = File(File(root(context), DATA), MARKER).isFile

    /** Go back to the data that came with the app. */
    fun remove(context: Context) { root(context).deleteRecursively() }

    private fun staging(context: Context) = File(context.filesDir, "game-data-staging").also { it.deleteRecursively(); it.mkdirs() }

    /** Install the data from the Chummer release zip [uri]. The zip is copied to the cache first (a zip cannot be read in one pass without its end). */
    fun importZip(context: Context, uri: Uri, progress: (String) -> Unit = {}): Imported {
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        if (size != null && size > GameDataZip.MAX_ZIP_BYTES) {
            throw GameDataImportException("That file is ${size / (1024 * 1024)} MB, far bigger than a Chummer release (about 45 MB). Download the release zip from the Chummer releases page, not the source code.")
        }
        val copy = File(context.cacheDir, "chummer-import.zip")
        val staging = staging(context)
        try {
            progress("Reading the zip")
            val input = context.contentResolver.openInputStream(uri) ?: throw GameDataImportException("That file could not be read.")
            input.use { i ->
                copy.outputStream().use { o ->
                    // The limit also protects a file whose size was not reported.
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > GameDataZip.MAX_ZIP_BYTES) throw GameDataImportException("That file is far bigger than a Chummer release (about 45 MB). Download the release zip from the Chummer releases page, not the source code.")
                        o.write(buffer, 0, n)
                    }
                }
            }
            val r = GameDataZip.extract(copy, staging) { progress("Copied $it files") }
            return swapIn(context, staging, r.files, r.build)
        } finally {
            copy.delete()
            staging.deleteRecursively()
        }
    }

    /** Install the data from a folder the player picked (the unpacked release, or the folder holding it). */
    fun importFolder(context: Context, tree: Uri, progress: (String) -> Unit = {}): Imported {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw GameDataImportException("That folder could not be opened.")
        val found = find(root) ?: throw GameDataImportException(
            "No Chummer game data found there. Choose the Chummer folder: the one that contains \"data\" (with $MARKER), \"lang\" and \"customdata\". Or choose the release zip.",
        )
        val staging = staging(context)
        try {
            var files = copyDir(context, found.data, File(staging, DATA), recurse = false) { it.endsWith(".xml") }
            found.lang?.findFile("en-us.xml")?.let { copyFile(context, it, File(File(staging, LANG).apply { mkdirs() }, "en-us.xml")); files++ }
            found.custom?.let { files += copyDir(context, it, File(staging, CUSTOM)) { n -> !n.endsWith(".xsd") } }
            progress("Copied $files files")
            val build = found.changelog?.let { f -> runCatching { context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { GameDataZip.buildOf(it.readText()) } }.getOrNull() }
            return swapIn(context, staging, files, build)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Replace the live folders with the staged ones, each as a whole. */
    private fun swapIn(context: Context, staging: File, files: Int, build: String?): Imported {
        if (!File(File(staging, DATA), MARKER).isFile) throw GameDataImportException("No Chummer game data found.")
        File(staging, CUSTOM).mkdirs()
        File(staging, LANG).mkdirs()
        val fingerprint = GameDataFingerprint.ofDirectory(File(staging, DATA))
        for (name in listOf(DATA, LANG, CUSTOM)) {
            val target = File(root(context), name)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!File(staging, name).renameTo(target)) throw GameDataImportException("Could not store the game data.")
        }
        return Imported(files, build, fingerprint)
    }

    /**
     * Look for Chummer's `data` folder: in [dir] itself, then up to [SEARCH_DEPTH] folders down. Choosing the Chummer folder, the folder
     * that holds it (for example the unzipped download), or the `data` folder itself all work.
     */
    fun find(dir: DocumentFile): Found? {
        var level = listOf(dir)
        repeat(SEARCH_DEPTH + 1) {
            for (d in level) at(d)?.let { return it }
            level = level.flatMap { it.listFiles().filter { f -> f.isDirectory } }
            if (level.isEmpty()) return null
        }
        return null
    }

    private fun at(dir: DocumentFile): Found? {
        dir.findFile("data")?.takeIf { it.isDirectory && it.findFile(MARKER) != null }?.let {
            return Found(it, dir.findFile("lang")?.takeIf(DocumentFile::isDirectory), dir.findFile("customdata")?.takeIf(DocumentFile::isDirectory), dir.findFile("changelog.txt"))
        }
        // The player chose the data folder itself: no way to reach its siblings, so the language file is left out.
        if (dir.findFile(MARKER) != null) return Found(dir, null, null, null)
        return null
    }

    private fun copyDir(context: Context, from: DocumentFile, to: File, recurse: Boolean = true, accept: (String) -> Boolean): Int {
        to.mkdirs()
        var n = 0
        for (f in from.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) { if (recurse) n += copyDir(context, f, File(to, name), true, accept) }
            else if (accept(name)) { copyFile(context, f, File(to, name)); n++ }
        }
        return n
    }

    private fun copyFile(context: Context, from: DocumentFile, to: File) {
        val input = context.contentResolver.openInputStream(from.uri) ?: throw GameDataImportException("Could not read ${from.name}.")
        input.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
    }
}
