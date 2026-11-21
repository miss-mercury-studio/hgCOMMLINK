package studio.missmercury.hgcommlink.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One character file in storage. [id] is a path (app folder) or a document URI (shared folder). */
data class StoredFile(val id: String, val name: String, val modified: Long, val size: Long)

/**
 * Where .chum5 files live. Characters are plain files so they can be moved, backed up,
 * synced by any tool and opened in desktop Chummer.
 */
interface CharacterStorage {
    val label: String
    fun list(): List<StoredFile>
    fun open(id: String): InputStream

    /** Create a new file (the name is made unique) and return its id and an output stream. */
    fun create(name: String): Pair<String, OutputStream>
    fun overwrite(id: String): OutputStream
    fun delete(id: String): Boolean
}

private const val EXT = ".chum5"

private val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/** Keeps the file name safe and ending in .chum5. */
fun safeFileName(name: String): String {
    val base = name.removeSuffix(EXT).replace(UNSAFE_NAME_CHARS, "_").trim().ifEmpty { "character" }
    return base + EXT
}

/** The app's private folder; works with no setup and needs no permissions. */
class AppFolderStorage(private val dir: File) : CharacterStorage {
    init {
        dir.mkdirs()
    }

    override val label get() = "App storage"

    override fun list() = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT, true) }.orEmpty()
        .map { StoredFile(it.absolutePath, it.name, it.lastModified(), it.length()) }

    override fun open(id: String): InputStream = File(id).inputStream().buffered()

    override fun create(name: String): Pair<String, OutputStream> {
        val safe = safeFileName(name)
        var f = File(dir, safe)
        var n = 2
        while (f.exists()) f = File(dir, safe.removeSuffix(EXT) + " (" + n++ + ")" + EXT)
        return f.absolutePath to f.outputStream().buffered()
    }

    override fun overwrite(id: String): OutputStream = File(id).outputStream().buffered()
    override fun delete(id: String) = File(id).delete()
}

/** A folder the user picked with the system file picker (device, SD card, Drive, ...). */
class TreeStorage(private val context: Context, private val tree: Uri) : CharacterStorage {
    private val root get() = DocumentFile.fromTreeUri(context, tree)
    override val label get() = root?.name ?: "Shared folder"

    override fun list(): List<StoredFile> = root?.listFiles().orEmpty()
        .filter { it.isFile && it.name?.endsWith(EXT, true) == true }
        .map { StoredFile(it.uri.toString(), it.name.orEmpty(), it.lastModified(), it.length()) }

    override fun open(id: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(id))?.buffered() ?: error("Cannot open $id")

    override fun create(name: String): Pair<String, OutputStream> {
        val dir = root ?: error("Folder is no longer available")
        val safe = safeFileName(name)
        var candidate = safe
        var n = 2
        while (dir.findFile(candidate) != null) candidate = safe.removeSuffix(EXT) + " (" + n++ + ")" + EXT
        // A generic MIME type keeps providers from appending their own extension to the name.
        val doc = dir.createFile("application/octet-stream", candidate) ?: error("Cannot create $candidate")
        val out = context.contentResolver.openOutputStream(doc.uri, "wt") ?: error("Cannot write $candidate")
        return doc.uri.toString() to out.buffered()
    }

    override fun overwrite(id: String): OutputStream =
        context.contentResolver.openOutputStream(Uri.parse(id), "wt")?.buffered() ?: error("Cannot write $id")

    override fun delete(id: String) = DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(id))
}
