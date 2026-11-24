package chummer.core.engine

import chummer.core.Expr
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/** Where a bonus comes from and what it needs to know about its item. */
class BonusContext(
    /** The improvement source name Chummer stores, for example "Quality", "Cyberware", "ArmorMod". */
    val source: String,
    /** The GUID of the item that grants the bonus. */
    val sourceName: String,
    /** The item rating (or level, for qualities). */
    val rating: Int,
    /** The value the user picked for a "select" node (Chummer's selected value, saved as the item's `extra`). */
    val choice: String = "",
    /** The item name shown to the user; limit modifiers use it as their unique name. */
    val friendlyName: String = "",
    /** Looks up a text from Chummer's language file by key (conditions such as `LimitCondition_...`). */
    val translate: (String) -> String? = { null },
    /** Adds a quality named by a bonus (for example Glamour adds Distinctive Style) and returns its GUID, or null if it cannot. */
    val addQuality: (name: String, extra: String) -> String? = { _, _ -> null },
    /** Creates the objects some bonuses add to the character (gear, a mentor spirit, a contact); without it those bonuses add nothing. */
    val objects: BonusObjects? = null,
)

/** A mentor spirit added to the character: its own GUID, and the id of the data entry it came from (Chummer uses that as the unique name). */
class MentorRef(val guid: String, val id: String)

/** The power a bonus selected: its name and the choice it carries (a skill, an attribute). */
class PowerRef(val name: String, val extra: String)

/** What a bonus can add to the character besides improvements. Each returns the new object's GUID, or null when it cannot be added. */
interface BonusObjects {
    /** Gear named in a bonus; free unless [fullCost]. [parentGuid] is a gear item this one is added under. [sourceGuid] is the item that grants it. */
    fun addGear(sourceGuid: String, name: String, category: String, rating: Int, qty: Double, fullCost: Boolean, parentGuid: String?): String?
    fun addMentorSpirit(name: String): MentorRef?
    /** An adept power added with no levels of its own (free levels follow); null when the character is not an adept or the power is unknown. */
    fun addPower(name: String): PowerRef?
    fun addContact(loyalty: Int, connection: Int, group: Boolean, readOnly: Boolean): String?
    /** A melee weapon the bonus gives (a bite, claws): the `naturalweapon` node and the source it comes from; the new weapon's GUID. */
    fun addNaturalWeapon(node: Element, sourceName: String): String? = null
}

/** The improvements a `<bonus>` node produces, and the child nodes no handler exists for yet. */
class BonusResult(val improvements: List<Improvement>, val unhandled: List<String>)

private typealias Handler = (node: Element, ctx: BonusContext, unique: String, out: MutableList<Improvement>) -> Unit

/**
 * Turns an item `<bonus>` node into improvements, as Chummer's `AddImprovementCollection` does when an item is
 * added. Nodes that only create one improvement come from [GeneratedBonusTable]; the rest are written by hand
 * below. The result is checked against the improvements Chummer stored in the fixtures (BonusHandlerFixtureTest).
 *
 * Nodes that ask the user to choose (`selecttext`, `selectskill`, ...) take the choice from [BonusContext.choice];
 * the creation screens will supply it.
 */
object BonusHandlers {
    fun apply(bonus: Element, ctx: BonusContext): BonusResult {
        val unique = bonus.getAttribute("unique")
        val out = ArrayList<Improvement>()
        val unhandled = ArrayList<String>()
        for (node in bonus.children()) {
            var name = node.tagName.lowercase()
            name = GeneratedBonusTable.aliases[name] ?: name
            val simple = GeneratedBonusTable.simple[name]
            val hand = HAND[name]
            when {
                hand != null -> hand(node, ctx, unique, out)
                simple != null -> if (!applySimple(simple, node, ctx, unique, out, name)) unhandled += node.tagName
                else -> unhandled += node.tagName
            }
        }
        return BonusResult(out, unhandled)
    }

    private fun applySimple(s: SimpleBonus, node: Element, ctx: BonusContext, unique: String, out: MutableList<Improvement>, name: String): Boolean {
        val improved = when (s.nameFrom) {
            "literal" -> s.literal
            "text" -> node.textContent.trim()
            else -> ""
        }
        var value = 0.0
        if (s.hasValue) {
            value = value(node.textContent, ctx.rating) ?: return false
            if (s.roundToInt) value = standardRound(value).toDouble()
        }
        // `initiativedice` and friends use their own node name as the unique name, so only the highest applies.
        val u = if (s.unique.isNotEmpty()) s.unique else unique
        out += improvement(ctx, s.type, u, value, improved)
        return true
    }

