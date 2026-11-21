package chummer.core

import chummer.core.data.CustomData
import chummer.core.data.CustomDataDir
import org.w3c.dom.Element
import java.io.File

/**
 * Chummer's game data, loaded from the XML files in `data/`, with any custom data folders
 * applied on top in order. Every entry that has an `<id>` child is indexed by that GUID,
 * which is what a character file stores in `<sourceid>`.
 */
class DataPack private constructor(
    val byId: Map<String, DataEntry>,
    val fileCount: Int,
    private val grades: Map<String, Double>,
) : GameData {
    override fun gradeEssence(bioware: Boolean, grade: String): Double? = grades[(if (bioware) "bio:" else "cyb:") + grade]

    override operator fun get(id: String): DataEntry? = byId[id.lowercase()]

    companion object {
        private const val MAX_CACHED_FILES = 60
        private const val ZERO_ID = "00000000-0000-0000-0000-000000000000"

        /**
         * Load the base data and apply [customDirs] (already in load order).
         *
         * Applying custom data is slow on a phone (XPath over large documents), so when [cacheDir] is given the
         * amended document of each changed data file is saved there and read back next time. A cached file is
         * named after a fingerprint of the base file and every custom file that affects it, so editing either
         * simply produces a new cache entry; the oldest entries are deleted beyond a fixed number.
         */
        fun load(dataDir: File, customDirs: List<CustomDataDir> = emptyList(), cacheDir: File? = null): DataPack {
            cacheDir?.mkdirs()
            val used = HashSet<String>()
            val index = HashMap<String, DataEntry>(20_000)
            val grades = HashMap<String, Double>()
            val files = dataDir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
            for (f in files) {
                val doc = try {
                    amendedDocument(f, customDirs, cacheDir, used)
                } catch (e: Exception) {
                    continue
                }
                val stack = ArrayDeque<Element>().apply { add(doc.documentElement) }
                while (stack.isNotEmpty()) {
                    val e = stack.removeLast()
                    val kids = e.children()
                    if (e.tagName == "grade" && (f.name == "cyberware.xml" || f.name == "bioware.xml")) {
                        val gn = kids.firstOrNull { it.tagName == "name" }?.textContent?.trim()
                        val ge = kids.firstOrNull { it.tagName == "ess" }?.textContent?.trim()?.toDoubleOrNull()
                        if (gn != null && ge != null) grades[(if (f.name == "bioware.xml") "bio:" else "cyb:") + gn] = ge
                    }
                    val id = kids.firstOrNull { it.tagName == "id" }?.textContent?.trim()
                    // The all-zero GUID means "no entry" in character files; some data rows use it as a placeholder.
                    if (!id.isNullOrEmpty() && id != ZERO_ID && kids.any { it.tagName == "name" }) {
                        index.putIfAbsent(id.lowercase(), DataEntry(f.name, e))
                    }
                    stack.addAll(kids)
                }
            }
            // Keep the cache bounded: different characters may use different custom data, so drop only the oldest entries.
            cacheDir?.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_CACHED_FILES).forEach { it.delete() }
            return DataPack(index, files.size, grades)
        }

        private fun amendedDocument(base: File, customDirs: List<CustomDataDir>, cacheDir: File?, used: MutableSet<String>): org.w3c.dom.Document {
            val custom = if (customDirs.isEmpty()) emptyList() else CustomData.filesFor(base.name, customDirs)
            if (custom.isEmpty()) return parseXml(base)
            val cached = cacheDir?.let { File(it, base.name + "." + fingerprint(base, custom) + ".xml") }
            if (cached != null) {
                used += cached.name
                if (cached.isFile) runCatching { return parseXml(cached).also { cached.setLastModified(System.currentTimeMillis()) } }
            }
            val doc = parseXml(base)
            CustomData.apply(doc, base.name, customDirs)
            if (cached != null) runCatching { writeXml(doc, cached) }
            return doc
        }

        private fun fingerprint(base: File, custom: List<File>): String {
            val text = (listOf(base) + custom).joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }
            return java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(8)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
