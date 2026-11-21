package chummer.core.engine

import chummer.core.data.CharacterSettings
import kotlin.math.ceil

/**
 * Stats derived from attributes and improvements: limits, initiative, condition monitors,
 * lift and carry, and the simple attribute-sum pools. Formulas follow Shadowrun 5e and were
 * checked against Chummer's own print output (see GoldenExportDerivedTest).
 *
 * Not yet covered: matrix and astral initiative, armor and the armor-based resistance pools,
 * movement, wound modifiers, and limit modifiers that depend on a condition.
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

    /** Lift and carry limits come from expressions in the settings, for example `{STR} * 15`. */
    val liftWeight: Int get() = standardRound(settings.evaluate("liftlimitexpression", mapOf("STR" to attr("STR").toDouble())))
    val carryWeight: Int get() = standardRound(settings.evaluate("carrylimitexpression", mapOf("STR" to attr("STR").toDouble())))

    val composure: Int get() = attr("CHA") + attr("WIL") + improvement("Composure")
    val judgeIntentions: Int get() = attr("INT") + attr("CHA") + improvement("JudgeIntentions")
    val memory: Int get() = attr("LOG") + attr("WIL") + improvement("Memory")
    val surprise: Int get() = attr("REA") + attr("INT") + improvement("Surprise")
    val liftAndCarry: Int get() = attr("STR") + attr("BOD") + improvement("LiftAndCarry")
    val fatigueResist: Int get() = attr("BOD") + attr("WIL") + improvement("FatigueResist")
    val dodge: Int get() = attr("REA") + attr("INT") + improvement("Dodge")
    val directManaResist: Int get() = attr("WIL") + improvement("DirectManaSpellResist")
    val physicalCMNaturalRecovery: Int get() = attr("BOD") + attr("WIL") + improvement("PhysicalCMRecovery")
}
