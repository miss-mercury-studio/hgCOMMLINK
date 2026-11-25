package chummer.core.engine

import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * What can be plugged into a piece of gear, as Chummer's gear picker decides it for a parent item. A parent that lists `addoncategory` takes gear of those
 * categories; a parent whose `capacity` has no brackets gives capacity, which plug-ins with a bracketed capacity use up. Each candidate's `required` and
 * `forbidden` parent or gear details must also allow this parent. A parent with neither takes no plug-ins.
 */
object GearPlugins {
    private fun Element.t(tag: String) = text(tag)?.trim().orEmpty()

    fun categories(entry: Element) = entry.children("addoncategory").map { it.textContent.trim() }.filter { it.isNotEmpty() }

    /** The capacity a gear item gives (its `capacity` before any `/[`), or null when it gives none. */
    fun capacityGiven(entry: Element, rating: Int): Double? {
        val text = entry.t("capacity").substringBefore("/[")
        if (text.isEmpty() || text.contains('[')) return null
        return CyberwareEditor.formula(text, rating.coerceAtLeast(1))
    }

    /** What a plug-in uses of its parent's capacity: `[2]` is 2, anything without brackets is 0. */
    fun capacityUsed(entry: Element, rating: Int): Double {
        val text = entry.t("capacity").substringAfter("/", entry.t("capacity")).trim()
        if (!text.startsWith("[")) return 0.0
        return CyberwareEditor.formula(text.removePrefix("[").removeSuffix("]"), rating.coerceAtLeast(1)) ?: 0.0
    }

    /** Whether this gear can take plug-ins at all (cheap, for the sheet). */
    fun accepts(entry: Element) = categories(entry).isNotEmpty() || capacityGiven(entry, 1) != null

    /** [parent] is the item as saved; [entry] is its data entry (or the saved item when the entry is gone). */
    fun options(data: DataPack, parent: Element, entry: Element): List<GearOption> {
        val categories = categories(entry).toSet()
        val rating = parent.t("rating").toIntOrNull() ?: 0
        val given = capacityGiven(entry, rating)
        if (categories.isEmpty() && given == null) return emptyList()
        val used = parent.child("children")?.children("gear").orEmpty().sumOf { capacityUsed(it, it.t("rating").toIntOrNull() ?: 0) }
        val left = given?.let { it - used }
        return data.byId.entries.filter { it.value.file == "gear.xml" && it.value.element.tagName == "gear" }.mapNotNull { (id, e) ->
            val el = e.element
            if (el.child("hide") != null || el.child("gears")?.children()?.isNotEmpty() == true) return@mapNotNull null
            // "Custom Item" is a blank for a made-up name and price, which the app cannot work out.
            if (el.t("category") == "Custom") return@mapNotNull null
            if (categories.isNotEmpty() && el.t("category") !in categories) return@mapNotNull null
            val need = capacityUsed(el, 1)
            if (left != null && (!el.t("capacity").contains('[') || need > left + 1e-9)) return@mapNotNull null
            for ((group, wantPass) in listOf("forbidden" to false, "required" to true)) for (kind in listOf("parentdetails", "geardetails")) {
                val node = el.child(group)?.child(kind) ?: continue
                if (WeaponFilter.passes(entry, node) != wantPass) return@mapNotNull null
            }
            fun t(tag: String) = el.t(tag)
            val min = t("minrating").toIntOrNull() ?: 0
            val max = t("rating").toIntOrNull() ?: 0
            val unsupported = el.child("bonus")?.let { BonusHandlers.apply(it, BonusContext("Gear", "", 1)).unhandled.distinct() }.orEmpty()
            // `armorCapacity` carries the capacity the plug-in uses of its parent here.
            GearOption(id, t("name"), t("category"), GearEditor.unitCost(el, if (max > 0) maxOf(min, 1) else 0), t("avail"), min, max, unsupported, need.takeIf { left != null },
                needsRestricted = el.child("bonus")?.child("selectrestricted") != null, restrictedChoices = data.licenses)
        }.sortedBy { it.name.lowercase() }
    }
}
