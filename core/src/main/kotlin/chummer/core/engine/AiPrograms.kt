package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** A program or advanced program for a picker, and what it still needs. */
data class AiProgramOption(val id: String, val name: String, val category: String, val advanced: Boolean, val source: String, val page: String, val needs: String)

/** A program on the character. */
data class AiProgramLine(val guid: String, val name: String, val extra: String, val advanced: Boolean)

/**
 * An A.I.'s programs and advanced programs, in Chummer's layout (`aiprograms`). In creation each program past the free count
 * (`ainormalprogramlimit`, `aiadvancedprogramlimit`, which only some talents set) costs `karmanewaiprogram` (5) or
 * `karmanewaiadvancedprogram` (8), a spare advanced program slot covering a normal one first; in career a program is bought for the same
 * karma. A program that names a `require` (an advanced program asks for a program) can only be had after that one.
 */
class AiProgramEditor(private val file: CharacterFile, private val data: GameData) {
    private val root get() = file.root
    private fun rows() = root.child("aiprograms")?.children("aiprogram").orEmpty()

    fun programs(): List<AiProgramLine> = rows().map {
        AiProgramLine(it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("extra").orEmpty().trim(), it.text("isadvancedprogram")?.trim().equals("True", true))
    }

    private fun needs(entry: Element): String {
        val have = rows().map { it.text("name")?.trim().orEmpty() }.toSet()
        val require = entry.child("require")?.textContent?.trim().orEmpty()
        if (require.isNotEmpty() && require !in have) return "needs $require"
        val oneof = entry.child("required")?.children("oneof")?.flatMap { it.children("program") }.orEmpty().map { it.textContent.trim() }
        if (oneof.isNotEmpty() && oneof.none { it in have }) return "needs one of " + oneof.joinToString(", ")
        return ""
    }

    fun options(): List<AiProgramOption> {
        val pack = data as? DataPack ?: return emptyList()
        val have = rows().map { it.text("name")?.trim().orEmpty() }.toSet()
        return pack.byId.entries.filter { it.value.file == "programs.xml" && it.value.element.tagName == "program" && it.value.element.child("hide") == null }.mapNotNull { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val name = t("name")
            if (name.isEmpty() || name in have) return@mapNotNull null
            AiProgramOption(id, name, t("category"), t("category") == "Advanced Programs", t("source"), t("page"), needs(el))
        }.sortedWith(compareBy({ !it.advanced }, { it.category }, { it.name.lowercase() }))
    }

    /** Add a program. Null when it is unknown, already known, or its requirement is not met. */
    fun add(entryId: String, extra: String = ""): String? {
        val entry = data[entryId]?.element ?: return null
        if (entry.tagName != "program") return null
        val name = entry.child("name")?.textContent?.trim().orEmpty()
        if (rows().any { it.text("name")?.trim() == name } || needs(entry).isNotEmpty()) return null
        val doc = file.doc
        fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        val guid = UUID.randomUUID().toString()
        val p = el("aiprogram")
        val advanced = entry.child("category")?.textContent?.trim() == "Advanced Programs"
        for ((tag, v) in listOf(
            "sourceid" to entryId.lowercase(), "guid" to guid, "name" to name, "candelete" to "True", "isadvancedprogram" to if (advanced) "True" else "False",
            "requiresprogram" to (entry.child("require")?.textContent?.trim().orEmpty()), "extra" to extra, "source" to (entry.child("source")?.textContent?.trim().orEmpty()),
            "page" to (entry.child("page")?.textContent?.trim().orEmpty()), "notes" to "", "notesColor" to "Black",
        )) p.appendChild(el(tag, v))
        (root.child("aiprograms") ?: el("aiprograms").also { root.appendChild(it) }).appendChild(p)
        entry.child("bonus")?.takeIf { it.children().isNotEmpty() }?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext("AIProgram", guid, 1, extra, name, objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    fun remove(guid: String): Boolean {
        val p = rows().firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        if (p.text("candelete")?.trim().equals("False", true)) return false
        root.child("improvements")?.children("improvement")?.filter { it.text("sourcename")?.trim().equals(guid, true) }?.forEach { it.parentNode.removeChild(it) }
        p.parentNode.removeChild(p)
        return true
    }

    companion object {
        /** Chummer's karma for programs in creation (see `CharacterCreate`): those past the free limits, with a spare advanced slot covering a normal program first. */
        fun creationKarma(c: CharacterFile, normalPrice: Int, advancedPrice: Int): Int {
            val rows = c.root.child("aiprograms")?.children("aiprogram").orEmpty().filter { it.text("candelete")?.trim() != "False" }
            val advanced = rows.count { it.text("isadvancedprogram")?.trim().equals("True", true) }
            var normal = rows.size - advanced
            val normalLimit = c.root.text("ainormalprogramlimit")?.trim()?.toIntOrNull() ?: 0
            val advancedLimit = c.root.text("aiadvancedprogramlimit")?.trim()?.toIntOrNull() ?: 0
            var cost = 0
            if (normal > normalLimit) {
                if (advanced < advancedLimit) normal -= minOf(normal - normalLimit, advancedLimit - advanced)
                if (normal > normalLimit) cost += (normal - normalLimit) * normalPrice
            }
            if (advanced > advancedLimit) cost += (advanced - advancedLimit) * advancedPrice
            return cost
        }
    }
}

/** The karma price of a program: the settings' price changed by the new-program cost improvements of that kind (Chummer's `AIProgramKarmaCost`). */
fun aiProgramPrice(base: Int, set: ImprovementSet, advanced: Boolean): Int {
    val add = if (advanced) "NewAIAdvancedProgramKarmaCost" else "NewAIProgramKarmaCost"
    var v = base.toDouble() + set.all.filter { it.type == add && it.enabled }.sumOf { it.value * it.rating }
    var m = 1.0
    set.all.filter { it.type == add + "Multiplier" && it.enabled }.forEach { m *= it.value / 100.0 }
    return maxOf(standardRound(v * m), 0)
}
