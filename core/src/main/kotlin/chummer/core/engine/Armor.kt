package chummer.core.engine

import chummer.core.CharacterFile.ArmorItem
import chummer.core.Expr

/**
 * Total armor and armor encumbrance, ported from Chummer's `GetArmorRatingWithImprovement` and
 * `ArmorEncumbrance`.
 *
 * Rules:
 *  - only equipped armor counts; the best non-stacking armor (value not starting with `+`) wins;
 *  - stacking pieces (`+2`) add to it, up to the wearer's STR (unless the settings uncap that);
 *  - improvements of type Armor (from ware, qualities and so on) add to every armor; improvements that came from
 *    an equipped armor or one of its mods count only for that armor, so each armor is judged with its own;
 *  - encumbrance: the burden of stacking pieces beyond STR + 1 costs one point of AGI and REA per two over.
 *
 * Not yet read: the "Custom Fit (Stack)" mod, which ties a stacking piece to one named armor.
 */
class ArmorEngine(
    armors: List<ArmorItem>,
    private val improvements: ImprovementSet,
    private val strength: Int,
    private val uncappedAccessories: Boolean = false,
    private val noEncumbrance: Boolean = false,
    private val armorDegradation: Boolean = false,
) {
    private val worn = armors.filter { it.equipped }

    private fun own(a: ArmorItem): Int {
        val text = a.armorValue.removePrefix("+").trim()
        if (text.isEmpty()) return 0
        return standardRound(Expr.eval(text, mapOf("Rating" to a.rating.toDouble())))
    }

    private fun total(a: ArmorItem, forEncumbrance: Boolean = false): Int {
        if (forEncumbrance && !a.isStacking) return 0
        var t = own(a) + a.mods.filter { it.equipped && (!forEncumbrance || it.encumbrance) }.sumOf { it.armor }
        if (armorDegradation) t -= a.damage
        return maxOf(t, 0)
    }

    /** Armor value against one damage type: "Armor" (general), "FireArmor", "ElectricityArmor", and so on. */
    fun rating(type: String = "Armor"): Int {
        var base = improvements.valueOf(type)
        val used = improvements.matching(type).toMutableList()
        if (type != "Armor") {
            base += improvements.valueOf("Armor")
            used += improvements.matching("Armor")
        }
        if (worn.isEmpty()) return standardRound(base)

        // Improvements that came from a worn armor (or its mods) belong to that armor alone.
        var general = base
        val perArmor = worn.associateWith { base }.toMutableMap()
        for (imp in used) {
            if (imp.source != "Armor" && imp.source != "ArmorMod") continue
            val owner = worn.firstOrNull { it.guid == imp.sourceName }
                ?: worn.firstOrNull { a -> a.mods.any { it.guid == imp.sourceName } }
                ?: continue
            val v = imp.value * imp.rating
            general -= v
            for (a in worn) if (a !== owner) perArmor[a] = perArmor.getValue(a) - v
        }

        val cap = if (uncappedAccessories) Int.MAX_VALUE else strength
        val stack = worn.associateWith { 0 }.toMutableMap()
        var naked = 0
        for (a in worn) {
            if (!a.isStacking) continue
            for (inner in worn) {
                if (inner === a || inner.isStacking) continue
                stack[inner] = stack.getValue(inner) + total(a)
            }
            if (naked < cap) naked = minOf(naked + total(a), cap)
        }
        for (a in worn) if (stack.getValue(a) > cap) stack[a] = cap

        var highest = 0
        var best: ArmorItem? = null
        for (a in worn) {
            if (a.isStacking) continue
            val v = total(a) + stack.getValue(a) + standardRound(perArmor.getValue(a))
            if (v > highest) { highest = v; best = a }
        }
        return if (best != null) highest else naked + standardRound(general)
    }

    /** The modifier applied to AGI and REA: zero or negative. */
    fun encumbrance(): Int {
        if (noEncumbrance) return 0
        if (worn.isEmpty() || worn.none { it.encumbrance }) return 0
        val stack = worn.associateWith { 0 to 0 }.toMutableMap()
        var naked = 0
        for (a in worn) {
            if (!a.isStacking) continue
            val loopStack = total(a)
            val loopEnc = total(a, forEncumbrance = true)
            for (inner in worn) {
                if (inner === a || inner.isStacking) continue
                val (s, e) = stack.getValue(inner)
                stack[inner] = (s + loopStack) to (if (a.encumbrance) e + loopEnc else e)
            }
            if (a.encumbrance) naked += loopEnc
        }
        if (!uncappedAccessories) {
            for (a in worn) {
                val (s, e) = stack.getValue(a)
                if (s > strength) stack[a] = strength to e
            }
        }

        var highest = 0
        var lowestEnc = Int.MAX_VALUE
        var best: ArmorItem? = null
        for (a in worn) {
            if (a.isStacking) continue
            val (s, e) = stack.getValue(a)
            val t = total(a) + s
            if (t >= highest && (t > highest || e < lowestEnc)) { highest = t; lowestEnc = e; best = a }
        }
        if (best == null) lowestEnc = naked
        return if (lowestEnc > strength + 1) (strength - lowestEnc) / 2 else 0
    }
}
