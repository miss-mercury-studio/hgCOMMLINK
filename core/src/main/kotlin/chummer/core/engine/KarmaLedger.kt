package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.Expr
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text

/**
 * How much karma a Karma-build character has spent and has left, ported from Chummer's creation form
 * (`CalculateBPandRefreshBPDisplays`) and the cost rules of attributes, skills, skill groups, knowledge skills and
 * qualities. Chummer does not store the remaining karma, so it is always calculated.
 *
 * Covered: the metatype, attributes, active skills, skill groups, knowledge skills (with the free knowledge points),
 * qualities, contacts and spells. Not covered, and reported in [notCounted] when the character has any: specializations on
 * knowledge skills, free-spell rules beyond the plain free spells, foci, complex forms, programs, martial arts, initiation, critter powers, nuyen converted to
 * karma and gear bought with karma. These are left out of [spent], so [remaining] is then an upper bound.
 */
data class KarmaLedger(
    val budget: Int,
    val metatype: Int,
    val attributes: Int,
    val skills: Int,
    val qualities: Int,
    val contacts: Int,
    val notCounted: List<String>,
    /** Karma in positive and negative qualities the user chose, and the limit for each (Chummer shows e.g. 9/50). */
    val positiveQualities: Int = 0,
    val negativeQualities: Int = 0,
    val qualityLimit: Int = 25,
    val skillGroups: Int = 0,
    val knowledge: Int = 0,
    /** Free knowledge skill points: the pool the settings give, and how many are used. */
    val knowledgePoints: Int = 0,
    val knowledgePointsUsed: Int = 0,
    val spells: Int = 0,
    /** Karma put into nuyen (at most the settings' maximum). */
    val nuyen: Int = 0,
) {
    val spent get() = metatype + attributes + skills + skillGroups + knowledge + qualities + contacts + spells + nuyen
    val remaining get() = budget - spent
    val knowledgePointsLeft get() = knowledgePoints - knowledgePointsUsed

    /** What is wrong with the character as it stands; empty when nothing is. */
    val problems: List<String>
        get() = buildList {
            if (remaining < 0) add("Overspent by ${-remaining} karma")
            if (positiveQualities > qualityLimit) add("Positive qualities $positiveQualities/$qualityLimit karma")
            if (negativeQualities > qualityLimit) add("Negative qualities $negativeQualities/$qualityLimit karma")
            if (knowledgePointsUsed > knowledgePoints) add("Knowledge points $knowledgePointsUsed/$knowledgePoints")
        }

    companion object {
        private fun triangle(n: Int) = n * (n + 1) / 2

        /**
         * [data] is needed to know which skill group a skill belongs to; without it, group karma cannot be priced and
         * is reported as not counted.
         */
        fun of(c: CharacterFile, settings: CharacterSettings, data: GameData? = null): KarmaLedger {
            val rules = settings.toRuleSettings()
            val set = ImprovementSet(Improvement.parseAll(c.root), isCareer = false)
            val cost = settings.karmaCost
            fun k(key: String, default: Int) = cost[key] ?: default
            val notCounted = ArrayList<String>()

            // Stored in the settings as `buildpoints` (the Karma build budget; 800 in Chummer's own profiles, 700 in San Francisco Showdown).
            val budget = settings.int("buildpoints", 800)

            // Metatype (and metavariant) cost.
            val metatype = (c.root.text("metatypebp")?.trim()?.toIntOrNull() ?: 0) * settings.int("metatypecostskarmamultiplier", 1)

            // Attributes: the steps bought with karma, each costing its new rating times the attribute cost.
            val results = AttributeEngine.forFile(c, rules)
            var attributes = 0
            for (row in c.attributes()) {
                if (row.karma == 0) continue
                val base = (results[row.name]?.value ?: (row.min + row.base + row.karma)) - row.karma
                var amount = (2 * base + row.karma + 1) * row.karma / 2 * k("karmaattribute", 5)
                val value = base + row.karma
                var extra = 0.0
                var multiplier = 1.0
                for (i in set.matching("AttributeKarmaCost", row.name, unconditionalOnly = false)) {
                    if (i.minimum <= value) extra += i.value * (minOf(value.toDouble(), if (i.maximum == 0.0) Double.MAX_VALUE else i.maximum) - maxOf(base.toDouble(), i.minimum - 1))
                }
                for (i in set.matching("AttributeKarmaCostMultiplier", row.name, unconditionalOnly = false)) {
                    if (i.minimum <= value) multiplier *= i.value / 100.0
                }
                amount = if (multiplier != 1.0) standardRound(amount * multiplier + extra) else amount + standardRound(extra)
                attributes += maxOf(amount, 0)
            }

            // Active skills. The price of rating steps from [lower] to [upper]: a new skill's first rating costs the
            // new-skill price, every other step costs its rating times the improve price.
            fun stepCost(lower: Int, upper: Int): Int {
                if (upper <= lower) return 0
                val levels = triangle(upper) - triangle(lower)
                return if (lower == 0) (levels - 1) * k("karmaimproveactiveskill", 2) + k("karmanewactiveskill", 2)
                else levels * k("karmaimproveactiveskill", 2)
            }
            val activeSkills = c.skills().filter { !it.isKnowledge }
            val groups = c.skillGroups()
            val groupOf = activeSkills.associate { s -> s.name to data?.get(s.suid)?.element?.child("skillgroup")?.textContent?.trim().orEmpty() }
            var skills = 0
            var skillGroups = 0
            // A group's karma raises every member by that many ratings; the group pays for the steps all members share and
            // each member only pays for what it has beyond that.
            val groupKarma = groups.mapValues { it.value.second }
            for ((name, g) in groupKarma.filter { it.value > 0 }) {
                if (data == null) { notCounted += "skill group karma"; break }
                val members = activeSkills.filter { groupOf[it.name] == name }
                if (members.isEmpty()) continue
                val upper = members.minOf { it.base + it.karma + g }
                val lower = upper - g
                val steps = triangle(upper) - triangle(lower)
                skillGroups += steps * (if (steps == 1) k("karmanewskillgroup", 5) else k("karmaimproveskillgroup", 5))
            }
            for (s in activeSkills) {
                val g = groupKarma[groupOf[s.name]] ?: 0
                val total = s.base + s.karma + g
                if (total == 0) continue
                if (g > 0 && data != null) {
                    val groupUpper = activeSkills.filter { groupOf[it.name] == groupOf[s.name] }.minOf { it.base + it.karma + g }
                    skills += stepCost(s.base, groupUpper - g) + stepCost(groupUpper, total)
                } else {
                    skills += stepCost(s.base, s.base + s.karma)
                }
                skills += s.specializations.size * k("karmaspecialization", 7)
            }

            // Knowledge skills (and languages). Points from the free pool are levels paid for already; karma buys the rest.
            // A native language is free.
            val knowledgeRows = c.skills().filter { it.isKnowledge && !it.isNativeLanguage }
            var knowledge = 0
            for (s in knowledgeRows) {
                val upper = s.base + s.karma
                val lower = s.base
                if (upper > lower) {
                    val improve = k("karmaimproveknowledgeskill", 1)
                    knowledge += (triangle(upper) - triangle(lower)) * improve
                    if (lower == 0) knowledge += k("karmanewknowledgeskill", 1) - improve
                }
                if (s.specializations.isNotEmpty() && "knowledge specializations" !in notCounted) notCounted += "knowledge specializations"
            }
            val unaug = results.mapValues { it.value.value.toDouble() }
            val knowledgePoints = runCatching {
                standardRound(Expr.eval(settings.string("knowledgepointsexpression", "({INTUnaug} + {LOGUnaug}) * 2"), mapOf(
                    "INTUnaug" to (unaug["INT"] ?: 1.0), "LOGUnaug" to (unaug["LOG"] ?: 1.0),
                )))
            }.getOrDefault(0)
            val knowledgePointsUsed = knowledgeRows.sumOf { it.base }

            // Qualities the user chose: positive ones cost karma, negative ones give it back. Free ones from the
            // metatype are not counted.
            var qualities = 0
            var positive = 0
            var negative = 0
            for (q in c.root.child("qualities")?.children("quality").orEmpty()) {
                if (q.text("qualitysource")?.trim() != "Selected") continue
                if (q.text("contributetobp")?.trim().equals("False", true)) continue
                val bp = q.text("bp")?.trim()?.toIntOrNull() ?: 0
                val amount = bp * k("karmaquality", 1)
                val countsToLimit = !q.text("contributetolimit")?.trim().equals("False", true)
                if (q.text("qualitytype")?.trim().equals("Negative", true)) { qualities -= amount; if (countsToLimit) negative += amount }
                else { qualities += amount; if (countsToLimit) positive += amount }
            }

            // Contacts: connection plus loyalty points beyond the free contact points cost karma.
            val contactRows = c.root.child("contacts")?.children("contact").orEmpty()
            val freePoints = c.root.text("contactpoints")?.trim()?.toIntOrNull() ?: 0
            val usedPoints = contactRows.filter { it.text("free")?.trim() != "True" }
                .sumOf { (it.text("connection")?.trim()?.toIntOrNull() ?: 0) + (it.text("loyalty")?.trim()?.toIntOrNull() ?: 0) }
            val contacts = maxOf(0, usedPoints - freePoints) * k("karmacontact", 1)
            if (contactRows.any { it.text("group")?.trim() == "True" }) notCounted += "group contacts"

            // Spells, rituals and preparations. Free spells (from improvements) cover spells first, then rituals, then preparations.
            val spellRows = c.root.child("spells")?.children("spell").orEmpty()
            var spellKarma = 0
            if (spellRows.isNotEmpty()) {
                fun price(category: String): Int {
                    var v = k("karmaspell", 5).toDouble()
                    val forCategory = { i: Improvement -> i.enabled && (i.improvedName == category || i.improvedName.isEmpty()) }
                    v += set.all.filter { it.type == "NewSpellKarmaCost" && forCategory(it) }.sumOf { it.value * it.rating }
                    var multiplier = 1.0
                    set.all.filter { it.type == "NewSpellKarmaCostMultiplier" && forCategory(it) }.forEach { multiplier *= it.value / 100.0 }
                    if (multiplier != 1.0) v *= multiplier
                    return maxOf(standardRound(v), 0)
                }
                fun flag(e: org.w3c.dom.Element, tag: String) = e.text(tag)?.trim().equals("True", true)
                val own = spellRows.filter { (it.text("grade")?.trim()?.toIntOrNull() ?: 0) == 0 && !flag(it, "freebonus") }
                var spells = own.count { !flag(it, "alchemical") && it.text("category")?.trim() != "Rituals" }
                var rituals = own.count { !flag(it, "alchemical") && it.text("category")?.trim() == "Rituals" }
                var preps = own.count { flag(it, "alchemical") }
                repeat(maxOf(0, standardRound(set.valueOf("FreeSpells")))) {
                    if (spells > 0) spells-- else if (rituals > 0) rituals-- else if (preps > 0) preps--
                }
                spellKarma = spells * price("Spells") + rituals * price("Rituals") + preps * price("Preparations")
                if (listOf("FreeSpellsATT", "FreeSpellsSkill", "SpellLimit").any { set.all.any { i -> i.enabled && i.type == it } }) notCounted += "free spell rules"
            }

            for ((tag, label) in listOf(
                "foci" to "foci", "complexforms" to "complex forms", "aiprograms" to "programs",
                "martialarts" to "martial arts", "initiationgrades" to "initiation", "critterpowers" to "critter powers",
                "powers" to "adept powers", "metamagics" to "metamagics", "mentorspirits" to "mentor spirits",
            )) if (c.root.child(tag)?.children().orEmpty().isNotEmpty()) notCounted += label
            val nuyenKarma = (c.root.text("nuyenbp")?.trim()?.toDoubleOrNull()?.toInt() ?: 0)
                .coerceIn(0, if (settings.bool("unrestrictednuyen")) Int.MAX_VALUE else settings.int("nuyenmaxbp", 10))

            return KarmaLedger(
                budget, metatype, attributes, skills, qualities, contacts, notCounted, positive, negative,
                settings.int("qualitykarmalimit", 25), skillGroups, knowledge, knowledgePoints, knowledgePointsUsed, spellKarma, nuyenKarma,
            )
        }
    }
}
