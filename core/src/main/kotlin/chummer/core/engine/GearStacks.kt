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

    /**
     * GUIDs of the gear sitting in a clip of some weapon. Those rounds belong to the clip, whatever the item's own `location` says: the app
     * ties a loaded stack to its clip by the clip's `id` and does not mark the item itself, so merging or topping up by comparing items alone
     * would fold the loaded rounds into a loose stack and leave the clip counting rounds of nothing.
     */
    fun loadedIds(root: Element): Set<String> {
        val clips = root.getElementsByTagName("clip")
        return (0 until clips.length).mapNotNull { (clips.item(it) as Element).t("id").lowercase().takeIf { id -> id.isNotEmpty() } }.toSet()
    }

    private fun guid(g: Element) = g.t("guid").lowercase()

    /** The other loose top-level items that are the same as [g]. [loaded] is [loadedIds]: an item in a clip is nobody's twin. */
    fun twins(all: List<Element>, g: Element, loaded: Set<String> = emptySet()): List<Element> =
        if (!loose(g) || guid(g) in loaded) emptyList() else all.filter { it !== g && loose(it) && guid(it) !in loaded && identical(it, g) }
}
