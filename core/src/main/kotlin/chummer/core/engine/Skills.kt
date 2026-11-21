package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData

/** One skill's calculated figures. */
data class SkillResult(
    val name: String,
    val attribute: String,
    val rating: Int,
    /** The general-purpose dice pool; 0 if the skill is disabled or cannot be used. */
    val pool: Int,
    val enabled: Boolean,
    val canDefault: Boolean,
    val ratingMaximum: Int,
)

/**
 * Active and knowledge skill calculation, a port of Chummer's `Skill` (Skill.cs).
 *
 * Rating = max(cyberware rating, learned rating + rating modifiers), where learned rating is
 * base + karma (each including free levels from improvements and the skill group's points).
 * Pool = attribute + rating + pool modifiers + wound and sustaining penalties, or the default
 * pool (attribute - 1) for an unrated skill that can default.
 *
 * Not yet covered: skillsofts and activesofts (cyberware rating), conditional improvements,
 * specialization bonuses (shown separately from the general pool), reflex recorder, swapped attributes,
 * skill groups that are broken, and the "sapience" rule for critters.
 */
class SkillEngine(
    private val improvements: ImprovementSet,
    private val attributes: Map<String, AttributeResult>,
    private val settings: RuleSettings,
    private val isCreated: Boolean,
    private val magicEnabled: Boolean,
    private val resonanceEnabled: Boolean,
    private val isCritter: Boolean = false,
    /** Wound and sustaining penalties are negative numbers added to every pool. */
    private val woundModifier: Int = 0,
    private val sustainingPenalty: Int = 0,
) {
    /** A skill as stored plus what the game data says about it. */
    data class Raw(
        val name: String,
        val category: String,
        val attribute: String,
        val group: String,
        val canDefaultByData: Boolean,
        val basePoints: Int,
        val karmaPoints: Int,
        val isKnowledge: Boolean,
        val isNativeLanguage: Boolean = false,
    )

    private fun relevant(r: Raw, attribute: String, includeConditionals: Boolean = false): List<Improvement> =
        improvements.all.filter { i ->
            if (!i.enabled) return@filter false
            if (!includeConditionals && i.condition.isNotBlank()) return@filter false
            when (i.type) {
                "Skill", "SkillDisable", "SkillEnableMovement" -> i.improvedName == r.name
                "SkillGroup", "SkillGroupDisable" ->
                    i.improvedName == r.group && r.group.isNotEmpty() && !excludes(i, r.name) && !excludes(i, r.category)
                "SkillCategory" -> i.improvedName == r.category && !excludes(i, r.name)
                "SkillAttribute" -> i.improvedName == attribute && !excludes(i, r.name)
                "SkillLinkedAttribute" -> i.improvedName == r.attribute && !excludes(i, r.name)
                "BlockSkillDefault" -> i.improvedName.isEmpty() || i.improvedName == r.name
                "AllowSkillDefault" -> i.improvedName.isEmpty() || i.improvedName == r.name
                "BlockSkillCategoryDefault" -> i.improvedName == r.category
                "BlockSkillGroupDefault" -> i.improvedName == r.group && r.group.isNotEmpty()
                "EnhancedArticulation" -> r.category == "Physical Active" && r.attribute in PHYSICAL
                else -> false
            }
        }

    /** Chummer's `Exclude` is a text list; a skill or category is excluded if its name appears in it. */
    private fun excludes(i: Improvement, name: String) = name.isNotEmpty() && i.exclude.contains(name)

    private fun bonus(r: Raw, addToRating: Boolean, attribute: String): Int =
        standardRound(relevant(r, attribute).filter { it.type in BONUS_TYPES && it.addToRating == addToRating }.sumOf { it.value })

    fun ratingMaximum(r: Raw): Int {
        val other = relevant(r, r.attribute).filter { it.type == "Skill" }.sumOf { it.maximum }.let(::standardRound)
        val base = when {
            r.isKnowledge && !isCreated -> settings.maxKnowledgeSkillRatingCreate
            r.isKnowledge -> settings.maxKnowledgeSkillRating
            !isCreated -> settings.maxSkillRatingCreate
            else -> settings.maxSkillRating
        }
        return base + other
    }

    fun isEnabled(r: Raw): Boolean {
        if (relevant(r, r.attribute).any { it.type == "SkillDisable" || it.type == "SkillGroupDisable" }) return false
        if (r.attribute == "MAG" && !magicEnabled) return false
        if (r.attribute == "RES" && !resonanceEnabled) return false
        return true
    }

    fun canDefault(r: Raw, rating: Int): Boolean {
        if (relevant(r, r.attribute).any { it.type == "BlockSkillDefault" || it.type == "BlockSkillCategoryDefault" || it.type == "BlockSkillGroupDefault" }) return false
        if (relevant(r, r.attribute).none { it.type == "AllowSkillDefault" }) {
            if (!r.canDefaultByData) return false
            if (isCritter && rating == 0) return false
        }
        return true
    }

    private fun defaultModifier(r: Raw): Int {
        fun removes(type: String, name: String) = name.isNotEmpty() && improvements.matching(type, name).isNotEmpty()
        if (removes("RemoveSkillCategoryDefaultPenalty", r.category)) return 0
        if (removes("RemoveSkillCategoryDefaultPenalty", r.group)) return 0
        if (removes("RemoveSkillDefaultPenalty", r.name)) return 0
        return -1
    }

    /** [groupBase] and [groupKarma] are the skill group's own stored points, if the skill belongs to a group. */
    fun calculate(r: Raw, groupBase: Int = 0, groupKarma: Int = 0): SkillResult {
        val max = ratingMaximum(r)
        val freeBase = if (r.name.isEmpty()) 0 else standardRound(improvements.valueOf("SkillBase", r.name))
        val freeKarma = if (r.name.isEmpty()) 0 else standardRound(improvements.valueOf("SkillLevel", r.name))
        val base = minOf(r.basePoints + freeBase + groupBase, max)
        val karma = minOf(r.karmaPoints + freeKarma + groupKarma, max)
        val rating = (base + karma) + bonus(r, true, r.attribute)
        val enabled = isEnabled(r)

        val attr = attributes[r.attribute]?.totalValue ?: 0
        val modifiers = bonus(r, false, r.attribute)
        val wound = woundModifier + sustainingPenalty
        val pool = when {
            r.isNativeLanguage -> Int.MAX_VALUE
            !enabled -> 0
            attr <= 0 -> 0
            rating > 0 -> maxOf(0, rating + attr + modifiers + wound)
            canDefault(r, rating) -> maxOf(0, attr + modifiers + defaultModifier(r) + wound)
            else -> 0
        }
        return SkillResult(r.name, r.attribute, rating, pool, enabled, canDefault(r, rating), max)
    }

    companion object {
        private val PHYSICAL = setOf("BOD", "AGI", "REA", "STR")
        private val BONUS_TYPES = setOf(
            "Skill", "SkillGroup", "SkillCategory", "SkillAttribute", "SkillLinkedAttribute", "EnhancedArticulation",
        )

        /** Attribute linked to a knowledge skill, by its type. */
        fun knowledgeAttribute(type: String) = when (type) {
            "Academic", "Professional" -> "LOG"
            else -> "INT"
        }

        /** Build the engine's inputs from a character file and the game data. */
        fun skillsOf(c: CharacterFile, data: GameData): List<Raw> {
            val out = ArrayList<Raw>()
            for (s in c.skills()) {
                val entry = data[s.suid]
                val el = entry?.element
                val attribute = el?.getElementsByTagName("attribute")?.item(0)?.textContent?.trim().orEmpty()
                val group = el?.getElementsByTagName("skillgroup")?.item(0)?.textContent?.trim().orEmpty()
                val def = el?.getElementsByTagName("default")?.item(0)?.textContent?.trim().equals("True", true)
                if (s.isKnowledge) {
                    out += Raw(s.name, s.category, knowledgeAttribute(s.type), "", true, s.base, s.karma, true, s.isNativeLanguage)
                } else {
                    out += Raw(s.name, s.category.ifEmpty { entry?.category.orEmpty() }, attribute, group, def, s.base, s.karma, false)
                }
            }
            return out
        }
    }
}
