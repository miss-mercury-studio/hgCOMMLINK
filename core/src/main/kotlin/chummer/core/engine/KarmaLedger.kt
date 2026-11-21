package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text

/**
 * How much karma a Karma-build character has spent and has left, ported from Chummer's creation form
 * (`CalculateBPandRefreshBPDisplays`) and the cost rules of attributes, skills, skill groups and qualities.
 * Chummer does not store the remaining karma, so it is always calculated.
 *
 * Covered: the metatype, attributes, active skills (with specializations), qualities and contacts.
 * Not covered, and reported in [notCounted] when the character has any: group karma, knowledge skills,
 * spells, foci, complex forms, programs, martial arts, initiation, critter powers, nuyen converted to karma
 * and gear bought with karma. These are left out of [spent], so [remaining] is then an upper bound.
 */
data class KarmaLedger(
    val budget: Int,
    val metatype: Int,
    val attributes: Int,
    val skills: Int,
    val qualities: Int,
    val contacts: Int,
    val notCounted: List<String>,
) {
    val spent get() = metatype + attributes + skills + qualities + contacts
    val remaining get() = budget - spent

    companion object {
        private fun triangle(n: Int) = n * (n + 1) / 2

        fun of(c: CharacterFile, settings: CharacterSettings): KarmaLedger {
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

            // Active skills: a new skill costs the new-skill price for its first rating, then each further rating costs
            // the improve price times that rating. Specializations cost a flat amount each.
            var skills = 0
            for (s in c.skills().filter { !it.isKnowledge }) {
                val upper = s.base + s.karma
                if (upper == 0) continue
                val lower = s.base
                if (upper > lower) {
                    val levels = triangle(upper) - triangle(lower)
                    skills += if (lower == 0) (levels - 1) * k("karmaimproveactiveskill", 2) + k("karmanewactiveskill", 2)
                    else levels * k("karmaimproveactiveskill", 2)
                }
                skills += s.specializations.size * k("karmaspecialization", 7)
            }
            if (c.skillGroups().values.any { it.second > 0 }) notCounted += "skill group karma"
            if (c.skills().any { it.isKnowledge && it.karma > 0 }) notCounted += "knowledge skills"

            // Qualities the user chose: positive ones cost karma, negative ones give it back. Free ones from the
            // metatype are not counted.
            var qualities = 0
            for (q in c.root.child("qualities")?.children("quality").orEmpty()) {
                if (q.text("qualitysource")?.trim() != "Selected") continue
                if (q.text("contributetobp")?.trim().equals("False", true)) continue
                val bp = q.text("bp")?.trim()?.toIntOrNull() ?: 0
                val amount = bp * k("karmaquality", 1)
                if (q.text("qualitytype")?.trim().equals("Negative", true)) qualities -= amount else qualities += amount
            }

            // Contacts: connection plus loyalty points beyond the free contact points cost karma.
            val contactRows = c.root.child("contacts")?.children("contact").orEmpty()
            val freePoints = c.root.text("contactpoints")?.trim()?.toIntOrNull() ?: 0
            val usedPoints = contactRows.filter { it.text("free")?.trim() != "True" }
                .sumOf { (it.text("connection")?.trim()?.toIntOrNull() ?: 0) + (it.text("loyalty")?.trim()?.toIntOrNull() ?: 0) }
            val contacts = maxOf(0, usedPoints - freePoints) * k("karmacontact", 1)
            if (contactRows.any { it.text("group")?.trim() == "True" }) notCounted += "group contacts"

            for ((tag, label) in listOf(
                "spells" to "spells", "foci" to "foci", "complexforms" to "complex forms", "aiprograms" to "programs",
                "martialarts" to "martial arts", "initiationgrades" to "initiation", "critterpowers" to "critter powers",
                "powers" to "adept powers", "metamagics" to "metamagics", "mentorspirits" to "mentor spirits",
            )) if (c.root.child(tag)?.children().orEmpty().isNotEmpty()) notCounted += label
            if ((c.root.text("nuyenbp")?.trim()?.toIntOrNull() ?: 0) != 0) notCounted += "nuyen bought with karma"

            return KarmaLedger(budget, metatype, attributes, skills, qualities, contacts, notCounted)
        }
    }
}
