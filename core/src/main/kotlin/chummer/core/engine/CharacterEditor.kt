package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** The colour Chummer 5.226 gives the notes of new items (older files hold the name "Chocolate" for the same colour). */
internal const val NOTES_COLOR = "#E3813C"

/**
 * Changes to a character: adding and removing items, with the improvements those items grant kept in step.
 *
 * The edits are made on the character file's own document, in the layout Chummer writes, so the file stays
 * openable in desktop Chummer and the sheet can be rebuilt from it after every change.
 */
class CharacterEditor(
    val file: CharacterFile,
    private val data: GameData,
    private val translate: (String) -> String? = { null },
) {
    private val doc get() = file.doc
    private val root get() = file.root

    private fun element(tag: String, text: String? = null): Element =
        doc.createElement(tag).also { if (text != null) it.textContent = text }

    private fun container(tag: String): Element =
        root.child(tag) ?: element(tag).also { root.appendChild(it) }

    private fun entryText(entry: Element, tag: String): String? = entry.child(tag)?.textContent?.trim()

    private fun entryFlag(entry: Element, tag: String, default: Boolean): String =
        (entryText(entry, tag)?.equals("True", true) ?: default).let { if (it) "True" else "False" }

    /**
     * Add a quality from the game data. [extra] is the user's choice for qualities that ask for one (a skill, a
     * text), [levels] the level for qualities that have several. [qualitySource] is "Selected" for a quality the
     * user bought, or "Metatype" for a free one the metatype grants (those do not count toward the quality limit).
     * Returns the new item's GUID.
     */
    fun addQuality(entryId: String, extra: String = "", levels: Int = 1, qualitySource: String = "Selected"): String {
        val entry = data[entryId]?.element ?: error("No quality with id $entryId in the game data")
        val guid = UUID.randomUUID().toString()
        val name = entryText(entry, "name").orEmpty()

        val q = element("quality")
        fun add(tag: String, text: String? = null) = q.appendChild(element(tag, text))
        add("sourceid", entryId.lowercase())
        add("guid", guid)
        add("name", name)
        add("extra", extra)
        add("bp", entryText(entry, "karma") ?: "0")
        add("implemented", "True")
        add("contributetobp", entryFlag(entry, "contributetobp", true))
        add("contributetolimit", if (qualitySource == "Metatype") "False" else entryFlag(entry, "contributetolimit", true))
        add("stagedpurchase", "False")
        add("doublecareer", entryFlag(entry, "doublecareer", true))
        add("canbuywithspellpoints", entryFlag(entry, "canbuywithspellpoints", false))
        add("metagenic", entryFlag(entry, "metagenic", false))
        add("print", entryFlag(entry, "print", true))
        add("qualitytype", entryText(entry, "category") ?: "Positive")
        add("qualitysource", qualitySource)
        add("mutant", entryFlag(entry, "mutant", false))
        add("source", entryText(entry, "source").orEmpty())
        add("page", entryText(entry, "page").orEmpty())
        add("sourcename")
        for (tag in listOf("bonus", "firstlevelbonus", "naturalweapons")) {
            val node = entry.child(tag)
            q.appendChild(if (node != null) doc.importNode(node, true) else element(tag))
        }
        add("notes")
        add("notesColor", NOTES_COLOR)
        container("qualities").appendChild(q)

        grantBonus(entry.child("bonus"), BonusContext("Quality", guid, levels, extra, name, translate))
        return guid
    }

    /** Remove the item with this GUID (a quality, for now) and every improvement it granted. Returns false if none. */
    fun removeItem(guid: String): Boolean {
        val item = root.children("qualities").flatMap { it.children() }.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        item.parentNode.removeChild(item)
        removeImprovementsOf(guid)
        return true
    }

    private fun grantBonus(bonus: Element?, ctx: BonusContext) {
        if (bonus == null || bonus.children().isEmpty()) return
        val result = BonusHandlers.apply(bonus, ctx)
        val list = container("improvements")
        for (imp in result.improvements) list.appendChild(ImprovementWriter.toElement(doc, imp))
    }

    private fun removeImprovementsOf(guid: String) {
        val list = root.child("improvements") ?: return
        for (i in list.children("improvement")) {
            if (i.text("sourcename")?.trim().equals(guid, true)) list.removeChild(i)
        }
    }
}

/** Writes an improvement in the element layout Chummer uses in `.chum5` files. */
object ImprovementWriter {
    private fun number(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    fun toElement(doc: org.w3c.dom.Document, i: Improvement): Element {
        val e = doc.createElement("improvement")
        fun add(tag: String, text: String = "") { e.appendChild(doc.createElement(tag).also { if (text.isNotEmpty()) it.textContent = text }) }
        // Chummer leaves the element out when there is no unique name.
        if (i.uniqueName.isNotEmpty()) add("unique", i.uniqueName)
        add("target")
        add("improvedname", i.improvedName)
        add("sourcename", i.sourceName)
        add("min", number(i.minimum))
        add("max", number(i.maximum))
        add("aug", number(i.augmented))
        add("augmax", number(i.augmentedMaximum))
        add("val", number(i.value))
        add("rating", number(i.rating))
        add("exclude", i.exclude)
        add("condition", i.condition)
        add("improvementttype", i.type)
        add("improvementsource", i.source)
        add("custom", if (i.custom) "True" else "False")
        add("customname")
        add("customid")
        add("customgroup", i.customGroup)
        add("addtorating", if (i.addToRating) "1" else "0")
        add("enabled", if (i.enabled) "1" else "0")
        add("order", "0")
        add("notes")
        add("notesColor", NOTES_COLOR)
        return e
    }
}

/** A quality that can be added, with what the editor can and cannot do with it yet. */
data class QualityOption(
    val id: String,
    val name: String,
    val category: String,
    val karma: Int,
    /** The quality asks the user to type something (a name, a contact). */
    val needsText: Boolean,
    /** Bonus node types with no handler yet; the quality can still be added, but those bonuses will be missing. */
    val unsupported: List<String>,
)

object QualityCatalog {
    /**
     * Every quality in the game data. Prerequisites (`required` and `forbidden`) and karma limits are not
     * enforced yet, so this lists them all.
     */
    fun list(data: chummer.core.DataPack): List<QualityOption> =
        data.byId.entries.filter { it.value.file == "qualities.xml" && it.value.element.tagName == "quality" }.map { (id, e) ->
            val el = e.element
            val bonusNodes = el.child("bonus")?.children().orEmpty().map { it.tagName.lowercase() }
            QualityOption(
                id = id, name = el.child("name")?.textContent?.trim().orEmpty(),
                category = el.child("category")?.textContent?.trim().orEmpty(),
                karma = el.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0,
                needsText = "selecttext" in bonusNodes,
                unsupported = bonusNodes.filter { !BonusHandlers.knows(it) },
            )
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
}
