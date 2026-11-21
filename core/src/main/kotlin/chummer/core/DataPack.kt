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
        /** Load the base data and apply [customDirs] (already in load order). */
        fun load(dataDir: File, customDirs: List<CustomDataDir> = emptyList()): DataPack {
            val index = HashMap<String, DataEntry>(20_000)
            val grades = HashMap<String, Double>()
            val files = dataDir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
            for (f in files) {
                val doc = try {
                    parseXml(f)
                } catch (e: Exception) {
                    continue
                }
                if (customDirs.isNotEmpty()) CustomData.apply(doc, f.name, customDirs)
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
                    if (!id.isNullOrEmpty() && kids.any { it.tagName == "name" }) {
                        index.putIfAbsent(id.lowercase(), DataEntry(f.name, e))
                    }
                    stack.addAll(kids)
                }
            }
            return DataPack(index, files.size, grades)
        }
    }
}
