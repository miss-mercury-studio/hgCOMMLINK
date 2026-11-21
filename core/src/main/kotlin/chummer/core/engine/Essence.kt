package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.Expr
import chummer.core.GameData
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Essence, a port of Character.Essence() and Cyberware.CalculatedESS.
 * Not yet covered: FixedValues and Variable formulas, the essence-hole items, the post-modifier
 * expression setting, and the cyborg fixed-essence improvement.
 */
class EssenceEngine(
    private val improvements: ImprovementSet,
    private val data: GameData,
    private val isCreated: Boolean,
    private val essenceDecimals: Int = 2,
    private val dontRoundInternally: Boolean = false,
    /** Prototype Transhuman: bioware flagged as prototype costs no Essence. */
    private val isPrototypeTranshuman: Boolean = false,
) {
    /** The current Essence: the metatype maximum, penalties, minus every installed item. */
    fun essence(metatypeMax: Double, installed: List<CharacterFile.Augmentation>): Double {
        var ess = metatypeMax
        ess += improvements.valueOf("EssencePenalty")
        ess += improvements.valueOf("EssencePenaltyT100") / 100.0
        ess -= installed.sumOf { calculatedEss(it, hasParent = false) }
        return ess
    }

    /** Essence cost of one item. Children only count when they add to their parent's cost. */
    fun calculatedEss(c: CharacterFile.Augmentation, hasParent: Boolean): Double {
        if (hasParent && !c.addToParent) return 0.0
        if (isPrototypeTranshuman && c.prototypeTranshuman) return 0.0
        val bioware = c.improvementSource == "Bioware"
        var cost = try {
            Expr.eval(c.essFormula, mapOf("Rating" to c.rating.toDouble(), "MinRating" to c.minRating.toDouble()))
        } catch (e: IllegalArgumentException) {
            0.0 // formula forms not supported yet
        }
        var additive = (data.gradeEssence(bioware, c.grade) ?: 1.0) + c.extraEssAdditive
        var multiplicative = c.extraEssMultiplicative
        if (c.suite) additive -= 0.1
        if (c.essDiscount != 0.0) multiplicative *= 1.0 - c.essDiscount * 0.01
        if (c.forceGrade != "None" || c.isGeneware) {
            fun baseMod(type: String) {
                for (i in improvements.matching(type)) additive -= 1.0 - i.value / 100.0
            }
            fun totalMod(type: String) {
                for (i in improvements.matching(type)) multiplicative *= i.value / 100.0
            }
            when {
                !bioware && !c.isGeneware -> {
                    baseMod("CyberwareEssCost")
                    totalMod("CyberwareTotalEssMultiplier")
                    if (!isCreated) {
                        baseMod("CyberwareEssCostNonRetroactive")
                        totalMod("CyberwareTotalEssMultiplierNonRetroactive")
                    }
                }
                bioware && !c.isGeneware -> {
                    baseMod("BiowareEssCost")
                    totalMod("BiowareTotalEssMultiplier")
                    if (!isCreated) {
                        baseMod("BiowareEssCostNonRetroactive")
                        totalMod("BiowareTotalEssMultiplierNonRetroactive")
                    }
                    if (c.category == "Basic") baseMod("BasicBiowareEssCost")
                }
                else -> {
                    baseMod("GenetechEssMultiplier")
                    totalMod("GenetechTotalEssMultiplier")
                }
            }
        }
        cost *= maxOf(0.0, additive * multiplicative)
        if (!dontRoundInternally) cost = BigDecimal(cost).setScale(essenceDecimals, RoundingMode.HALF_UP).toDouble()
        cost += c.children.filter { it.addToParent }.sumOf { calculatedEss(it, hasParent = true) }
        return cost
    }
}
