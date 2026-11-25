package chummer.core.data

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Which Chummer game data is on the device, and whether this app has been checked against it.
 *
 * Players can import their own copy of Chummer's data, and Chummer changes its data between builds, so the app
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

/**
 * The game data in use: what it is, and whether the engine is tested on it. [release] and [commit] are the Chummer version and the start of
 * its git commit, read from Chummer5.exe in the release the player imported (a nightly is "5.226.248", the stable release "5.226.0");
 * [build] is Chummer's build number from its changelog, the fallback when there is no exe.
 */
data class GameDataVersion(
    val fingerprint: String, val name: String, val status: DataStatus, val build: String?, val source: String, val importedAt: String?,
    val release: String? = null, val commit: String? = null,
) {
    val short: String get() = fingerprint.take(8)

    /** For a player: the name from the supported list ("Chummer 5.226.0") when it is a data set we know, otherwise the version we could read. */
    val label: String get() = when {
        status == DataStatus.SUPPORTED -> name
        release != null -> "Chummer $release"
        build != null -> "Chummer build $build"
        else -> "Unknown Chummer version"
    }

    /** Whether the player imported this, rather than it being the data that came with the app. */
    val isCustom: Boolean get() = source != "bundled"

    /**
     * "STABLE" for a Chummer release (its version ends in .0, like 5.226.0), "NIGHTLY" for anything between releases (5.226.248, where the last
     * number counts the builds since); null when the version could not be read. A naming convention, not something Chummer states.
     */
    val channel: String? get() = release?.let { if (it.endsWith(".0")) "STABLE" else "NIGHTLY" }

    /** Short form for a narrow screen: "Chummer 5.226.248 · nightly · unverified" (no commit or fingerprint). */
    val brief: String get() = (listOf(label) + listOfNotNull(
        if (isCustom) (channel?.lowercase() ?: "custom") else null,
        if (status == DataStatus.UNCHECKED) "unverified" else null,
    )).joinToString(" · ")

    /** One line for reports and screens: label, what is different about it, the commit and the start of the fingerprint. */
    val line: String get() {
        val notes = listOfNotNull(
            if (isCustom) (channel?.lowercase() ?: "custom") else null,
            if (status == DataStatus.UNCHECKED) "unverified" else null,
        )
        return label + (if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")") + (commit?.let { " @$it" } ?: "") + " [$short]"
    }

    fun toText(): String = Properties().apply {
        setProperty("fingerprint", fingerprint); setProperty("name", name); setProperty("status", status.name)
        build?.let { setProperty("build", it) }; setProperty("source", source); importedAt?.let { setProperty("importedAt", it) }
        release?.let { setProperty("release", it) }; commit?.let { setProperty("commit", it) }
    }.let { p -> java.io.StringWriter().also { w -> p.store(w, null) }.toString() }

    companion object {
        /** [name] and [status] come from the supported list, by fingerprint. */
        fun identify(
            fingerprint: String, build: String?, supported: List<SupportedDataSet>, source: String, importedAt: String?,
            release: String? = null, commit: String? = null,
        ): GameDataVersion {
            val known = supported.firstOrNull { it.fingerprint == fingerprint }
            return GameDataVersion(fingerprint, known?.name.orEmpty(), if (known != null) DataStatus.SUPPORTED else DataStatus.UNCHECKED, build, source, importedAt, release, commit)
        }

        fun fromText(text: String): GameDataVersion? = runCatching {
            val p = Properties().apply { load(text.reader()) }
            GameDataVersion(
                p.getProperty("fingerprint"), p.getProperty("name").orEmpty(), DataStatus.valueOf(p.getProperty("status")),
                p.getProperty("build"), p.getProperty("source").orEmpty(), p.getProperty("importedAt"),
                p.getProperty("release"), p.getProperty("commit"),
            )
        }.getOrNull()
    }
}

/** Reads the version Chummer5.exe was built as, for example "5.226.248+dcdede6e..." (the .NET product version, with the git commit after the plus). */
object ExeVersion {
    class Version(val release: String, val commit: String?)

    private val key = "ProductVersion".toByteArray(Charsets.UTF_16LE)
    private val shape = Regex("""(\d+(?:\.\d+){1,3})(?:\+([0-9a-fA-F]{7,40}))?""")

    /** The version resource is UTF-16 text: the name "ProductVersion", padding, then the value. Null if there is none that looks like a version. */
    fun parse(exe: ByteArray): Version? {
        var from = 0
        while (true) {
            val at = indexOf(exe, key, from)
            if (at < 0) return null
            from = at + key.size
            var i = from
            while (i + 1 < exe.size && exe[i] == 0.toByte() && exe[i + 1] == 0.toByte() && i < from + 8) i += 2
            val sb = StringBuilder()
            while (i + 1 < exe.size && sb.length < 100) {
                val c = (exe[i].toInt() and 0xff) or ((exe[i + 1].toInt() and 0xff) shl 8)
                if (c == 0) break
                sb.append(c.toChar()); i += 2
            }
            val m = shape.matchEntire(sb.toString().trim()) ?: continue
            val parts = m.groupValues[1].split(".")
            // Stable releases are "5.226.0.0"; show them as "5.226.0".
            val release = if (parts.size == 4 && parts[3] == "0") parts.take(3).joinToString(".") else m.groupValues[1]
            return Version(release, m.groupValues[2].takeIf { it.isNotEmpty() }?.take(7)?.lowercase())
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}

/** A problem with what the player chose, worded for the player. */
class GameDataImportException(message: String) : Exception(message)

/**
 * Takes the files the app reads out of a Chummer release zip. The zip is laid out with `data`, `lang` and `customdata` at its root (the
 * stable releases) or inside up to three folders (a copy of the source tree, `Chummer/data`); the folder holding `data/metatypes.xml` is the root.
 */
object GameDataZip {
    class Result(val files: Int, val build: String?, val exe: ExeVersion.Version? = null)

    /** Chummer's largest release zip is under 50 MB; a much bigger file is something else (for example the whole source repository). */
    const val MAX_ZIP_BYTES = 250L * 1024 * 1024
    const val MAX_EXE_BYTES = 64L * 1024 * 1024

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
            var exe: ExeVersion.Version? = null
            for (entry in z.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val rel = entry.name.substring(prefix.length)
                val target = when {
                    rel.startsWith("data/") && rel.count { it == '/' } == 1 && rel.endsWith(".xml") -> "chummer-data/" + rel.removePrefix("data/")
                    rel == "lang/en-us.xml" -> "chummer-lang/en-us.xml"
                    rel.startsWith("customdata/") && !rel.endsWith(".xsd") -> "chummer-customdata/" + rel.removePrefix("customdata/")
                    rel == "Chummer5.exe" -> { if (entry.size in 1..MAX_EXE_BYTES) exe = ExeVersion.parse(z.getInputStream(entry).use { it.readBytes() }); continue }
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
            return Result(files, build, exe)
        }
    }

    /** Chummer's build number from its changelog: the first `Build N` line (a `Future Build N` line above it is the unreleased one). */
    fun buildOf(changelog: String): String? = changelog.lineSequence().firstNotNullOfOrNull { Regex("""^Build (\d+)""").find(it.trim())?.groupValues?.get(1) }
}
