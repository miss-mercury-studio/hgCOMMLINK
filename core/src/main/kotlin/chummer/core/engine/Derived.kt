package chummer.core.engine

import chummer.core.data.CharacterSettings
import kotlin.math.ceil

/**
 * Stats derived from attributes and improvements: limits, initiative, condition monitors,
 * lift and carry, and the simple attribute-sum pools. Formulas follow Shadowrun 5e and were
 * checked against Chummer's own print output (see GoldenExportDerivedTest).
 *
 * Not yet covered: matrix and astral initiative, armor and the armor-based resistance pools,
 * movement, and limit modifiers that depend on a condition.
 */
class DerivedStats(
    private val improvements: ImprovementSet,
    private val attributes: Map<String, AttributeResult>,
    private val essence: Double,
    private val settings: CharacterSettings,
) {
    private fun attr(name: String) = attributes[name]?.totalValue ?: 0
    private fun improvement(type: String, name: String = ""): Int = standardRound(improvements.valueOf(type, name))

    private fun third(x: Double) = ceil(x / 3.0 - 1e-9).toInt()

    val limitPhysical: Int get() = third(attr("STR") * 2.0 + attr("BOD") + attr("REA")) + improvement("PhysicalLimit")
    val limitMental: Int get() = third(attr("LOG") * 2.0 + attr("INT") + attr("WIL")) + improvement("MentalLimit")
    val limitSocial: Int get() = third(attr("CHA") * 2.0 + attr("WIL") + essence) + improvement("SocialLimit")
    val limitAstral: Int get() = maxOf(limitMental, limitSocial)

    /** Initiative score and number of initiative dice, kept within the settings' limits. */
    class Initiative(val value: Int, val dice: Int)

    val initiative: Initiative
        get() {
            val value = attr("REA") + attr("INT") + improvement("Initiative")
            val dice = 1 + improvement("InitiativeDice") + improvement("InitiativeDiceAdd")
            return Initiative(value, dice.coerceIn(settings.int("mininitiativedice", 1), settings.int("maxinitiativedice", 5)))
        }

    val physicalCM: Int get() = 8 + ceil(attr("BOD") / 2.0).toInt() + improvement("PhysicalCM")
    val stunCM: Int get() = 8 + ceil(attr("WIL") / 2.0).toInt() + improvement("StunCM")

    /** Boxes of overflow before death: Body plus one. */
    val cmOverflow: Int get() = attr("BOD") + 1 + improvement("CMOverflow")
    val physicalCMThresholdOffset: Int get() = improvement("CMThresholdOffset") + improvement("PhysicalCMThresholdOffset")
    val stunCMThresholdOffset: Int get() = improvement("CMThresholdOffset") + improvement("StunCMThresholdOffset")

    /** Boxes of damage per point of wound penalty: 3 plus improvements. */
    val cmThreshold: Int get() = maxOf(1, 3 + improvement("CMThreshold"))

    /**
     * The wound modifier (zero or negative) for [physicalFilled] and [stunFilled] boxes of damage, as `Character.RefreshWoundPenalties`:
     * every [cmThreshold] boxes beyond the threshold offset cost one die, unless an improvement ignores that track's penalty.
     */
    fun woundModifier(physicalFilled: Int, stunFilled: Int): Int {
        fun ignores(type: String) = improvements.all.any { it.enabled && it.type == type }
        val stun = if (ignores("IgnoreCMPenaltyStun")) 0 else minOf(0, stunCMThresholdOffset - minOf(stunFilled, stunCM)) / cmThreshold
        val physical = if (ignores("IgnoreCMPenaltyPhysical")) 0 else minOf(0, physicalCMThresholdOffset - minOf(physicalFilled, physicalCM)) / cmThreshold
        return stun + physical
    }

    /** Lift and carry limits come from expressions in the settings, for example `{STR} * 15`. */
    val liftWeight: Int get() = standardRound(settings.evaluate("liftlimitexpression", mapOf("STR" to attr("STR").toDouble()), "{STR} * 15"))
    val carryWeight: Int get() = standardRound(settings.evaluate("carrylimitexpression", mapOf("STR" to attr("STR").toDouble()), "{STR} * 10"))

    val composure: Int get() = attr("CHA") + attr("WIL") + improvement("Composure")
    val judgeIntentions: Int get() = attr("INT") + attr("CHA") + improvement("JudgeIntentions")
    val memory: Int get() = attr("LOG") + attr("WIL") + improvement("Memory")
    val surprise: Int get() = attr("REA") + attr("INT") + improvement("Surprise")
    val liftAndCarry: Int get() = attr("STR") + attr("BOD") + improvement("LiftAndCarry")
    val fatigueResist: Int get() = attr("BOD") + attr("WIL") + improvement("FatigueResist")
    val dodge: Int get() = attr("REA") + attr("INT") + improvement("Dodge")
    val physicalCMNaturalRecovery: Int get() = attr("BOD") + attr("WIL") + improvement("PhysicalCMRecovery")

    private fun sum(attrs: List<String>, vararg types: String) =
        attrs.sumOf { attr(it) } + standardRound(types.sumOf { improvements.valueOf(it) })

    /**
     * Resistance pools by their name in Chummer's print output. Each is the listed attributes plus the
     * improvements of the listed types. Spell defences also take the general "SpellResistance" improvement.
     * Immunity to toxins and pathogens is not handled yet.
     */
    fun resistances(): Map<String, Int> {
        val bw = listOf("BOD", "WIL")
        val m = linkedMapOf<String, Int>()
        m["judgeintentionsresist"] = sum(listOf("CHA", "WIL"), "JudgeIntentions", "JudgeIntentionsDefense")
        m["fatigueresist"] = sum(bw, "FatigueResist")
        m["radiationresist"] = sum(bw, "RadiationResist")
        m["sonicresist"] = sum(listOf("WIL"), "SonicResist")
        for (vector in listOf("Contact", "Ingestion", "Inhalation", "Injection")) {
            m["toxin${vector.lowercase()}resist"] = sum(bw, "Toxin${vector}Resist")
            m["pathogen${vector.lowercase()}resist"] = sum(bw, "Pathogen${vector}Resist")
        }
        m["physiologicaladdictionresistfirsttime"] = sum(bw, "PhysiologicalAddictionFirstTime")
        m["physiologicaladdictionresistalreadyaddicted"] = sum(bw, "PhysiologicalAddictionAlreadyAddicted")
        m["psychologicaladdictionresistfirsttime"] = sum(listOf("LOG", "WIL"), "PsychologicalAddictionFirstTime")
        m["psychologicaladdictionresistalreadyaddicted"] = sum(listOf("LOG", "WIL"), "PsychologicalAddictionAlreadyAddicted")
        m["indirectdefenseresist"] = dodge
        m["directmanaresist"] = sum(listOf("WIL"), "SpellResistance", "DirectManaSpellResist")
        m["directphysicalresist"] = sum(listOf("BOD"), "SpellResistance", "DirectPhysicalSpellResist")
        m["detectionspellresist"] = sum(listOf("LOG", "WIL"), "SpellResistance", "DetectionSpellResist")
        for (a in listOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL")) {
            m["decrease${a.lowercase()}resist"] =
                sum(listOf(a, "WIL"), "SpellResistance", "Decrease${a}Resist")
        }
        m["illusionmanaresist"] = sum(listOf("LOG", "WIL"), "SpellResistance", "ManaIllusionResist")
        m["illusionphysicalresist"] = sum(listOf("LOG", "INT"), "SpellResistance", "PhysicalIllusionResist")
        m["manipulationmentalresist"] = sum(listOf("LOG", "WIL"), "SpellResistance", "MentalManipulationResist")
        m["manipulationphysicalresist"] = sum(listOf("BOD", "STR"), "SpellResistance", "PhysicalManipulationResist")
        return m
    }
}
