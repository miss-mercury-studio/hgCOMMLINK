package chummer.core.engine

import chummer.core.child
import chummer.core.children
import org.w3c.dom.Element

/**
 * Chummer's rule for what may be fitted to what: an accessory's `<required><weapondetails>` and `<forbidden><weapondetails>` are filters checked against the
 * weapon's own data entry (a port of `ProcessFilterOperationNode`). Without it every accessory would be offered for every weapon: a Spare Clip for a revolver,
 * or one made for an HK Urban Fighter for a machine pistol.
 *
 * A filter is a list of conditions that must all hold (an AND). `OR` and `AND` blocks group conditions, `NOR` and `NAND` negate a group, and `NONE` is true
 * when there is nothing to check. A condition names a field of the weapon (`name`, `category`, `ammo`, ...) and the value to look for, with an `operation`
 * attribute: equals (the default), `contains` (any case), `greaterthan`, `greaterthanequals`, `lessthan`, `lessthanequals`, `notequals`, or `exists`. `NOT`
 * inverts a condition, and `checktype="all"` makes a field that appears several times pass only when every one does.
 */
object WeaponFilter {
    /** True when a weapon (its data entry [weapon]) passes the filter [filter]: the contents of a `weapondetails` node. */
    fun passes(weapon: Element, filter: Element): Boolean = group(weapon, filter, orNode = false)

    /** Whether an accessory's entry may be fitted to a weapon: nothing forbidden matches, and what is required does. */
    fun accessoryFits(accessory: Element, weapon: Element): Boolean {
        val forbidden = accessory.child("forbidden")?.child("weapondetails")
        if (forbidden != null && passes(weapon, forbidden)) return false
        val required = accessory.child("required")?.child("weapondetails")
        return required == null || passes(weapon, required)
    }

    private fun group(parent: Element, node: Element, orNode: Boolean): Boolean {
        // An AND group fails at the first condition that fails; an OR group passes at the first that passes. An empty AND passes and an empty OR does not.
        for (child in node.children()) {
            val invert = child.hasAttribute("NOT")
            val ok = when (child.tagName.uppercase()) {
                "OR" -> group(parent, child, true) != invert
                "NOR" -> group(parent, child, true) == invert
                "AND" -> group(parent, child, false) != invert
                "NAND" -> group(parent, child, false) == invert
                "NONE" -> invert
                else -> condition(parent, child, invert)
            }
            if (orNode && ok) return true
            if (!orNode && !ok) return false
        }
        return !orNode
    }

    private fun condition(parent: Element, op: Element, invert: Boolean): Boolean {
        val tag = op.tagName
        val targets = parent.children(tag)
        val operation = op.getAttribute("operation").ifEmpty { "==" }
        if (operation == "exists") return targets.isNotEmpty() != invert
        val all = op.getAttribute("checktype") == "all"
        val want = op.textContent.trim()
        val orChildren = op.hasAttribute("OR")
        for (target in targets) {
            val sub: Boolean = if (target.children().isNotEmpty()) {
                if (op.children().isNotEmpty()) group(target, op, orChildren) != invert else invert
            } else {
                val have = target.textContent.trim()
                if (have.isEmpty() || want.isEmpty()) {
                    if ((have.isEmpty() == want.isEmpty()) && (operation == "==" || operation.equals("equals", true))) !invert else invert
                } else compare(operation, have, want, invert)
            }
            if (all) { if (!sub) return false } else if (sub) return true
        }
        return all
    }

    private fun compare(operation: String, have: String, want: String, invert: Boolean): Boolean {
        val a = have.toIntOrNull(); val b = want.toIntOrNull()
        return when (operation.uppercase()) {
            "DOESNOTEQUAL", "NOTEQUALS", "!=", "<>" -> (have == want) == invert
            "LIKE", "CONTAINS" -> have.contains(want, ignoreCase = true) != invert
            "GREATERTHAN", ">" -> (a != null && b != null && a > b) != invert
            "GREATERTHANEQUALS", "GREATERTHANOREQUALS", "GREATERTHANEQUALTO", "GREATERTHANOREQUALTO", ">=" -> (a != null && b != null && a >= b) != invert
            "LESSTHAN" -> !(a != null && b != null && a >= b) != invert
            "LESSTHANEQUALS", "LESSTHANEQUALTO", "LESSTHANOREQUALS", "LESSTHANOREQUALTO" -> !(a != null && b != null && a > b) != invert
            else -> (have == want) != invert
        }
    }
}
