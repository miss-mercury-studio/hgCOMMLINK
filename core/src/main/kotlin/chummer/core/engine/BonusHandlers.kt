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
)

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
        exclude: String = "", addToRating: Boolean = false, condition: String = "",
    ) = Improvement(
        type = type, source = ctx.source, improvedName = improvedName, sourceName = ctx.sourceName, uniqueName = unique,
        value = value, augmented = augmented, minimum = min, maximum = max, augmentedMaximum = augMax, rating = rating,
        addToRating = addToRating, enabled = true, custom = false, condition = condition, exclude = exclude,
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

        put("selectrestricted") { _, ctx, unique, out ->
            if (ctx.choice.isNotEmpty()) out += improvement(ctx, "Restricted", unique, 0.0, ctx.choice)
        }

        for (t in listOf("skillcategorykarmacost" to "SkillCategoryKarmaCost", "skillcategorypointcostmultiplier" to "SkillCategoryPointCostMultiplier",
            "skillcategoryspecializationkarmacostmultiplier" to "SkillCategorySpecializationKarmaCostMultiplier",
            "skillcategorykarmacostmultiplier" to "SkillCategoryKarmaCostMultiplier")) put(t.first, costModifier(t.second))
    }
}
