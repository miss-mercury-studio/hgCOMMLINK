package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.data.CharacterSettings
import chummer.core.text

/**
 * When a skill group is "broken", as Chummer decides it (`SkillGroup.BaseUnbroken` and `KarmaUnbroken`). A skill group is raised as a
 * unit, so it stops being a unit once its member skills have different ratings of their own. Chummer then locks the group: its points
 * can no longer be raised, and its karma stops counting toward the member skills.
 *
 *  - Karma is unbroken while every member's own base + karma is at least the highest own base among the members.
 *  - Points (Priority and Sum-to-Ten builds only) are unbroken while no member has base points of its own; with the setting
 *    "use points on broken groups" they follow the karma rule instead.
 *  - With "strict skill groups in creation" a character in creation keeps a group unbroken only while no member has any points or karma.
 */
object SkillGroupRules {
    /** A member skill's own points: stored base and karma, and the free ones its improvements give. */
    data class Member(val base: Int, val freeBase: Int, val karma: Int, val freeKarma: Int)

    data class State(val baseUnbroken: Boolean, val karmaUnbroken: Boolean) {
        /** Neither points nor karma can raise the group. */
        val locked get() = !baseUnbroken && !karmaUnbroken
    }

    fun state(members: List<Member>, priorityBuild: Boolean, creation: Boolean, settings: CharacterSettings?): State {
        if (members.isEmpty()) return State(baseUnbroken = false, karmaUnbroken = false)
        val strict = creation && (settings?.bool("strictskillgroupsincreatemode") ?: false)
        val untouched = members.all { it.base + it.freeBase <= 0 } && members.all { it.karma + it.freeKarma <= 0 }
        val high = members.maxOf { it.base + it.freeBase }
        val karmaUnbroken = if (strict) untouched else members.all { it.base + it.freeBase + it.karma + it.freeKarma >= high }
        val baseUnbroken = when {
            !priorityBuild -> false
            strict -> untouched
            settings?.bool("usepointsonbrokengroups") == true -> karmaUnbroken
            else -> members.all { it.base + it.freeBase <= 0 }
        }
        return State(baseUnbroken, karmaUnbroken)
    }

    private val PRIORITY_BUILDS = setOf("Priority", "SumtoTen")

    /** The state of one group of a character file; [set] holds the character's improvements (the free points come from them). */
    fun stateOf(c: CharacterFile, data: GameData, settings: CharacterSettings?, set: ImprovementSet, group: String): State {
        val created = c.root.text("created")?.trim().equals("True", true)
        val members = c.skills().filter { !it.isKnowledge && data[it.suid]?.element?.child("skillgroup")?.textContent?.trim() == group }.map { s ->
            val name = s.name.ifEmpty { data[s.suid]?.name.orEmpty() }
            Member(s.base, standardRound(set.valueOf("SkillBase", name)), s.karma, standardRound(set.valueOf("SkillLevel", name)))
        }
        return state(members, c.root.text("buildmethod")?.trim() in PRIORITY_BUILDS, !created, settings)
    }

    fun stateOf(c: CharacterFile, data: GameData, settings: CharacterSettings?, group: String): State {
        val created = c.root.text("created")?.trim().equals("True", true)
        return stateOf(c, data, settings, ImprovementSet(Improvement.parseAll(c.root), created), group)
    }
}