    /** Whether a handler exists for a bonus node name. */
    fun knows(nodeName: String): Boolean {
        val n = nodeName.lowercase().let { GeneratedBonusTable.aliases[it] ?: it }
        return n in HAND || n in GeneratedBonusTable.simple
    }

    /** Chummer's `ValueToDec`: the text evaluated with `Rating` replaced by the item's rating. */
    fun value(text: String?, rating: Int): Double? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return 0.0
        return Expr.evalOrNull(t.replace("{Rating}", rating.toString()).replace("Rating", rating.toString()))
    }

    private fun intValue(text: String?, rating: Int): Double? = value(text, rating)?.let { standardRound(it).toDouble() }

    fun improvement(
        ctx: BonusContext, type: String, unique: String, value: Double = 0.0, improvedName: String = "",
        rating: Double = 1.0, min: Double = 0.0, max: Double = 0.0, augmented: Double = 0.0, augMax: Double = 0.0,
        exclude: String = "", addToRating: Boolean = false, condition: String = "", target: String = "",
    ) = Improvement(
        type = type, source = ctx.source, improvedName = improvedName, sourceName = ctx.sourceName, uniqueName = unique,
        value = value, augmented = augmented, minimum = min, maximum = max, augmentedMaximum = augMax, rating = rating,
        addToRating = addToRating, enabled = true, custom = false, condition = condition, exclude = exclude, target = target,
    )

    // ---- helpers for the hand-written handlers ----

    private fun Element.t(tag: String): String? = text(tag)?.trim()
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    /** `precedence` and `group` attributes choose a unique name that decides how same-named bonuses combine. */
    private fun uniqueFor(node: Element, unique: String, allowGroup: Boolean = false): String =
        node.attr("precedence")?.let { "precedence$it" }
            ?: (if (allowGroup) node.attr("group")?.let { "group$it" } else null)
            ?: unique

    /** A node holding one number, with `precedence`/`group` support (the armor and dodge nodes). */
    private fun grouped(type: String): Handler = { node, ctx, unique, out ->
        value(node.textContent, ctx.rating)?.let { out += improvement(ctx, type, uniqueFor(node, unique, allowGroup = true), it) }
    }

    /** A node with `<name>`, `<val>`, `<min>`, `<max>`, `<condition>` children that changes a karma cost or point cost. */
    private fun costModifier(type: String): Handler = { node, ctx, unique, out ->
        out += improvement(
            ctx, type, unique, value(node.t("val"), ctx.rating) ?: 0.0, node.t("name").orEmpty(),
            min = intValue(node.t("min"), ctx.rating) ?: 0.0, max = intValue(node.t("max"), ctx.rating) ?: 0.0,
            condition = node.t("condition").orEmpty(),
        )
    }

    /** User choices and bookkeeping nodes that create no improvement of their own. */
    private val NO_IMPROVEMENT = setOf(
        "pushtext", "pushtextforqualitygroup",
        // Not improvement nodes in Chummer: drug components and vehicle stats reuse these names inside `<bonus>`.
        "attribute", "limit", "quality", "handling",
    )

    private val LIVING_PERSONA = ArrayList<Pair<String, String>>()

    private val HAND: Map<String, Handler> = buildMap {
        for (n in NO_IMPROVEMENT) put(n) { _, _, _, _ -> }

        // The text the user entered (a name, a contact, a restriction) is kept as a Text improvement.
        put("selecttext") { _, ctx, unique, out ->
            if (ctx.choice.isNotEmpty()) out += improvement(ctx, "Text", unique, 0.0, ctx.choice)
        }

        put("armor", grouped("Armor"))
        put("firearmor", grouped("FireArmor"))
        put("coldarmor", grouped("ColdArmor"))
        put("electricityarmor", grouped("ElectricityArmor"))
        put("acidarmor", grouped("AcidArmor"))
        put("fallingarmor", grouped("FallingArmor"))
        put("dodge", grouped("Dodge"))

        put("initiativedice") { node, ctx, _, out ->
            value(node.textContent, ctx.rating)?.let { out += improvement(ctx, "InitiativeDice", uniqueFor(node, node.tagName.lowercase()), it) }
        }
        put("initiativediceadd") { node, ctx, _, out ->
            value(node.textContent, ctx.rating)?.let { out += improvement(ctx, "InitiativeDiceAdd", uniqueFor(node, node.tagName.lowercase()), it) }
        }

        put("specificattribute") { node, ctx, unique, out ->
            var attribute = node.t("name").orEmpty()
            val max = node.t("max")
            // "-natural" needs the character's metatype maximum; not available here.
            if (max != null && max.endsWith("-natural")) return@put
            if (node.child("affectbase") != null && attribute.isNotEmpty()) attribute += "Base"
            out += improvement(
                ctx, "Attribute", node.child("name")?.attr("precedence")?.let { "precedence$it" } ?: uniqueFor(node, unique), 0.0, attribute,
                min = node.t("min")?.let { intValue(it, ctx.rating) } ?: 0.0,
                max = max?.let { intValue(it, ctx.rating) } ?: 0.0,
                augmented = node.t("val")?.let { value(it, ctx.rating) } ?: 0.0,
                augMax = node.t("aug")?.let { intValue(it, ctx.rating) } ?: 0.0,
                condition = node.t("condition").orEmpty(),
            )
        }
        put("enableattribute") { node, ctx, _, out ->
            val name = node.t("name")?.uppercase()
            if (name == "MAG" || name == "RES" || name == "DEP") out += improvement(ctx, "Attribute", "enableattribute", 0.0, name, rating = 0.0)
        }
        put("enabletab") { node, ctx, _, out ->
            val tabs = mapOf("MAGICIAN" to "Magician", "ADEPT" to "Adept", "TECHNOMANCER" to "Technomancer",
                "ADVANCED PROGRAMS" to "Advanced Programs", "CRITTER" to "Critter")
            for (n in node.children("name")) tabs[n.textContent.trim().uppercase()]?.let {
                out += improvement(ctx, "SpecialTab", "enabletab", 0.0, it, rating = 0.0)
            }
        }

        put("conditionmonitor") { node, ctx, unique, out ->
            node.t("physical")?.takeIf { it.isNotEmpty() }?.let { v -> value(v, ctx.rating)?.let { out += improvement(ctx, "PhysicalCM", unique, it) } }
            node.t("stun")?.takeIf { it.isNotEmpty() }?.let { v -> value(v, ctx.rating)?.let { out += improvement(ctx, "StunCM", unique, it) } }
            for ((tag, type) in listOf("threshold" to "CMThreshold", "thresholdoffset" to "CMThresholdOffset", "sharedthresholdoffset" to "CMSharedThresholdOffset")) {
                node.child(tag)?.let { n -> value(n.textContent, ctx.rating)?.let { out += improvement(ctx, type, uniqueFor(n, unique), it) } }
            }
            node.child("overflow")?.let { n -> value(n.textContent, ctx.rating)?.let { out += improvement(ctx, "CMOverflow", unique, it) } }
        }

        put("limitmodifier") { node, ctx, unique, out ->
            // Chummer stores a random id for the modifier as the improved name; the limit's name is kept here instead.
            val bonus = value(node.t("value"), ctx.rating) ?: return@put
            val raw = node.t("condition").orEmpty()
            // Conditions given as language keys are stored in their English text.
            val condition = (if (raw.startsWith("LimitCondition_")) ctx.translate(raw) else null) ?: raw
            out += improvement(ctx, "LimitModifier", ctx.friendlyName.ifEmpty { unique }, bonus, node.t("limit").orEmpty(), rating = 0.0, condition = condition)
        }

        // Dice for a category of spells (`spellcategory` is an alias), or for one spell.
        put("spellcategorydicepool") { node, ctx, unique, out ->
            val name = node.t("name").orEmpty()
            if (name.isEmpty()) return@put
            value(node.t("val"), ctx.rating)?.let { out += improvement(ctx, "SpellCategory", uniqueFor(node, unique), it, name, condition = node.t("condition").orEmpty()) }
        }
        put("spelldicepool") { node, ctx, unique, out ->
            val name = node.t("id") ?: node.t("name") ?: return@put
            value(node.t("val"), ctx.rating)?.let { out += improvement(ctx, "SpellDicePool", uniqueFor(node, unique), it, name) }
        }
        // A change to drain, for all spells or (with a `specific` attribute) one kind.
        put("drainvalue") { node, ctx, unique, out ->
            value(node.textContent.trim(), ctx.rating)?.let { out += improvement(ctx, "DrainValue", unique, it, node.getAttribute("specific")) }
        }
        // A natural weapon (bite, claws): a melee weapon in the Critter Powers category, tied to the bonus's source by a Weapon improvement.
        put("naturalweapon") { node, ctx, unique, out ->
            val guid = ctx.objects?.addNaturalWeapon(node, ctx.friendlyName.ifEmpty { ctx.sourceName }) ?: return@put
            out += improvement(ctx, "Weapon", unique, 0.0, guid, rating = 0.0)
        }
        put("specificskill") { node, ctx, unique, out ->
            val addToRating = node.t("applytorating").equals("True", true)
            val condition = node.t("condition").orEmpty()
            val u = uniqueFor(node, unique)
            val name = node.t("name").orEmpty()
            node.t("bonus")?.takeIf { it.isNotEmpty() }?.let { v ->
                value(v, ctx.rating)?.let { out += improvement(ctx, "Skill", u, it, name, addToRating = addToRating, condition = condition) }
            }
            if (node.child("disablespecializationeffects") != null) {
                out += improvement(ctx, "DisableSpecializationEffects", u, 0.0, name, condition = condition)
            }
            node.t("max")?.takeIf { it.isNotEmpty() }?.let { v ->
                intValue(v, ctx.rating)?.let { out += improvement(ctx, "Skill", u, 0.0, name, max = it, condition = condition) }
            }
        }
        for ((tag, type) in listOf("skillcategory" to "SkillCategory", "skillgroup" to "SkillGroup")) {
            put(tag) { node, ctx, unique, out ->
                val name = node.t("name").orEmpty()
                if (name.isEmpty()) return@put
                value(node.t("bonus"), ctx.rating)?.let {
                    out += improvement(
                        ctx, type, unique, it, name, exclude = node.t("exclude").orEmpty(),
                        addToRating = node.t("applytorating").equals("True", true), condition = node.t("condition").orEmpty(),
                    )
                }
            }
        }

        put("movementreplace") { node, ctx, unique, out ->
            val type = when (node.t("speed")?.uppercase()) { "RUN" -> "RunSpeed"; "SPRINT" -> "SprintSpeed"; else -> "WalkSpeed" }
            val v = value(node.t("val"), ctx.rating) ?: return@put
            val category = node.t("category")
            if (!category.isNullOrEmpty()) out += improvement(ctx, type, unique, v, category)
            else for (c in listOf("Ground", "Swim", "Fly")) out += improvement(ctx, type, unique, v, c)
        }

        put("lifestylecost") { node, ctx, unique, out ->
            value(node.textContent, ctx.rating)?.let {
                out += improvement(ctx, "LifestyleCost", unique, it, node.attr("lifestyle").orEmpty(), condition = node.attr("condition").orEmpty())
            }
        }

        put("restrictedgear") { node, ctx, unique, out ->
            // The availability limit is the value and the number of items the rating; old files held the limit as plain text.
            var limit = node.t("availability")
            var amount = node.t("amount")
            if (amount.isNullOrEmpty()) { amount = "1"; if (limit.isNullOrEmpty()) limit = node.textContent.trim() }
            val v = value(limit, ctx.rating) ?: return@put
            val count = intValue(amount, ctx.rating) ?: return@put
            out += improvement(ctx, "RestrictedGear", unique, v, rating = count)
        }

        // Extra reach, optionally only for the weapon named in the `name` attribute.
        put("reach") { node, ctx, unique, out ->
            value(node.textContent, ctx.rating)?.let { out += improvement(ctx, "Reach", unique, it, node.attr("name").orEmpty()) }
        }

        // A quality that adds other qualities: each one becomes a quality of the character, and a SpecificQuality
        // improvement records which (its improved name is the new quality's GUID).
        put("addqualities") { node, ctx, unique, out ->
            for (q in node.children("addquality")) {
                val guid = ctx.addQuality(q.textContent.trim(), q.attr("select").orEmpty()) ?: continue
                out += improvement(ctx, "SpecificQuality", unique, 0.0, guid)
            }
        }
        // The older form, in Chummer 5.226.0's traditions: one quality named straight in the bonus (no addqualities around it).
        put("addquality") { node, ctx, unique, out ->
            val guid = ctx.addQuality(node.textContent.trim(), node.attr("select").orEmpty()) ?: return@put
            out += improvement(ctx, "SpecificQuality", unique, 0.0, guid)
        }

        // Unlocks a group of skills (for example the magical skills for a Magician); the editor adds the skills themselves.
        put("unlockskills") { node, ctx, unique, out ->
            val options = node.textContent.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val chosen = if (options.size == 1) options[0] else ctx.choice.takeIf { it in options } ?: return@put
            out += improvement(ctx, "SpecialSkills", unique, 0.0, chosen)
        }

        put("selectrestricted") { _, ctx, unique, out ->
            if (ctx.choice.isNotEmpty()) out += improvement(ctx, "Restricted", unique, 0.0, ctx.choice)
        }

        for (t in listOf("skillcategorykarmacost" to "SkillCategoryKarmaCost", "skillcategorypointcostmultiplier" to "SkillCategoryPointCostMultiplier",
            "skillcategoryspecializationkarmacostmultiplier" to "SkillCategorySpecializationKarmaCostMultiplier",
            "skillcategorykarmacostmultiplier" to "SkillCategoryKarmaCostMultiplier",
            "activeskillkarmacost" to "ActiveSkillKarmaCost", "knowledgeskillkarmacost" to "KnowledgeSkillKarmaCost",
            "skillgroupkarmacost" to "SkillGroupKarmaCost", "skillgroupcategorykarmacost" to "SkillGroupCategoryKarmaCost",
            "skillgroupcategorykarmacostmultiplier" to "SkillGroupCategoryKarmaCostMultiplier")) put(t.first, costModifier(t.second))

        // A drug's positive effect on an attribute category: the value is also kept as the augmented value.
        put("drugpositiveattributemodifier") { node, ctx, unique, out ->
            val v = value(node.textContent, ctx.rating) ?: return@put
            out += improvement(ctx, "DrugPositiveAttributeModifier", unique, v, node.attr("category").orEmpty(), augmented = v)
        }

        // Sprint distance in metres and in percent for a movement category.
        put("sprintbonus") { node, ctx, unique, out ->
            val category = node.t("category").orEmpty()
            if (category.isEmpty()) return@put
            node.t("val")?.takeIf { it.isNotEmpty() }?.let { t -> value(t, ctx.rating)?.takeIf { it != 0.0 }?.let { out += improvement(ctx, "SprintBonus", unique, it, category) } }
            node.t("percent")?.takeIf { it.isNotEmpty() }?.let { t -> value(t, ctx.rating)?.takeIf { it != 0.0 }?.let { out += improvement(ctx, "SprintBonusPercent", unique, it, category) } }
        }

        // The improvement carries the node text and the item rating (Chummer also adds the value to the character's prototype count).
        put("prototypetranshuman") { node, ctx, unique, out ->
            out += improvement(ctx, "PrototypeTranshuman", unique, 0.0, node.textContent.trim(), rating = ctx.rating.toDouble())
        }

        // Accuracy for a weapon skill: the skill is named in the node or picked by the user.
        put("weaponskillaccuracy") { node, ctx, unique, out ->
            val skill = ctx.choice.ifEmpty { node.t("name").orEmpty() }
            if (skill.isEmpty() && node.child("selectskill") != null) return@put
            out += improvement(ctx, "WeaponSkillAccuracy", unique, value(node.t("value"), ctx.rating) ?: 0.0, skill)
        }

        // ---- nodes that ask the user to choose; the choice arrives in the context (the item's saved `extra`) ----

        // A skill the user picks: its rating bonus, its maximum, and whether specialization effects are switched off.
        put("selectskill") { node, ctx, unique, out ->
            val skill = ctx.choice
            if (skill.isEmpty()) return@put
            val addToRating = node.t("applytorating").equals("True", true)
            node.t("val")?.takeIf { it.isNotEmpty() }?.let { v ->
                out += improvement(ctx, "Skill", unique, value(v, ctx.rating) ?: 0.0, skill, addToRating = addToRating)
            }
            if (node.t("disablespecializationeffects").equals("True", true)) out += improvement(ctx, "DisableSpecializationEffects", unique, 0.0, skill)
            node.t("max")?.takeIf { it.isNotEmpty() }?.let { m ->
                out += improvement(ctx, "Skill", unique, 0.0, skill, max = intValue(m, ctx.rating) ?: 0.0, addToRating = addToRating)
            }
        }

        // A weapon or armor the user picks is kept as text.
        for (n in listOf("selectweapon", "selectarmor")) put(n) { _, ctx, unique, out ->
            if (ctx.choice.isNotEmpty()) out += improvement(ctx, "Text", unique, 0.0, ctx.choice)
        }

        put("selecttradition") { _, ctx, unique, out ->
            if (ctx.choice.isNotEmpty()) out += improvement(ctx, "Tradition", unique, 0.0, ctx.choice)
        }

        // A specialization a martial art adds as an option for a skill (Chummer: SkillSpecializationOption, the spec as the unique name).
        put("addskillspecializationoption") { node, ctx, _, out ->
            val spec = node.t("spec") ?: return@put
            out += improvement(ctx, "SkillSpecializationOption", spec, 0.0, node.t("skill").orEmpty())
        }

        // Another limb slot; `precedence` picks the unique name.
        put("addlimb") { node, ctx, unique, out ->
            out += improvement(ctx, "AddLimb", node.attr("precedence")?.let { "precedence$it" } ?: unique, value(node.t("val"), ctx.rating) ?: 0.0, node.t("limbslot").orEmpty())
        }

        // A skill hardwired into the character: the value from the node, the skill from the choice.
        put("hardwires") { node, ctx, unique, out ->
            val skill = ctx.choice
            if (skill.isEmpty()) return@put
            val exotic = skill.contains(" (")
            out += improvement(ctx, "Hardwire", if (exotic) unique else skill, value(node.textContent, ctx.rating) ?: 0.0, skill)
        }

        put("cyberseeker") { node, ctx, unique, out ->
            val attribute = node.textContent.trim()
            if (attribute == "BOX" || attribute in setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG", "MAG", "RES", "DEP")) {
                out += improvement(ctx, "Seeker", unique, 0.0, attribute, rating = 0.0)
            }
        }

        // The living persona's matrix attributes: the improved name carries the signed bonus as text.
        for ((tag, type) in listOf(
            "devicerating" to "LivingPersonaDeviceRating", "programlimit" to "LivingPersonaProgramLimit", "attack" to "LivingPersonaAttack",
            "sleaze" to "LivingPersonaSleaze", "dataprocessing" to "LivingPersonaDataProcessing", "firewall" to "LivingPersonaFirewall", "matrixcm" to "LivingPersonaMatrixCM",
        )) LIVING_PERSONA += tag to type
        put("livingpersona") { node, ctx, unique, out ->
            for ((tag, type) in LIVING_PERSONA) {
                var text = node.t(tag)?.takeIf { it.isNotEmpty() } ?: continue
                Regex("""^FixedValues\((.*)\)$""").find(text)?.let { m ->
                    val values = m.groupValues[1].split(',').map { it.trim() }
                    text = values.getOrNull((ctx.rating - 1).coerceIn(0, values.size - 1)).orEmpty()
                }
                text = text.replace("Rating", ctx.rating.toString())
                if ((text.toIntOrNull() ?: 0) > 0) text = "+$text"
                out += improvement(ctx, type, unique, 0.0, text)
            }
        }

        // ---- nodes that add objects to the character; the improvements record which object came from which item ----

        // Gear the item brings (free unless the node says `fullcost`), with the gear listed under `children` inside it.
        put("addgear") { node, ctx, unique, out ->
            val objects = ctx.objects ?: return@put
            fun purchase(n: Element, parent: String?): String? {
                val rating = n.t("rating")?.takeIf { it.isNotEmpty() }?.let { intValue(it, ctx.rating)?.toInt() } ?: 0
                val qty = n.t("quantity")?.takeIf { it.isNotEmpty() }?.let { value(it, ctx.rating) } ?: 1.0
                val guid = objects.addGear(ctx.sourceName, n.t("name").orEmpty(), n.t("category").orEmpty(), rating, qty, n.child("fullcost") != null, parent) ?: return null
                out += improvement(ctx, "Gear", unique, 0.0, guid)
                return guid
            }
            val parent = purchase(node, null)
            if (parent != null) for (child in node.child("children")?.children().orEmpty()) purchase(child, parent)
        }

        // A mentor spirit the user picks (the choice is its name).
        put("selectmentorspirit") { _, ctx, _, out ->
            val objects = ctx.objects ?: return@put
            if (ctx.choice.isEmpty()) return@put
            val mentor = objects.addMentorSpirit(ctx.choice) ?: return@put
            out += improvement(ctx, "MentorSpirit", mentor.id, 0.0, mentor.guid)
        }

        // Adept powers the item gives for free: the user picks one for each `selectpower` node (the choice lists them in order, "A, B").
        // The power is added with no levels of its own, and an improvement gives it free levels (or free points when the node has pointsperlevel).
        put("selectpowers") { node, ctx, _, out ->
            val objects = ctx.objects ?: return@put
            val picks = ctx.choice.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            node.children("selectpower").forEachIndexed { i, sel ->
                val name = picks.getOrNull(i) ?: return@forEachIndexed
                val power = objects.addPower(name) ?: return@forEachIndexed
                val levels = intValue(sel.t("val"), ctx.rating) ?: 0.0
                val freePoints = sel.t("pointsperlevel")?.isNotBlank() == true
                out += improvement(ctx, if (freePoints) "AdeptPowerFreePoints" else "AdeptPowerFreeLevels", power.extra, 0.0, power.name, rating = levels)
            }
        }

        // A quality the user picks from those the node lists.
        put("selectquality") { node, ctx, unique, out ->
            if (ctx.choice.isEmpty() || node.children("quality").none { it.textContent.trim() == ctx.choice }) return@put
            val guid = ctx.addQuality(ctx.choice, "") ?: return@put
            out += improvement(ctx, "SpecificQuality", unique, 0.0, guid)
        }

        // A contact the item gives, with the loyalty, connection and group flags from the node.
        put("addcontact") { node, ctx, unique, out ->
            val objects = ctx.objects ?: return@put
            val loyalty = node.t("loyalty")?.takeIf { it.isNotEmpty() }?.let { intValue(it, ctx.rating)?.toInt() } ?: 1
            val connection = node.t("connection")?.takeIf { it.isNotEmpty() }?.let { intValue(it, ctx.rating)?.toInt() } ?: 1
            val guid = objects.addContact(loyalty, connection, node.child("group") != null, node.child("canwrite") == null) ?: return@put
            out += improvement(ctx, "AddContact", guid, 0.0, guid)
            node.t("forcedloyalty")?.takeIf { it.isNotEmpty() }?.let { out += improvement(ctx, "ContactForcedLoyalty", unique, value(it, ctx.rating) ?: 0.0, guid) }
            if (node.child("free") != null) out += improvement(ctx, "ContactMakeFree", unique, 0.0, guid)
            if (node.child("forcegroup") != null) out += improvement(ctx, "ContactForceGroup", unique, 0.0, guid)
        }

        // Attributes the user picks: one Attribute improvement for each `selectattribute` node, in the order the choice lists them
        // (the choice reads like "AGI, REA (2)").
        put("selectattributes") { node, ctx, unique, out ->
            if (ctx.choice.isEmpty()) return@put
            val picks = ArrayList<String>()
            for (part in ctx.choice.split(',')) {
                val m = Regex("""^\s*([A-Za-z]+)\s*(?:\((\d+)\))?\s*$""").find(part) ?: continue
                repeat(m.groupValues[2].toIntOrNull() ?: 1) { picks += m.groupValues[1] }
            }
            node.children("selectattribute").forEachIndexed { i, sel ->
                val attribute = picks.getOrNull(i) ?: picks.firstOrNull() ?: return@forEachIndexed
                fun n(tag: String) = sel.t(tag)?.trim()?.toDoubleOrNull() ?: 0.0
                val name = if (sel.child("affectbase") != null) attribute + "Base" else attribute
                out += improvement(ctx, "Attribute", unique, 0.0, name, min = n("min"), max = n("max"), augmented = n("val"), augMax = n("aug"))
            }
        }

        // Bonus dice or damage for a weapon category: the category (or skill) is named in the node or chosen by the user.
        for ((tag, type) in listOf("weaponcategorydice" to "WeaponCategoryDice", "weaponcategorydv" to "WeaponCategoryDV")) put(tag) { node, ctx, unique, out ->
            val skill = node.child("selectskill")
            val category = node.child("selectcategory")
            when {
                skill != null -> if (ctx.choice.isNotEmpty()) out += improvement(ctx, type, unique, value(node.t("bonus"), ctx.rating) ?: 0.0, ctx.choice)
                category != null -> if (ctx.choice.isNotEmpty()) out += improvement(ctx, type, unique, value(category.t("value")?.takeIf { it.isNotEmpty() } ?: node.t("bonus"), ctx.rating) ?: 0.0, ctx.choice)
                node.child("name") != null -> out += improvement(ctx, type, unique, value(node.t("bonus"), ctx.rating) ?: 0.0, node.t("name").orEmpty())
                // The form in Chummer 5.226.0's data: a fixed category with its own name and value (later builds put both on the node).
                node.child("category")?.child("name") != null -> node.child("category")!!.let { c -> out += improvement(ctx, type, unique, value(c.t("value"), ctx.rating) ?: 0.0, c.t("name").orEmpty()) }
            }
        }

        // One attribute the user picks (the choice), with the node's minimum, maximum and bonuses.
        put("selectattribute") { node, ctx, unique, out ->
            if (ctx.choice.isEmpty()) return@put
            fun n(tag: String) = value(node.t(tag), ctx.rating) ?: 0.0
            val name = if (node.child("affectbase") != null) ctx.choice + "Base" else ctx.choice
            out += improvement(ctx, "Attribute", unique, 0.0, name, min = n("min"), max = n("max"), augmented = n("val"), augMax = n("aug"))
        }

        // Walking speed: a distance and a percentage for a movement category (like sprint).
        put("walkmultiplier") { node, ctx, unique, out ->
            val category = node.t("category").orEmpty()
            if (category.isEmpty()) return@put
            node.t("val")?.takeIf { it.isNotEmpty() }?.let { t -> value(t, ctx.rating)?.takeIf { it != 0.0 }?.let { out += improvement(ctx, "WalkMultiplier", unique, it, category) } }
            node.t("percent")?.takeIf { it.isNotEmpty() }?.let { t -> value(t, ctx.rating)?.takeIf { it != 0.0 }?.let { out += improvement(ctx, "WalkMultiplierPercent", unique, it, category) } }
        }

        put("runmultiplier") { node, ctx, unique, out ->
            val category = node.t("category").orEmpty()
            if (category.isEmpty()) return@put
            node.t("val")?.takeIf { it.isNotEmpty() }?.let { t -> value(t, ctx.rating)?.takeIf { it != 0.0 }?.let { out += improvement(ctx, "RunMultiplier", unique, it, category) } }
            node.t("percent")?.takeIf { it.isNotEmpty() }?.let { t -> value(t, ctx.rating)?.takeIf { it != 0.0 }?.let { out += improvement(ctx, "RunMultiplierPercent", unique, it, category) } }
        }

        // Fading (the technomancer's drain): the node text is the value, an optional `specific` attribute names what it applies to.
        put("fadingvalue") { node, ctx, unique, out ->
            value(node.textContent.trim(), ctx.rating)?.let { out += improvement(ctx, "FadingValue", unique, it, node.getAttribute("specific")) }
        }

        // Drain and damage changes for a spell category (the category may be the user's choice) or descriptor.
        put("spellcategorydrain") { node, ctx, unique, out ->
            val category = node.t("category")?.takeIf { it.isNotEmpty() } ?: ctx.choice.takeIf { it.isNotEmpty() } ?: return@put
            value(node.t("val"), ctx.rating)?.let { out += improvement(ctx, "SpellCategoryDrain", unique, it, category) }
        }
        for ((tag, field, type) in listOf(
            Triple("spellcategorydamage", "category", "SpellCategoryDamage"),
            Triple("spelldescriptordamage", "descriptor", "SpellDescriptorDamage"),
            Triple("spelldescriptordrain", "descriptor", "SpellDescriptorDrain"),
        )) put(tag) { node, ctx, unique, out ->
            value(node.t("val"), ctx.rating)?.let { out += improvement(ctx, type, unique, it, node.t(field).orEmpty()) }
        }

        // A cyberware or bioware grade the character cannot take; the node name is the unique name, as in Chummer.
        put("disablecyberwaregrade") { node, ctx, _, out -> out += improvement(ctx, "DisableCyberwareGrade", "disablecyberwaregrade", 0.0, node.textContent.trim()) }
        put("disablebiowaregrade") { node, ctx, _, out -> out += improvement(ctx, "DisableBiowareGrade", "disablebiowaregrade", 0.0, node.textContent.trim()) }

        // Attributes whose limits are replaced (a critter's or a metatype's own range).
        put("replaceattributes") { node, ctx, unique, out ->
            for (a in node.children("replaceattribute")) {
                val name = a.t("name") ?: continue
                fun n(tag: String) = a.t(tag)?.toDoubleOrNull() ?: 0.0
                out += improvement(ctx, "ReplaceAttribute", unique, 0.0, name, rating = 1.0, min = n("min"), max = n("max"), augMax = n("aug"))
            }
        }

        // The karma cost of binding a focus changes by the value; `extracontains` limits it to foci whose name contains the text.
        put("focusbindingkarmacost") { node, ctx, unique, out ->
            out += improvement(ctx, "FocusBindingKarmaCost", unique, value(node.t("val"), ctx.rating) ?: 0.0, node.t("name").orEmpty(), rating = 1.0, target = node.t("extracontains").orEmpty())
        }

        put("focusbindingkarmamultiplier") { node, ctx, unique, out ->
            out += improvement(ctx, "FocusBindingKarmaMultiplier", unique, value(node.t("val"), ctx.rating) ?: 0.0, node.t("name").orEmpty(), rating = 1.0, target = node.t("extracontains").orEmpty())
        }

        // Older data spelled the metagenic limit node "metageneticlimit".
        put("metageneticlimit") { node, ctx, unique, out ->
            value(node.textContent, ctx.rating)?.let { out += improvement(ctx, "MetageneticLimit", unique, it) }
        }
    }
}
