package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text

/** One pool of free points: how many there are and how many are spent. */
data class Pool(val total: Int, val used: Int) {
    val left get() = total - used
}

/**
 * The free points of a Priority or Sum-to-Ten character: attribute points, special attribute points (Edge, Magic, Resonance),
 * skill points and skill group points. The totals come from the priority letters ([PriorityTable]); what is spent is read from
 * the `base` of the rows, which is how Chummer stores points in creation (a rating is the metatype minimum plus base plus karma).
 * Checked against the 5.202 fixtures: the attribute bases add up to exactly the attribute points, and the skill group bases to
 * exactly the group points.
 */
data class PointsLedger(val attributes: Pool, val special: Pool, val skills: Pool, val groups: Pool) {
    companion object {
        val STANDARD = setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL")
        val SPECIAL = setOf("EDG", "MAG", "MAGAdept", "RES", "DEP")

        /** True for the build methods that give free points. */
        fun usesPoints(c: CharacterFile) = c.root.text("buildmethod")?.trim() in setOf("Priority", "SumtoTen")

        fun of(c: CharacterFile, data: GameData?, tableName: String = "Standard", settings: chummer.core.data.CharacterSettings? = null): PointsLedger? {
            if (!usesPoints(c)) return null
            val pack = data as? DataPack ?: return null
            val a = PriorityTable(pack).allotment(c, tableName) ?: return null
            fun base(e: org.w3c.dom.Element) = e.text("base")?.trim()?.toIntOrNull() ?: 0
            val attrs = c.root.child("attributes")?.children("attribute").orEmpty()
            val standardUsed = attrs.filter { it.text("name")?.trim() in STANDARD }.sumOf(::base)
            val specialUsed = attrs.filter { it.text("name")?.trim() in SPECIAL }.sumOf(::base)
            val activeRows = c.root.child("newskills")?.child("skills")?.children("skill").orEmpty().filter { it.text("isknowledge")?.trim() != "True" }
            // A specialization costs a skill point, unless it has to be bought with karma (see [SkillSpecs]).
            val skillsUsed = activeRows.sumOf(::base) + SkillSpecs.split(c, settings, data).first
            val groupsUsed = c.root.child("newskills")?.child("groups")?.children("group").orEmpty().sumOf(::base)
            return PointsLedger(
                Pool(a.attributePoints, standardUsed), Pool(a.specialPoints, specialUsed),
                Pool(a.skillPoints, skillsUsed), Pool(a.skillGroupPoints, groupsUsed),
            )
        }
    }
}

/** How Chummer pays for specializations in the points builds: a skill point each, or karma when the skill is bought with karma. */
object SkillSpecs {
    /**
     * The paid specializations of the active skills as (paid with skill points, paid with karma). A specialization is paid with
     * karma when the skill is marked so, when the skill was bought with karma alone (unless the settings allow points for those),
     * or when the settings make specializations break skill groups and the skill's group has been raised. A skill with no rating
     * cannot have a specialization at all. Chummer's `Skill.BuyWithKarma` and `CurrentSpCost`.
     */
    fun split(c: CharacterFile, settings: chummer.core.data.CharacterSettings?, data: GameData?): Pair<Int, Int> {
        val groups = c.skillGroups()
        val allowPoints = settings?.bool("allowpointbuyspecializationsonkarmaskills") ?: false
        val breakGroups = settings?.bool("specializationsbreakskillgroups", true) ?: true
        var points = 0
        var karma = 0
        for (s in c.skills().filter { !it.isKnowledge && it.paidSpecializations > 0 }) {
            val group = data?.get(s.suid)?.element?.child("skillgroup")?.textContent?.trim().orEmpty()
            val (groupBase, groupKarma) = groups[group] ?: (0 to 0)
            val forced = (s.karma > 0 && s.base == 0 && !allowPoints) || (breakGroups && (groupBase > 0 || groupKarma > 0))
            // A skill with no rating cannot have a specialization.
            if (s.base + s.karma + groupBase + groupKarma == 0) continue
            if (s.buyWithKarma || forced) karma += s.paidSpecializations else points += s.paidSpecializations
        }
        return points to karma
    }
}

/** A skill's specialization in creation: what it has, and whether and what it could take. */
data class SkillSpecInfo(val skillGuid: String, val specGuid: String, val spec: String, val canAdd: Boolean, val choices: List<String>)

/** Specializations of active skills in creation: one each, on a skill that has a rating, chosen from the data's list and what improvements add. */
class SkillSpecEditor(private val file: CharacterFile, private val data: GameData) {
    private val root get() = file.root
    private fun row(guid: String) = file.skills().firstOrNull { it.guid.equals(guid, true) }

    /** The data's own specializations for the skill plus the options improvements add for it (a martial art's style), without repeats. */
    fun choices(guid: String, improvements: List<Improvement> = Improvement.parseAll(root)): List<String> {
        val s = row(guid) ?: return emptyList()
        if (s.isKnowledge) return emptyList()
        val own = data[s.suid]?.element?.child("specs")?.children("spec").orEmpty().map { it.textContent.trim() }
        val name = s.name.ifEmpty { data[s.suid]?.element?.child("name")?.textContent?.trim().orEmpty() }
        val added = improvements.filter { it.type == "SkillSpecializationOption" && it.enabled && it.improvedName == name && it.uniqueName.isNotBlank() }.map { it.uniqueName }
        return (own + added).distinct()
    }

    private fun element(guid: String) = (root.child("newskills")?.child("skills")?.children("skill").orEmpty()).firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** Add the skill's specialization. Null when the skill is unknown, a knowledge skill, already has one, or [hasRating] says it has no rating. */
    fun add(guid: String, name: String, hasRating: Boolean): String? {
        val text = name.trim()
        val s = row(guid) ?: return null
        val el = element(guid) ?: return null
        if (s.isKnowledge || text.isEmpty() || !hasRating || el.child("specs")?.children("spec").orEmpty().isNotEmpty()) return null
        val doc = file.doc
        val id = java.util.UUID.randomUUID().toString()
        val spec = doc.createElement("spec")
        for ((t, v) in listOf("guid" to id, "name" to text, "free" to "False", "expertise" to "False")) spec.appendChild(doc.createElement(t).also { it.textContent = v })
        (el.child("specs") ?: doc.createElement("specs").also { el.appendChild(it) }).appendChild(spec)
        return id
    }

    /** Take a specialization off its skill. */
    fun remove(specGuid: String): Boolean {
        val spec = root.child("newskills")?.child("skills")?.children("skill").orEmpty().flatMap { it.child("specs")?.children("spec").orEmpty() }
            .firstOrNull { it.text("guid")?.trim().equals(specGuid, true) } ?: return false
        spec.parentNode.removeChild(spec)
        return true
    }

    /** What each active skill (by name) has and could take, for the creation sheet. */
    fun info(ratedNames: Set<String>): Map<String, SkillSpecInfo> {
        val improvements = Improvement.parseAll(root)
        return file.skills().filter { !it.isKnowledge && it.guid.isNotEmpty() }.associate { s ->
            val name = s.name.ifEmpty { data[s.suid]?.element?.child("name")?.textContent?.trim().orEmpty() }
            val spec = element(s.guid)?.child("specs")?.children("spec").orEmpty().firstOrNull()
            name to SkillSpecInfo(s.guid, spec?.text("guid")?.trim().orEmpty(), spec?.text("name")?.trim().orEmpty(), spec == null && name in ratedNames, choices(s.guid, improvements))
        }
    }
}
