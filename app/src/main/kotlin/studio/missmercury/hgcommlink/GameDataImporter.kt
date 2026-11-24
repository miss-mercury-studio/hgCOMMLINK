package studio.missmercury.hgcommlink

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * The Play edition ships no game data. The player points the app at their own copy of Chummer 5a (an unzipped download of the
 * release, or a clone of the repository) and this copies the files the rules engine reads into app storage, where
 * [GameDataStore] looks for them. Nothing leaves the phone and nothing is downloaded.
 *
 * The copy is made beside the live data and swapped in at the end, so a folder that fails half way never leaves the app without data.
 */
object GameDataImporter {
    /** What the app reads, as folders under the app's files directory (the same names the GitHub edition unpacks into). */
    const val DATA = "chummer-data"
    const val LANG = "chummer-lang"
    const val CUSTOM = "chummer-customdata"

    /** A file every Chummer data folder has; its presence is how a folder is recognised and how the app knows data is installed. */
    const val MARKER = "metatypes.xml"

    private const val SEARCH_DEPTH = 3

    class Found(val data: DocumentFile, val lang: DocumentFile?, val custom: DocumentFile?)

    fun installed(context: Context): Boolean = File(File(context.filesDir, DATA), MARKER).isFile

    /** Copy the game data found in [tree] (the folder the player picked) into app storage. Returns a one-line summary for the player. */
    fun import(context: Context, tree: Uri): String {
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("That folder could not be opened.")
        val found = find(root) ?: error(
            "No Chummer game data found there. Choose the Chummer folder: the one that contains \"data\" (with $MARKER), \"lang\" and \"customdata\".",
        )

        val staging = File(context.filesDir, "game-data-staging").also { it.deleteRecursively(); it.mkdirs() }
        try {
            var files = copyDir(context, found.data, File(staging, DATA), recurse = false) { it.endsWith(".xml") }
            found.lang?.let { lang ->
                lang.findFile("en-us.xml")?.let { copyFile(context, it, File(File(staging, LANG).apply { mkdirs() }, "en-us.xml")); files++ }
            }
            found.custom?.let { files += copyDir(context, it, File(staging, CUSTOM)) { n -> !n.endsWith(".xsd") } }
            File(staging, CUSTOM).mkdirs()
            File(staging, LANG).mkdirs()

            // Swap in. Each target is replaced as a whole folder.
            for (name in listOf(DATA, LANG, CUSTOM)) {
                val target = File(context.filesDir, name)
                target.deleteRecursively()
                check(File(staging, name).renameTo(target)) { "Could not store the game data." }
            }
            return buildString {
                append("Game data added ($files files).")
                if (found.lang == null) append(" No \"lang\" folder found, so a few texts may be missing.")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Look for Chummer's `data` folder: in [dir] itself, then up to [SEARCH_DEPTH] folders down. Choosing the Chummer folder, the folder
     * that holds it (for example the unzipped download), or the `data` folder itself all work.
     */
    fun find(dir: DocumentFile): Found? {
        var level = listOf(dir)
        repeat(SEARCH_DEPTH + 1) {
            for (d in level) {
                at(d)?.let { return it }
            }
            level = level.flatMap { it.listFiles().filter { f -> f.isDirectory } }
            if (level.isEmpty()) return null
        }
        return null
    }

    private fun at(dir: DocumentFile): Found? {
        dir.findFile("data")?.takeIf { it.isDirectory && it.findFile(MARKER) != null }?.let {
            return Found(it, dir.findFile("lang")?.takeIf(DocumentFile::isDirectory), dir.findFile("customdata")?.takeIf(DocumentFile::isDirectory))
        }
        // The player chose the data folder itself: no way to reach its siblings, so the language file is left out.
        if (dir.findFile(MARKER) != null) return Found(dir, null, null)
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
        val input = context.contentResolver.openInputStream(from.uri) ?: error("Could not read ${from.name}.")
        input.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
    }
}
