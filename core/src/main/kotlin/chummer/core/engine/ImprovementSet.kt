package chummer.core.engine

import kotlin.math.abs
import kotlin.math.floor

/** Chummer's `StandardRound`: halves round away from zero. */
fun standardRound(x: Double): Int = (floor(abs(x) + 0.5) * if (x < 0) -1 else 1).toInt()

/**
 * The improvements active on a character and the queries Chummer runs over them
 * (a port of `ImprovementManager.ValueOf` and `AugmentedValueOf`).
 *
 * Rules, in the order Chummer applies them:
 *  - only enabled improvements of the requested type count;
 *  - unless asked otherwise, only unconditional ones count (no condition, or the one that
 *    matches whether the character is in career or creation mode);
 *  - `addToRating` improvements are kept apart from the rest;
 *  - improvements with a unique name are not simply added: `precedence0` takes the highest,
 *    `precedence1` takes the sum of its group, and any other unique name takes the highest per name;
 *  - custom (user-entered) improvements are added after the normal ones.
 */
class ImprovementSet(val all: List<Improvement>, private val isCareer: Boolean) {

    private fun unconditional(i: Improvement): Boolean {
        if (i.condition.isEmpty()) return true
        val mode = if (isCareer) "career" else "create"
        val xpath = if (isCareer) "/character/created" else "/character/created = false"
        return i.condition == mode || i.condition == xpath
    }

    /** The improvements a query would consider, before combining. */
    fun matching(type: String, name: String = "", addToRating: Boolean = false, unconditionalOnly: Boolean = true): List<Improvement> =
        all.filter {
            it.enabled && it.type == type && it.addToRating == addToRating &&
                (!unconditionalOnly || unconditional(it)) &&
                (name.isEmpty() || it.improvedName == name)
        }

    /** Sum of `value * rating` (Chummer's `ValueOf`). */
    fun valueOf(type: String, name: String = "", addToRating: Boolean = false): Double =
        combine(matching(type, name, addToRating)) { it.value * it.rating }

    /** Sum of `augmented * rating` (Chummer's `AugmentedValueOf`). */
    fun augmentedValueOf(type: String, name: String = "", addToRating: Boolean = false): Double =
        combine(matching(type, name, addToRating)) { it.augmented * it.rating }

    /** Combine already-filtered improvements using Chummer's unique-name and custom rules. */
    fun combine(list: List<Improvement>, getter: (Improvement) -> Double): Double {
        val normal = combineGroup(list.filter { !it.custom }, getter, custom = false)
        val custom = combineGroup(list.filter { it.custom }, getter, custom = true)
        return normal + custom
    }

    private fun combineGroup(list: List<Improvement>, getter: (Improvement) -> Double, custom: Boolean): Double {
        var total = 0.0
        for ((_, group) in list.groupBy { it.improvedName }) {
            var value = 0.0
            val unique = group.filter { it.uniqueName.isNotEmpty() }
            value += group.filter { it.uniqueName.isEmpty() }.sumOf(getter)
            if (unique.isNotEmpty()) {
                val names = unique.map { it.uniqueName }.toSet()
                if (!custom && "precedence0" in names) {
                    var highest = unique.filter { it.uniqueName == "precedence0" }.maxOf(getter)
                    if ("precedence-1" in names) highest += unique.filter { it.uniqueName == "precedence-1" }.sumOf(getter)
                    if (value < highest) value = highest
                } else if (!custom && "precedence1" in names) {
                    val sum = unique.filter { it.uniqueName == "precedence1" || it.uniqueName == "precedence-1" }.sumOf(getter)
                    if (value < sum) value = sum
                } else {
                    for (n in names) value += unique.filter { it.uniqueName == n }.maxOf(getter)
                }
            }
            total += value
        }
        return total
    }
}
