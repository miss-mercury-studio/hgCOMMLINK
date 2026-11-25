package chummer.core.engine

import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * When two gear items are the same thing in two stacks, as Chummer decides it (`IsIdenticalToOtherGear`): the same name, category, rating and extra
 * (for ammunition, the kind of weapon it is for), and the same items inside them. Their own name and notes do not matter when merging.
 */
object GearStacks {
    private fun Element.t(tag: String) = text(tag)?.trim().orEmpty()
    fun quantity(g: Element) = g.t("qty").toDoubleOrNull() ?: 0.0
    fun format(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()
    fun setQuantity(g: Element, v: Double) { g.child("qty")?.textContent = format(v) }

    fun isAmmo(g: Element) = g.t("category") == "Ammunition" || g.t("ammoforweapontype").isNotEmpty()

    /** Loaded rounds are a clip's own, never part of a loose stack. */
    private fun loose(g: Element) = g.t("location") != "loaded"

    private fun inside(g: Element) = g.child("children")?.children("gear").orEmpty()

    fun identical(a: Element, b: Element): Boolean {
        if (a.t("name") != b.t("name") || a.t("category") != b.t("category") || a.t("rating") != b.t("rating") || a.t("extra") != b.t("extra")) return false
        val x = inside(a)
        val y = inside(b)
        return x.size == y.size && x.indices.all { quantity(x[it]) == quantity(y[it]) && identical(x[it], y[it]) }
    }

    /** The other loose top-level items that are the same as [g]. */
    fun twins(all: List<Element>, g: Element): List<Element> =
        if (!loose(g)) emptyList() else all.filter { it !== g && loose(it) && identical(it, g) }
}
