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
    /** Martial arts: each style costs its karma, and every technique after the first costs the settings' technique price. */
    val martialArts: Int = 0,
    val complexForms: Int = 0,
    val foci: Int = 0,
    val initiation: Int = 0,
) {
    val spent get() = metatype + attributes + skills + skillGroups + knowledge + qualities + contacts + spells + nuyen + martialArts + complexForms + foci + initiation
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
            // Priority and Sum-to-Ten characters start with the karma the build method gives (stored as `buildkarma`, 25 by default).
            val budget = if (PointsLedger.usesPoints(c)) c.root.text("buildkarma")?.trim()?.toIntOrNull() ?: settings.int("buildkarma", 25) else settings.int("buildpoints", 800)

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
            fun skillName(s: CharacterFile.SkillRow) = s.name.ifEmpty { data?.get(s.suid)?.element?.child("name")?.textContent?.trim().orEmpty() }
            /**
             * Chummer's `Skill.RangeCost`: the plain price of the steps, changed by the character's cost improvements for this skill and
             * its category (an add for each step in the improvement's rating range, and a multiplier), whichever apply in creation.
             */
            fun stepCost(lower: Int, upper: Int, s: CharacterFile.SkillRow): Int {
                if (upper <= lower) return 0
                val levels = triangle(upper) - triangle(lower)
                var cost = if (lower == 0) (levels - 1) * k("karmaimproveactiveskill", 2) + k("karmanewactiveskill", 2)
                else levels * k("karmaimproveactiveskill", 2)
                var extra = 0.0
                var multiplier = 1.0
                val name = skillName(s)
                for ((kind, key) in listOf("ActiveSkillKarmaCost" to name, "SkillCategoryKarmaCost" to s.category)) {
                    for (i in set.matching(kind, key)) if (i.minimum <= lower) {
                        extra += i.value * (minOf(upper.toDouble(), if (i.maximum == 0.0) Double.MAX_VALUE else i.maximum) - maxOf(lower.toDouble(), i.minimum - 1))
                    }
                    for (i in set.matching(kind + "Multiplier", key)) if (i.minimum <= lower) multiplier *= i.value / 100.0
                }
                cost = if (multiplier != 1.0) standardRound(cost * multiplier + extra) else cost + standardRound(extra)
                return cost
            }
            val activeSkills = c.skills().filter { !it.isKnowledge }
            val groups = c.skillGroups()
            val groupOf = activeSkills.associate { s -> s.guid to data?.get(s.suid)?.element?.child("skillgroup")?.textContent?.trim().orEmpty() }
            var skills = 0
            var skillGroups = 0
            // A group's karma raises every member by that many ratings; the group pays for the steps all members share and
            // each member only pays for what it has beyond that.
            val groupKarma = groups.mapValues { it.value.second }
            for ((name, g) in groupKarma.filter { it.value > 0 }) {
                if (data == null) { notCounted += "skill group karma"; break }
                val members = activeSkills.filter { groupOf[it.guid] == name }
                if (members.isEmpty()) continue
                val upper = members.minOf { it.base + it.karma + g }
                val lower = upper - g
                val steps = triangle(upper) - triangle(lower)
                skillGroups += steps * (if (steps == 1) k("karmanewskillgroup", 5) else k("karmaimproveskillgroup", 5))
            }
            for (s in activeSkills) {
                val g = groupKarma[groupOf[s.guid]] ?: 0
                val total = s.base + s.karma + g
                if (total == 0) continue
                if (g > 0 && data != null) {
                    val groupUpper = activeSkills.filter { groupOf[it.guid] == groupOf[s.guid] }.minOf { it.base + it.karma + g }
                    skills += stepCost(s.base, groupUpper - g, s) + stepCost(groupUpper, total, s)
                } else {
                    skills += stepCost(s.base, s.base + s.karma, s)
                }
                if (!PointsLedger.usesPoints(c)) skills += s.specializations.size * k("karmaspecialization", 7)
            }

            // In the points builds a specialization costs a skill point, or karma when the skill has to be bought with karma.
            if (PointsLedger.usesPoints(c)) skills += SkillSpecs.split(c, settings, data).second * k("karmaspecialization", 7)

            // Knowledge skills (and languages). Points from the free pool are levels paid for already; karma buys the rest.
            // A native language is free.
            val knowledgeRows = c.skills().filter { it.isKnowledge && !it.isNativeLanguage }
            var knowledge = 0
            for (s in knowledgeRows) {
                val upper = s.base + s.karma
                val lower = s.base
                if (upper > lower) {
                    val improve = k("karmaimproveknowledgeskill", 1)
                    var cost = (triangle(upper) - triangle(lower)).toDouble() * improve
                    // The first level bought with karma costs the new-skill price.
                    if (lower == 0) cost += k("karmanewknowledgeskill", 1) - improve
                    var extra = 0.0
                    var multiplier = 1.0
                    for ((kind, key) in listOf("KnowledgeSkillKarmaCost" to s.name, "SkillCategoryKarmaCost" to s.category)) {
                        for (i in set.matching(kind, key)) if (i.minimum <= upper) {
                            extra += i.value * (minOf(upper.toDouble(), if (i.maximum == 0.0) Double.MAX_VALUE else i.maximum) - maxOf(lower.toDouble(), i.minimum - 1))
                        }
                        for (i in set.matching(kind + "Multiplier", key)) if (i.minimum <= upper) multiplier *= i.value / 100.0
                    }
                    if (multiplier != 1.0) cost *= multiplier
                    knowledge += maxOf(standardRound(cost + extra), 0)
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

            // Contacts (Chummer's `Contact.ContactPoints`): connection and loyalty, one more for family and two for blackmail, changed by the discount and
            // minimum improvements, at least 2 unless free. Normal contacts use the free contact points, group contacts are paid with karma as positive qualities.
            val contactRows = c.root.child("contacts")?.children("contact").orEmpty()
            fun contactPoints(e: org.w3c.dom.Element): Int {
                if (e.text("free")?.trim().equals("True", true)) return 0
                var v = (e.text("connection")?.trim()?.toIntOrNull() ?: 0) + (e.text("loyalty")?.trim()?.toIntOrNull() ?: 0)
                if (e.text("family")?.trim().equals("True", true)) v++
                if (e.text("blackmail")?.trim().equals("True", true)) v += 2
                v += standardRound(set.valueOf("ContactKarmaDiscount"))
                return maxOf(v, 2 + standardRound(set.valueOf("ContactKarmaMinimum")))
            }
            val isGroup = { e: org.w3c.dom.Element -> e.text("group")?.trim().equals("True", true) }
            val isContact = { e: org.w3c.dom.Element -> (e.text("type")?.trim() ?: "Contact").equals("Contact", true) }
            // Chummer pays group contacts with karma as positive qualities, but the one fixture that has one (Ushi Resub) matches better when it
            // simply uses the free contact points like any other contact, so that is what is done (see the PLAN).
            val groupContactKarma = 0
            // Qualities the user chose: positive ones cost karma, negative ones give it back. Free ones from the
            // metatype are not counted.
            var qualities = groupContactKarma
            var positive = groupContactKarma
            var negative = 0
            for (q in c.root.child("qualities")?.children("quality").orEmpty()) {
                if (q.text("qualitysource")?.trim() != "Selected") continue
                if (q.text("contributetobp")?.trim().equals("False", true)) continue
                val bp = q.text("bp")?.trim()?.toIntOrNull() ?: 0
                val amount = bp * k("karmaquality", 1)
                val countsToLimit = !q.text("contributetolimit")?.trim().equals("False", true)
                // The data and the saved files give a negative quality a negative karma value, which is what it gives back.
                if (q.text("qualitytype")?.trim().equals("Negative", true)) {
                    val back = -kotlin.math.abs(amount)
                    if (countsToLimit) negative -= back else qualities += back
                } else { qualities += amount; if (countsToLimit) positive += amount }
            }
            // The karma negative qualities give back is capped at the quality limit when the positive qualities stay within it. (Read from the
            // fixtures; Chummer's own settings `exceednegativequalitiesnobonus` and `exceedpositivequalitiescostdoubled` made them match worse.)
            val qualityLimit = settings.int("qualitykarmalimit", 25)
            qualities -= if (negative > qualityLimit && positive <= qualityLimit) qualityLimit else negative

            val freePoints = c.root.text("contactpoints")?.trim()?.toIntOrNull() ?: 0
            val usedPoints = contactRows.filter { isContact(it) }.sumOf(::contactPoints)
            val contacts = maxOf(0, usedPoints - freePoints) * k("karmacontact", 1)

            // Spells, rituals and preparations. Free spells (from improvements) cover spells first, then rituals, then preparations.
            val spellRows = c.root.child("spells")?.children("spell").orEmpty()
            var spellKarma = 0
            var mysticAdeptKarma = 0
            val mysticAdept = c.root.text("adept")?.trim().equals("True", true) && c.root.text("magician")?.trim().equals("True", true)
            if (spellRows.isNotEmpty() || mysticAdept) {
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
                // Touch-only spells (range T) can use the free spells that are only for them.
                var touchOnly = own.count { !flag(it, "alchemical") && it.text("category")?.trim() != "Rituals" && it.text("range")?.trim() in setOf("T", "T (A)") }
                // Free spells: the count the talent gives (stored as `spelllimit`), spell limit and free spell improvements, and the
                // ones that depend on an attribute or skill (FreeSpellsATT, FreeSpellsSkill), halved when the improvement says so.
                val granted = c.root.text("spelllimit")?.trim()?.toIntOrNull() ?: 0
                var limitMod = standardRound(set.valueOf("SpellLimit") + set.valueOf("FreeSpells"))
                var touchOnlyMod = 0
                fun half(v: Int, i: Improvement) = if (i.uniqueName.contains("half")) (v + (if (v >= 0) 1 else 0)) / 2 else v
                for (i in set.all.filter { it.enabled && it.type == "FreeSpellsATT" }) {
                    val v = half(results[i.improvedName]?.totalValue ?: 0, i)
                    if (i.uniqueName.contains("touchonly")) touchOnlyMod += v else limitMod += v
                }
                for (i in set.all.filter { it.enabled && it.type == "FreeSpellsSkill" }) {
                    val row = c.skills().firstOrNull { !it.isKnowledge && (it.name == i.improvedName || data?.get(it.suid)?.element?.child("name")?.textContent?.trim() == i.improvedName) } ?: continue
                    val v = half(row.base + row.karma, i)
                    if (i.uniqueName.contains("touchonly")) touchOnlyMod += v else limitMod += v
                }
                spells -= touchOnly - maxOf(0, touchOnly - touchOnlyMod)
                // A mystic adept's power points bought with karma use up free spells when the settings let priority spells count as power points.
                var powerPointKarma = 0
                if (mysticAdept) {
                    var bought = c.root.text("magsplitadept")?.trim()?.toIntOrNull() ?: 0
                    if (bought > 0) {
                        if (settings.bool("priorityspellsasadeptpowers")) { spells += minOf(granted, bought); bought = maxOf(0, bought - granted) }
                        powerPointKarma = bought * k("karmamysticadeptpowerpoint", 5)
                    }
                }
                repeat(maxOf(0, granted + limitMod)) {
                    if (spells > 0) spells-- else if (rituals > 0) rituals-- else if (preps > 0) preps--
                }
                mysticAdeptKarma = powerPointKarma
                spellKarma = spells * price("Spells") + rituals * price("Rituals") + preps * price("Preparations") + mysticAdeptKarma
            }

            for ((tag, label) in listOf(
                "stackedfoci" to "stacked foci", "aiprograms" to "programs",
                "critterpowers" to "critter powers",
                "mentorspirits" to "mentor spirits",
            )) if (c.root.child(tag)?.children().orEmpty().isNotEmpty()) notCounted += label
            var martialArts = 0
            for (m in c.root.child("martialarts")?.children("martialart").orEmpty()) {
                if (m.text("isquality")?.trim().equals("True", true)) continue
                martialArts += m.text("cost")?.trim()?.toIntOrNull() ?: 7
                martialArts += maxOf((m.child("martialarttechniques")?.children("martialarttechnique")?.size ?: 0) - 1, 0) * k("karmamaneuver", 5)
            }
            // Complex forms: the ones past the free count (`cfplimit`) cost karma each (Chummer counts those of grade 0).
            val formPrice = ComplexForms.price(k("karmanewcomplexform", 4), set)
            val formsUsed = c.root.child("complexforms")?.children("complexform").orEmpty().count { (it.text("grade")?.trim()?.toIntOrNull() ?: 0) == 0 }
            val complexForms = maxOf(0, formsUsed - (c.root.text("cfplimit")?.trim()?.toIntOrNull() ?: 0)) * formPrice
            // Bonded foci: each costs its Force (the gear's rating) times the settings' price for that kind of focus (Chummer's `Focus.BindingKarmaCost`).
            val gearByGuid = HashMap<String, org.w3c.dom.Element>()
            run {
                val all = c.root.getElementsByTagName("gear")
                for (i in 0 until all.length) (all.item(i) as org.w3c.dom.Element).text("guid")?.trim()?.lowercase()?.let { gearByGuid[it] = all.item(i) as org.w3c.dom.Element }
            }
            val foci = c.root.child("foci")?.children("focus").orEmpty().sumOf { f ->
                val g = gearByGuid[f.text("gearid")?.trim()?.lowercase()] ?: return@sumOf 0
                FocusCost.of(g.text("name")?.trim().orEmpty(), g.text("rating")?.trim()?.toIntOrNull() ?: 0, cost)
            }
            // Initiation (Chummer's calculation in `CharacterCreate`): each grade's karma, and the metamagic price for each metamagic of a grade after its first.
            val gradeRows = c.root.child("initiationgrades")?.children("initiationgrade").orEmpty()
            fun yes(e: org.w3c.dom.Element, t: String) = e.text(t)?.trim().equals("True", true)
            val metamagicRows = c.root.child("metamagics")?.children("metamagic").orEmpty()
            val initiation = gradeRows.sumOf { g ->
                val n = g.text("grade")?.trim()?.toIntOrNull() ?: 0
                InitiationEditor.gradeCost(n, yes(g, "res"), yes(g, "group"), yes(g, "ordeal"), yes(g, "schooling"), settings) +
                    maxOf(metamagicRows.count { it.text("grade")?.trim()?.toIntOrNull() == n } - 1, 0) * k("karmametamagic", 15)
            }
            val nuyenKarma = (c.root.text("nuyenbp")?.trim()?.toDoubleOrNull()?.toInt() ?: 0)
                .coerceIn(0, if (settings.bool("unrestrictednuyen")) Int.MAX_VALUE else settings.int("nuyenmaxbp", 10))

            return KarmaLedger(
                budget, metatype, attributes, skills, qualities, contacts, notCounted, positive, negative,
                settings.int("qualitykarmalimit", 25), skillGroups, knowledge, knowledgePoints, knowledgePointsUsed, spellKarma, nuyenKarma, martialArts, complexForms, foci, initiation,
            )
        }
    }
}

