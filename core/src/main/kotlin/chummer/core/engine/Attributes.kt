package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.children
import chummer.core.int
import chummer.core.text

/** Rule options that change attribute maths. Defaults match Chummer's; read from settings later. */
data class RuleSettings(
    val unclampAttributeMinimum: Boolean = false,
    val dontUseCyberlimbCalculation: Boolean = false,
    val limbCount: Int = 6,
    val cyberlimbAttributeBonusCap: Int = 4,
    val excludeLimbSlots: Set<String> = emptySet(),
    val redlinerExcludes: Set<String> = setOf("skull", "torso"),
    val essenceDecimals: Int = 2,
    val dontRoundEssenceInternally: Boolean = false,
    val maxSkillRating: Int = 12,
    val maxSkillRatingCreate: Int = 6,
    val maxKnowledgeSkillRating: Int = 12,
    val maxKnowledgeSkillRatingCreate: Int = 6,
)

/** An attribute as stored plus the figures the engine derives from it. */
data class AttributeResult(
    val name: String,
    val totalMinimum: Int,
    val totalMaximum: Int,
    val value: Int,
    val totalValue: Int,
)

/**
 * Attribute calculation, a port of Chummer's `CharacterAttrib` (Attribute.Core.cs).
 * Not yet covered: cyberlimb averaging for AGI and STR, Cyberzombie, and critter rules.
 */
