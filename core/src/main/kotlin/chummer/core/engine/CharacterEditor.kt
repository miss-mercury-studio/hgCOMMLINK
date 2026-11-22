package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
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
    /** Needed for buying attributes and skills (their limits and the stored derived values come from the rules). */
    private val settings: CharacterSettings? = null,
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

    /**
     * Why a quality cannot be added to a Karma-build character in creation, or null if it can: Chummer does not allow
     * positive or negative qualities beyond the settings' karma limit. Other prerequisites are not checked yet.
     */
    fun qualityLimitProblem(entryId: String): String? {
        val cfg = settings ?: return null
        if (root.text("created")?.trim().equals("True", true) || root.text("buildmethod")?.trim() != "Karma") return null
        val entry = data[entryId]?.element ?: return null
        val karma = (entry.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0) * (cfg.karmaCost["karmaquality"] ?: 1)
        val ledger = KarmaLedger.of(file, cfg, data)
        return if (entry.child("category")?.textContent?.trim().equals("Negative", true)) {
            if (ledger.negativeQualities + karma > ledger.qualityLimit) "Negative qualities would be ${ledger.negativeQualities + karma}/${ledger.qualityLimit} karma" else null
        } else {
            if (ledger.positiveQualities + karma > ledger.qualityLimit) "Positive qualities would be ${ledger.positiveQualities + karma}/${ledger.qualityLimit} karma" else null
        }
    }

    private fun attributeRow(name: String): Element? =
        root.child("attributes")?.children("attribute")?.firstOrNull { it.text("name")?.trim() == name }

    /**
     * Buy ([delta] = 1) or give back ([delta] = -1) one point of an attribute with karma, as in Chummer's creation
     * screen. Fails (returns false) at the metatype maximum, or when no karma point is left to give back. The row's
     * stored total and the stored condition monitor values are updated, as Chummer does on saving.
     */
    fun changeAttribute(name: String, delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val rules = (settings ?: error("Settings are needed to change attributes")).toRuleSettings()
        val row = attributeRow(name) ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        val now = AttributeEngine.forFile(file, rules)[name] ?: return false
        if (delta > 0 && now.value >= now.totalMaximum) return false
        if (delta < 0 && karma <= 0) return false
        row.child("karma")!!.textContent = (karma + delta).toString()
        AttributeEngine.forFile(file, rules)[name]?.let { row.child("totalvalue")?.textContent = it.totalValue.toString() }
        refreshCalculatedValues()
        return true
    }

    /** Buy or give back one rating point of an active skill with karma. Fails at the creation maximum or with no karma point to give back. */
    fun changeSkill(name: String, delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val rules = (settings ?: error("Settings are needed to change skills")).toRuleSettings()
        val row = root.child("newskills")?.child("skills")?.children("skill")
            ?.firstOrNull { it.text("name")?.trim() == name && it.text("isknowledge")?.trim() != "True" } ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        val base = row.text("base")?.trim()?.toIntOrNull() ?: 0
        if (delta > 0 && base + karma >= rules.maxSkillRatingCreate) return false
        if (delta < 0 && karma <= 0) return false
        row.child("karma")!!.textContent = (karma + delta).toString()
        return true
    }

    /**
     * Buy or give back one step of a skill group with karma. Raising a group raises every member skill by one, so it stops
     * when a member would pass the creation maximum; giving back needs a karma step in the group.
     */
    fun changeSkillGroup(name: String, delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val rules = (settings ?: error("Settings are needed to change skill groups")).toRuleSettings()
        val groups = root.child("newskills")?.child("groups") ?: return false
        val row = groups.children("group").firstOrNull { it.text("name")?.trim() == name } ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        if (delta < 0 && karma <= 0) return false
        if (delta > 0) {
            val members = root.child("newskills")!!.child("skills")!!.children("skill").filter {
                data[it.text("suid")?.trim().orEmpty()]?.element?.child("skillgroup")?.textContent?.trim() == name
            }
            val highest = members.maxOfOrNull { m -> (m.text("base")?.trim()?.toIntOrNull() ?: 0) + (m.text("karma")?.trim()?.toIntOrNull() ?: 0) + karma } ?: karma
            if (highest + 1 > rules.maxSkillRatingCreate) return false
        }
        row.child("karma")!!.textContent = (karma + delta).toString()
        return true
    }

    private fun knowledgeRow(guid: String): Element? =
        root.child("newskills")?.child("knoskills")?.children("skill")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /**
     * Add a knowledge skill or language from the game data at rating 0. [type] is the kind the user picked (Academic,
     * Interest, Language, Professional or Street); it defaults to the data's category. A native language costs nothing.
     * Returns the new skill's GUID.
     */
    fun addKnowledgeSkill(entryId: String, type: String? = null, native: Boolean = false): String {
        val entry = data[entryId]?.element ?: error("No knowledge skill with id $entryId in the game data")
        val guid = UUID.randomUUID().toString()
        val kind = type ?: entry.child("category")?.textContent?.trim().orEmpty()
        val skill = element("skill")
        fun add(tag: String, text: String? = null) = skill.appendChild(element(tag, text))
        add("guid", guid)
        add("suid", entryId.lowercase())
        add("isknowledge", "True")
        add("skillcategory", kind)
        add("requiresgroundmovement", "False")
        add("requiresswimmovement", "False")
        add("requiresflymovement", "False")
        add("karma", "0")
        add("base", "0")
        add("notes")
        add("notesColor", "Chocolate")
        add("name", entry.child("name")?.textContent?.trim())
        add("buywithkarma", "False")
        add("type", kind)
        add("isnativelanguage", if (native) "True" else "False")
        val newSkills = root.child("newskills") ?: error("The character has no skills section")
        val list = newSkills.child("knoskills") ?: element("knoskills").also { newSkills.appendChild(it) }
        list.appendChild(skill)
        return guid
    }

    /**
     * Buy ([delta] = 1) or give back (-1) a rating of a knowledge skill. Buying uses the free knowledge points first and
     * then karma, unless [withKarma] is true. Giving back takes karma before points. Fails at the creation maximum, for a
     * native language, or with nothing to give back.
     */
    fun changeKnowledgeSkill(guid: String, delta: Int, withKarma: Boolean = false): Boolean {
        require(delta == 1 || delta == -1)
        val cfg = settings ?: error("Settings are needed to change knowledge skills")
        val row = knowledgeRow(guid) ?: return false
        if (row.text("isnativelanguage")?.trim() == "True") return false
        val base = row.text("base")?.trim()?.toIntOrNull() ?: 0
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        if (delta > 0) {
            if (base + karma >= cfg.toRuleSettings().maxKnowledgeSkillRatingCreate) return false
            val pointsLeft = KarmaLedger.of(file, cfg, data).knowledgePointsLeft
            if (!withKarma && pointsLeft > 0) row.child("base")!!.textContent = (base + 1).toString()
            else row.child("karma")!!.textContent = (karma + 1).toString()
        } else {
            when {
                karma > 0 -> row.child("karma")!!.textContent = (karma - 1).toString()
                base > 0 -> row.child("base")!!.textContent = (base - 1).toString()
                else -> return false
            }
        }
        return true
    }

    fun removeKnowledgeSkill(guid: String): Boolean {
        val row = knowledgeRow(guid) ?: return false
        row.parentNode.removeChild(row)
        return true
    }

    /** Rewrite the values Chummer keeps beside the character (condition monitor boxes and thresholds). */
    private fun refreshCalculatedValues() {
        val cfg = settings ?: return
        val set = ImprovementSet(Improvement.parseAll(root), isCareer = false)
        val attrs = AttributeEngine.forSet(file, set, cfg.toRuleSettings())
        val d = DerivedStats(set, attrs, 6.0, cfg)
        val values = mapOf(
            "physicalcm" to d.physicalCM, "physicalcmthresholdoffset" to d.physicalCMThresholdOffset,
            "physicalcmoverflow" to d.cmOverflow, "stuncm" to d.stunCM, "stuncmthresholdoffset" to d.stunCMThresholdOffset,
        )
        val cv = root.child("calculatedvalues") ?: return
        for ((tag, v) in values) cv.child(tag)?.textContent = v.toString()
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

/** A knowledge skill or language that can be added. */
data class KnowledgeOption(val id: String, val name: String, val category: String)

object KnowledgeCatalog {
    fun list(data: chummer.core.DataPack): List<KnowledgeOption> =
        data.byId.entries.filter { it.value.file == "skills.xml" && (it.value.element.parentNode as? Element)?.tagName == "knowledgeskills" }.map { (id, e) ->
            KnowledgeOption(id, e.element.child("name")?.textContent?.trim().orEmpty(), e.element.child("category")?.textContent?.trim().orEmpty())
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
}