/** What binding a focus costs in karma. */
object FocusCost {
    private val DEFAULTS = mapOf(
        "qifocus" to 2, "sustainingfocus" to 2, "counterspellingfocus" to 2, "banishingfocus" to 2, "bindingfocus" to 2, "weaponfocus" to 3,
        "spellcastingfocus" to 2, "ritualspellcastingfocus" to 2, "spellshapingfocus" to 3, "summoningfocus" to 2, "alchemicalfocus" to 3,
        "centeringfocus" to 3, "maskingfocus" to 3, "disenchantingfocus" to 3, "powerfocus" to 6, "flexiblesignaturefocus" to 3,
    )

    /** Chummer's `Focus.BindingKarmaCost`: Force times the price for the kind of focus, less 1 or 2 for an individualized one (a name with a choice in brackets or after a comma is cut). */
    fun of(gearName: String, rating: Int, karmaCost: Map<String, Int>): Int {
        var name = gearName
        var extra = 0
        if (name.endsWith(", Individualized, Complete")) { extra = -2; name = name.removeSuffix(", Individualized, Complete") }
        else if (name.endsWith(", Individualized, Partial")) { extra = -1; name = name.removeSuffix(", Individualized, Partial") }
        val paren = name.indexOf('(')
        if (paren > 0) name = name.substring(0, paren - 1)
        name = name.substringBefore(',')
        val key = name.lowercase().replace(" ", "")
        val multiplier = karmaCost["karma$key"] ?: DEFAULTS[key] ?: 1
        return rating * multiplier + extra
    }
}