class AttributeEngine(
    private val improvements: ImprovementSet,
    private val settings: RuleSettings = RuleSettings(),
    private val isCritter: Boolean = false,
    private val cyberware: List<CharacterFile.Augmentation> = emptyList(),
) {
    data class Raw(val name: String, val metatypeMin: Int, val metatypeMax: Int, val metatypeAugMax: Int, val base: Int, val karma: Int)

    fun calculate(a: Raw): AttributeResult {
        val abbrev = a.name
        // Modifiers that move the limits: sum of min/max * rating over this attribute's improvements.
        val forName = improvements.matching("Attribute", abbrev)
        val minimumModifiers = forName.sumOf { it.minimum * it.rating }.let(::standardRound)
        val maximumModifiers = forName.sumOf { it.maximum * it.rating }.let(::standardRound)
        val augMaxModifiers = forName.sumOf { it.augmentedMaximum * it.rating }.let(::standardRound)

        val rawMinimum = if (settings.unclampAttributeMinimum) a.metatypeMin + minimumModifiers
        else maxOf(a.metatypeMin + minimumModifiers, 0)
        val totalMaximum = maxOf(0, a.metatypeMax + maximumModifiers)
        val totalMinimum = if (rawMinimum < 1) {
            if (isCritter || totalMaximum == 0 || abbrev in ZERO_MIN) 0 else 1
        } else rawMinimum

        val freeBase = standardRound(minOf(improvements.valueOf("Attributelevel", abbrev), (a.metatypeMax - a.metatypeMin).toDouble()))
        val valueModifiers = standardRound(improvements.augmentedValueOf("Attribute", abbrev + "Base"))
        val value = minOf(maxOf(a.base + freeBase + rawMinimum + valueModifiers, totalMinimum) + a.karma, totalMaximum)

        val attributeModifiers = standardRound(improvements.augmentedValueOf("Attribute", abbrev))
        // Chummer's TotalAugmentedMaximum also counts the maximum modifiers (Exceptional Attribute raises both), unless the maximum is clamped.
        val totalAugMax = if (improvements.matching("AttributeMaxClamp", abbrev).isNotEmpty()) totalMaximum
        else maxOf(0, a.metatypeAugMax + maximumModifiers + augMaxModifiers)
        val meat = value + attributeModifiers
        var total = meat
        if (!settings.dontUseCyberlimbCalculation && abbrev in LIMB_ATTRIBUTES) {
            val (limbCount, limbTotal) = processLimbs(cyberware, abbrev, totalMaximum, totalAugMax)
            if (limbCount > 0) {
                val maxLimbs = settings.limbCount + standardRound(improvements.valueOf("AddLimb"))
                val missing = maxOf(maxLimbs - limbCount, 0)
                // Limbs that are not replaced count at the natural value; the attribute is the average.
                total = (limbTotal + maxOf(meat, 0) * missing + maxLimbs - 1) / maxLimbs
            }
        }
        total = minOf(total, totalAugMax)
        if (total < 1) total = lowFloor(abbrev, total)
        return AttributeResult(abbrev, totalMinimum, totalMaximum, value, total)
    }

    /** Count and summed attribute of installed cyberlimbs (each limb counted per slot it fills). */
    private fun processLimbs(
        list: List<CharacterFile.Augmentation>, abbrev: String, attrMax: Int, attrAugMax: Int,
    ): Pair<Int, Int> {
        var count = 0
        var total = 0
        for (c in list) {
            if (c.plugsIntoModularMount.isNotEmpty()) continue // modular attachments: not modelled yet
            if (c.isLimb) {
                if (c.limbSlot in settings.excludeLimbSlots) continue
                count += c.limbSlotCount
                total += limbValue(c, abbrev, attrMax, attrAugMax) * c.limbSlotCount
            } else {
                val (n, t) = processLimbs(c.children, abbrev, attrMax, attrAugMax)
                count += n
                total += t
            }
        }
        return count to total
    }

    /** Redliner: with a Seeker improvement on STR or AGI, every two cyberlimbs add 1 (at most 2). */
    private fun redlinerBonus(): Int {
        val seeker = improvements.matching("Seeker").map { it.improvedName }
        if (seeker.none { it == "STR" || it == "AGI" }) return 0
        fun limbs(list: List<CharacterFile.Augmentation>): Int = list.sumOf {
            if (it.isLimb && it.limbSlot !in settings.redlinerExcludes) it.limbSlotCount else if (it.isLimb) 0 else limbs(it.children)
        }
        return minOf(limbs(cyberware) / 2, 2)
    }

    /** Chummer's `Cyberware.GetAttributeTotalValue`. */
    private fun limbValue(c: CharacterFile.Augmentation, abbrev: String, attrMax: Int, attrAugMax: Int): Int {
        if (c.inheritAttributes) {
            val kids = c.children.map { limbValue(it, abbrev, attrMax, attrAugMax) }.filter { it > 0 }
            return kids.sum() / maxOf(kids.size, 1)
        }
        if (c.category != "Cyberlimb" && !c.isLimb) return 0
        // Value: the limb's own base, replaced by the best customization child.
        var value = if (abbrev == "AGI") c.minAgility else c.minStrength
        val custom = c.children.filter { it.name in CUSTOMIZATION.getValue(abbrev) }
        if (custom.isNotEmpty()) value = custom.maxOf { it.rating }
        value = minOf(value, attrMax)
        // Enhancement children add a bonus, capped by the settings.
        val enhancement = c.children.filter { it.name in ENHANCEMENT.getValue(abbrev) }
        var bonus = if (enhancement.isEmpty()) 0 else enhancement.maxOf { it.rating }
        bonus += redlinerBonus()
        bonus += standardRound(improvements.valueOf("CyberlimbAttributeBonus", abbrev))
        bonus = minOf(bonus, settings.cyberlimbAttributeBonusCap)
        return minOf(value + bonus, attrAugMax)
    }

    /** Attributes can only fall below 1 for some attributes, or through encumbrance. */
    private fun lowFloor(abbrev: String, total: Int): Int {
        if (isCritter || abbrev in ZERO_MIN) return maxOf(total, 0)
        // Only encumbrance-driven reductions to AGI and REA may take the attribute to 0.
        if (abbrev == "AGI" || abbrev == "REA") {
            val encumbrance = improvements.matching("Attribute", abbrev).filter { it.source == "ArmorEncumbrance" }.sumOf { it.augmented * it.rating }
            if (encumbrance < 0) return 0
        }
        return 1
    }

    companion object {
        private val ZERO_MIN = setOf("EDG", "MAG", "MAGAdept", "RES", "DEP")
        private val LIMB_ATTRIBUTES = setOf("AGI", "STR")
        private val CUSTOMIZATION = mapOf(
            "AGI" to setOf("Customized Agility", "Cyberlimb Customization, Agility (2050)"),
            "STR" to setOf("Customized Strength", "Cyberlimb Customization, Strength (2050)"),
        )
        private val ENHANCEMENT = mapOf(
            "AGI" to setOf("Enhanced Agility", "Cyberlimb Augmentation, Agility (2050)"),
            "STR" to setOf("Enhanced Strength", "Cyberlimb Augmentation, Strength (2050)"),
        )

        /** All attributes of a character file, calculated. ESS is left to the essence calculation. */
        fun forFile(c: CharacterFile, settings: RuleSettings = RuleSettings()): Map<String, AttributeResult> {
            val career = c.root.text("created")?.trim().equals("True", true)
            return forSet(c, ImprovementSet(Improvement.parseAll(c.root), career), settings)
        }

        /** Like [forFile], but with an explicit set of improvements (for example with a drug group switched on). */
        fun forSet(c: CharacterFile, set: ImprovementSet, settings: RuleSettings = RuleSettings()): Map<String, AttributeResult> {
            val engine = AttributeEngine(set, settings, c.root.text("critter")?.trim().equals("True", true), c.augmentations())
            val rows = c.root.child("attributes")?.children("attribute").orEmpty()
            return rows.map {
                Raw(it.text("name").orEmpty(), it.int("metatypemin"), it.int("metatypemax"), it.int("metatypeaugmax"), it.int("base"), it.int("karma"))
            }.filter { it.name != "ESS" }.associate { it.name to engine.calculate(it) }
        }
    }
}
