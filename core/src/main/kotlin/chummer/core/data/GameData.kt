package chummer.core.data

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Which Chummer game data is on the device, and whether this app has been checked against it.
 *
 * Players of the Play edition bring their own copy of Chummer's data, and Chummer changes its data between builds, so the app
 * identifies a data set by a fingerprint of its files (the version is in no file) and compares it with chummer-supported.txt,
 * the list of data sets the engine is tested on. The same fingerprint is made by `tools/data_sets.py`.
 */
object GameDataFingerprint {
    /**
     * SHA-256 over the top-level `*.xml` files of Chummer's `data` folder: for each file, in name order, its name and the SHA-256 of its
     * bytes with carriage returns removed (so Windows and Linux copies of the same data agree).
     */
    fun ofDirectory(dataDir: File): String {
        val all = MessageDigest.getInstance("SHA-256")
        for (f in dataDir.listFiles { x -> x.isFile && x.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }) {
            val one = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            val clean = ByteArray(buffer.size)
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    var m = 0
                    for (i in 0 until n) if (buffer[i] != 13.toByte()) clean[m++] = buffer[i]
                    one.update(clean, 0, m)
                }
            }
            all.update((f.name + "\n").toByteArray(Charsets.UTF_8))
            all.update((hex(one.digest()) + "\n").toByteArray(Charsets.US_ASCII))
        }
        return hex(all.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** One line of chummer-supported.txt: `fingerprint | name | source`, where source is `pin` or `release:TAG`. */
data class SupportedDataSet(val fingerprint: String, val name: String, val source: String) {
    /** The chummer5a release this data set is the zip of, or null (the pinned checkout). */
    val releaseTag: String? get() = source.removePrefix("release:").takeIf { source.startsWith("release:") }
}

object SupportedData {
    fun parse(text: String): List<SupportedDataSet> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line -> line.split("|").map { it.trim() }.takeIf { it.size == 3 && it[0].length == 64 }?.let { SupportedDataSet(it[0], it[1], it[2]) } }
        .toList()

    /** The release players are sent to: the first `release:` line in the list. */
    fun recommended(list: List<SupportedDataSet>): SupportedDataSet? = list.firstOrNull { it.releaseTag != null }

    fun releaseUrl(tag: String) = "https://github.com/chummer5a/chummer5a/releases/tag/$tag"
}

enum class DataStatus { SUPPORTED, UNCHECKED }

/** The game data in use: what it is, and whether the engine is tested on it. [build] is Chummer's build number from its changelog, when known. */
data class GameDataVersion(
    val fingerprint: String, val name: String, val status: DataStatus, val build: String?, val source: String, val importedAt: String?,
) {
    val short: String get() = fingerprint.take(8)

    /** For a player: "Chummer 5.226.0" when it is a data set we know, otherwise what we can say about it. */
    val label: String get() = if (status == DataStatus.SUPPORTED) name else if (build != null) "Chummer build $build (not checked)" else "Unknown Chummer data (not checked)"

    /** One line for reports and screens: label and the start of the fingerprint. */
    val line: String get() = "$label [$short]"

    fun toText(): String = Properties().apply {
        setProperty("fingerprint", fingerprint); setProperty("name", name); setProperty("status", status.name)
        build?.let { setProperty("build", it) }; setProperty("source", source); importedAt?.let { setProperty("importedAt", it) }
    }.let { p -> java.io.StringWriter().also { w -> p.store(w, null) }.toString() }

    companion object {
        /** [name] and [status] come from the supported list, by fingerprint. */
        fun identify(fingerprint: String, build: String?, supported: List<SupportedDataSet>, source: String, importedAt: String?): GameDataVersion {
            val known = supported.firstOrNull { it.fingerprint == fingerprint }
            return GameDataVersion(fingerprint, known?.name.orEmpty(), if (known != null) DataStatus.SUPPORTED else DataStatus.UNCHECKED, build, source, importedAt)
        }

        fun fromText(text: String): GameDataVersion? = runCatching {
            val p = Properties().apply { load(text.reader()) }
            GameDataVersion(
                p.getProperty("fingerprint"), p.getProperty("name").orEmpty(), DataStatus.valueOf(p.getProperty("status")),
                p.getProperty("build"), p.getProperty("source").orEmpty(), p.getProperty("importedAt"),
            )
        }.getOrNull()
    }
}

/** A problem with what the player chose, worded for the player. */
class GameDataImportException(message: String) : Exception(message)

/**
 * Takes the files the app reads out of a Chummer release zip. The zip is laid out with `data`, `lang` and `customdata` at its root (the
 * stable releases) or inside up to three folders (a copy of the source tree, `Chummer/data`); the folder holding `data/metatypes.xml` is the root.
 */
object GameDataZip {
    class Result(val files: Int, val build: String?)

    /** Chummer's largest release zip is under 50 MB; a much bigger file is something else (for example the whole source repository). */
    const val MAX_ZIP_BYTES = 250L * 1024 * 1024

    private val dataMarker = Regex("""^((?:[^/]+/){0,3})data/metatypes\.xml$""")

    /** Copies the data into [staging]: `chummer-data`, `chummer-lang` and `chummer-customdata` folders. Returns how much was copied. */
    fun extract(zip: File, staging: File, progress: (Int) -> Unit = {}): Result {
        val z = try { ZipFile(zip) } catch (e: Exception) { throw GameDataImportException("That file is not a zip file.") }
        z.use {
            val names = z.entries().asSequence().map { it.name }.toList()
            val prefix = names.mapNotNull { dataMarker.matchEntire(it)?.groupValues?.get(1) }.minByOrNull { it.length }
                ?: throw GameDataImportException(
                    "That zip does not hold Chummer's game data (no data folder with ${"metatypes.xml"}). Use the Chummer release zip, not the source code.",
                )
            val canonical = staging.canonicalFile
            var files = 0
            var build: String? = null
            for (entry in z.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val rel = entry.name.substring(prefix.length)
                val target = when {
                    rel.startsWith("data/") && rel.count { it == '/' } == 1 && rel.endsWith(".xml") -> "chummer-data/" + rel.removePrefix("data/")
                    rel == "lang/en-us.xml" -> "chummer-lang/en-us.xml"
                    rel.startsWith("customdata/") && !rel.endsWith(".xsd") -> "chummer-customdata/" + rel.removePrefix("customdata/")
                    rel == "changelog.txt" -> { build = buildOf(z.getInputStream(entry).bufferedReader().use { it.readText() }); continue }
                    else -> continue
                }
                val out = File(staging, target).canonicalFile
                // Never write outside the staging folder, whatever the entry's name says.
                if (!out.path.startsWith(canonical.path + File.separator)) continue
                out.parentFile.mkdirs()
                z.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                files++
                if (files % 25 == 0) progress(files)
            }
            return Result(files, build)
        }
    }

    /** Chummer's build number from its changelog: the first `Build N` line (a `Future Build N` line above it is the unreleased one). */
    fun buildOf(changelog: String): String? = changelog.lineSequence().firstNotNullOfOrNull { Regex("""^Build (\d+)""").find(it.trim())?.groupValues?.get(1) }
}
