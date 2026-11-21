package studio.missmercury.hgcommlink

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import chummer.core.CharacterFile
import chummer.core.CharacterIo
import studio.missmercury.hgcommlink.storage.AppFolderStorage
import studio.missmercury.hgcommlink.storage.CharacterStorage
import studio.missmercury.hgcommlink.storage.StoredFile
import studio.missmercury.hgcommlink.storage.TreeStorage
import java.io.File

/** A stored file plus the summary read from it (or the reason it could not be read). */
data class CharacterEntry(val file: StoredFile, val summary: CharacterFile.Summary?, val error: String?)

/** Owns the current storage location and the import, export and delete operations. */
class CharacterRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)
    private val summaryCache = HashMap<String, Pair<Pair<Long, Long>, CharacterEntry>>()

    @Volatile
    var storage: CharacterStorage = restore()
        private set

    private fun appFolder() = AppFolderStorage(File(context.filesDir, "characters"))

    private fun restore(): CharacterStorage {
        val uri = prefs.getString("tree", null)?.let(Uri::parse) ?: return appFolder()
        val granted = context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return if (granted) TreeStorage(context, uri) else appFolder()
    }

    /** Switch to a folder chosen in the system picker, keeping access across restarts. */
    fun useFolder(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString("tree", tree.toString()).apply()
        summaryCache.clear()
        storage = TreeStorage(context, tree)
    }

    fun useAppFolder() {
        prefs.edit().remove("tree").apply()
        summaryCache.clear()
        storage = appFolder()
    }

    @Synchronized
    fun list(): List<CharacterEntry> = storage.list().sortedBy { it.name.lowercase() }.map { f ->
        val key = f.modified to f.size
        summaryCache[f.id]?.takeIf { it.first == key }?.second ?: read(f).also { summaryCache[f.id] = key to it }
    }

    private fun read(f: StoredFile): CharacterEntry = try {
        CharacterEntry(f, storage.open(f.id).use { CharacterIo.read(it).summary() }, null)
    } catch (e: Exception) {
        CharacterEntry(f, null, e.message ?: e.javaClass.simpleName)
    }

    /**
     * Copy the `.xml` files kept beside the characters into the app's folders (replacing what was there).
     * Files that belong to a custom data folder (a manifest, `amend_`, `custom_` and `override_` files) go to
     * [customDir]; the rest (settings profiles) go to [settingsDir]. Returns how many files were copied.
     */
    @Synchronized
    fun syncSideFiles(settingsDir: File, customDir: File): Int {
        val shared = File(customDir, "Shared folder")
        settingsDir.mkdirs()
        shared.mkdirs()
        val wanted = HashMap<File, StoredFile>()
        for (f in storage.listSettingsFiles()) {
            val lower = f.name.lowercase()
            val isCustom = lower == "manifest.xml" || listOf("amend_", "custom_", "override_").any { lower.startsWith(it) }
            wanted[File(if (isCustom) shared else settingsDir, f.name)] = f
        }
        // Remove copies whose source is gone.
        for (dir in listOf(settingsDir, shared)) dir.listFiles().orEmpty().filter { it.isFile && it !in wanted }.forEach { it.delete() }
        var n = 0
        for ((target, source) in wanted) {
            // Unchanged files are left alone, so the loaded game data stays valid between opens.
            if (target.isFile && target.length() == source.size && target.lastModified() == source.modified) { n++; continue }
            try {
                storage.open(source.id).use { i -> target.outputStream().use { i.copyTo(it) } }
                if (source.modified > 0) target.setLastModified(source.modified)
                n++
            } catch (e: Exception) {
                // An unreadable file is skipped; the sheet then warns about what is missing.
            }
        }
        return n
    }

    fun load(id: String): CharacterFile = storage.open(id).use { CharacterIo.read(it) }

    /**
     * Copy a file from elsewhere into storage. The content is parsed first, so a file that
     * is not a Chummer character is rejected instead of being saved.
     */
    @Synchronized
    fun import(source: Uri, displayName: String?): StoredFile {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: error("Cannot read the file")
        val parsed = try {
            CharacterIo.read(bytes.inputStream())
        } catch (e: Exception) {
            error("Not a valid Chummer character file (${e.message ?: e.javaClass.simpleName})")
        }
        check(parsed.root.tagName == "character") { "Not a Chummer character file" }
        val name = displayName?.takeIf { it.isNotBlank() } ?: parsed.name.ifBlank { "character" }
        val (id, out) = storage.create(name)
        out.use { it.write(bytes) }
        summaryCache.clear()
        return storage.list().first { it.id == id }
    }

    @Synchronized
    fun delete(id: String): Boolean = storage.delete(id).also { summaryCache.remove(id) }

    /** A share intent for the character, copied to the app cache and exposed through a FileProvider. */
    fun shareIntent(entry: StoredFile): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val copy = File(dir, entry.name)
        storage.open(entry.id).use { i -> copy.outputStream().use { i.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
