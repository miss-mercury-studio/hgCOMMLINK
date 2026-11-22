package chummer.core.engine

import chummer.core.GameData
import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.text
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Movement, a port of Chummer's `CalculatedMovement` and `FullMovement`. A metatype stores walk, run and sprint as
 * "ground/swim/fly" rates; walk and run are multiplied by AGI (swimming averages AGI and STR), sprint is metres per hit.
 *
 * Not covered: the cyberleg movement setting (the legs' own AGI), the alternate attribute set of shapeshifters, and
 * encumbrance.
 */
class Movement(private val set: ImprovementSet, private val attrs: Map<String, AttributeResult>, private val walk: String, private val run: String, private val sprint: String) {
    private fun rate(text: String, index: Int) = text.split('/').filter { it.isNotEmpty() }.getOrNull(index)?.trim()?.toDoubleOrNull() ?: 0.0

    /** A single rate with the improvements that replace it (the highest wins). */
    private fun rate(kind: String, text: String, type: String): Double {
        val index = when (type) { "Fly" -> 2; "Swim" -> 1; else -> 0 }
        val overrides = set.matching(kind, type).map { it.value }
        return overrides.maxOrNull() ?: rate(text, index)
    }

    private fun fmt(v: Double) = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.ROOT)).format(v)

    fun calculated(type: String): String {
        var sprintV = rate("SprintSpeed", sprint, type) + set.valueOf("SprintBonus", type) / 100.0
        var runV = rate("RunSpeed", run, type) + set.valueOf("RunMultiplier", type)
        var walkV = rate("WalkSpeed", walk, type) + set.valueOf("WalkMultiplier", type)
        if (walkV == 0.0 && runV == 0.0 && sprintV == 0.0) return "0"
        sprintV *= 1.0 + set.valueOf("SprintBonusPercent", type) / 100.0
        runV *= 1.0 + set.valueOf("RunMultiplierPercent", type) / 100.0
        walkV *= 1.0 + set.valueOf("WalkMultiplierPercent", type) / 100.0
        val agi = (attrs["AGI"]?.totalValue ?: 0).toDouble()
        if (type == "Swim") {
            val factor = (agi + (attrs["STR"]?.totalValue ?: 0)) * 0.5
            walkV *= factor; runV *= factor
        } else { walkV *= agi; runV *= agi }
        var s = when {
            walkV != 0.0 -> if (runV != 0.0) fmt(walkV) + "/" + fmt(runV) else fmt(walkV)
            runV != 0.0 || sprintV != 0.0 -> fmt(runV)
            else -> ""
        }
        if (sprintV != 0.0) { if (s.isNotEmpty()) s += "; "; s += fmt(sprintV) + "m / hit" }
        return s
    }

    /** Ground, then swim, then fly, as the character sheet prints it. */
    fun full(): String = buildList {
        calculated("Ground").takeIf { it.isNotEmpty() && it != "0" }?.let { add(it) }
        calculated("Swim").takeIf { it.isNotEmpty() && it != "0" }?.let { add("Swim: $it") }
        calculated("Fly").takeIf { it.isNotEmpty() && it != "0" }?.let { add("Fly: $it") }
    }.joinToString(", ")

    companion object {
        fun of(c: CharacterFile, data: GameData, set: ImprovementSet, attrs: Map<String, AttributeResult>): Movement? {
            val metatype = data[c.root.text("metatypeid")?.trim().orEmpty()]?.element ?: return null
            val variant = data[c.root.text("metavariantid")?.trim().orEmpty()]?.element
            fun s(tag: String) = variant?.child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: metatype.child(tag)?.textContent?.trim().orEmpty()
            if (s("movement") == "Special") return null
            return Movement(set, attrs, s("walk"), s("run"), s("sprint"))
        }
    }
}
