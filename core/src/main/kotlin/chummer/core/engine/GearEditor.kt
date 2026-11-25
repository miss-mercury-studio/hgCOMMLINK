package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.Expr
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** One gear entry for a picker. [cost] is null when the price formula is beyond what the evaluator reads (it can be listed, not bought). */
data class GearOption(
    val id: String, val name: String, val category: String, val cost: Double?, val avail: String,
    val minRating: Int, val maxRating: Int, val unsupported: List<String>,
    /** Capacity this gear uses when installed in armor, e.g. `[2]`; null when it does not fit in armor. */
    val armorCapacity: Double? = null,
)

object GearCatalog {
    fun list(data: DataPack): List<GearOption> =
        data.byId.entries.filter { it.value.file == "gear.xml" && it.value.element.tagName == "gear" }.mapNotNull { (id, e) ->
            val el = e.element
            // Entries that are only templates for other gear, or that bring child items or weapons along, wait for later slices.
            if (el.child("gears") != null && el.child("gears")!!.children().isNotEmpty()) return@mapNotNull null
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val min = t("minrating").toIntOrNull() ?: 0
            val max = t("rating").toIntOrNull() ?: 0
            val unsupported = el.child("bonus")?.let { BonusHandlers.apply(it, BonusContext("Gear", "", 1)).unhandled.distinct() }.orEmpty()
            GearOption(id, t("name"), t("category"), GearEditor.unitCost(el, if (max > 0) maxOf(min, 1) else 0), t("avail"), min, max, unsupported,
                t("armorcapacity").takeIf { it.startsWith("[") }?.let { CyberwareEditor.formula(it.removePrefix("[").removeSuffix("]"), maxOf(min, 1)) })
        }.sortedBy { it.name.lowercase() }
}

/**
 * Adds gear to a character in the layout Chummer writes (checked field for field against Viper's gear, see GearEditorTest),
 * with the improvements its bonus grants. Gear that brings weapons or child items along is not supported yet.
 */
class GearEditor(private val file: CharacterFile, private val data: GameData, private val settings: CharacterSettings? = null) {
    private val doc get() = file.doc
    private val root get() = file.root

    companion object {
        /** The price of one unit at a rating: the data's cost formula with Rating replaced. Null when it is not plain arithmetic. */
        fun unitCost(entry: Element, rating: Int): Double? {
            val text = entry.child("cost")?.textContent?.trim().orEmpty()
            if (text.isEmpty()) return 0.0
            return Expr.evalOrNull(text.replace("{Rating}", rating.toString()).replace("Rating", rating.toString()))
        }

        /**
         * An availability such as `(Rating * 3)R` worked out at a rating (`3R`); anything the evaluator cannot read is shown as it is.
         * A leading `+` or a trailing R or F (restricted, forbidden) is kept.
         */
        fun availText(avail: String, rating: Int): String {
            val t = avail.trim()
            if (t.isEmpty() || t == "0") return t
            val m = Regex("""^(.*?)([RF]?)$""").find(t) ?: return t
            val body = m.groupValues[1].trim()
            val value = chummer.core.Expr.evalOrNull(body.replace("{Rating}", rating.toString()).replace("Rating", rating.toString())) ?: return t
            val n = if (value == Math.rint(value)) value.toLong().toString() else value.toString()
            return (if (body.startsWith("+") && value >= 0) "+" else "") + n + m.groupValues[2]
        }

        /** What the whole purchase costs: unit price times quantity, divided by the pack size (`costfor`) when there is one. */
        fun totalCost(entry: Element, rating: Int, qty: Double): Double? {
            val unit = unitCost(entry, rating) ?: return null
            val per = entry.child("costfor")?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            return unit * qty / per
        }
    }

    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    /** Add a gear entry; returns the new item's GUID. [rating] is clamped to the entry's range. */
    fun addGear(entryId: String, rating: Int? = null, qty: Double = 1.0, extra: String = "", into: Element? = null, parentId: String = ""): String {
        val entry = data[entryId]?.element ?: error("No gear with id $entryId in the game data")
        require(entry.tagName == "gear") { "Not a gear entry" }
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val min = d("minrating").toIntOrNull() ?: 0
        val max = d("rating").toIntOrNull() ?: 0
        val chosen = if (max <= 0) 0 else (rating ?: maxOf(min, 1)).coerceIn(maxOf(min, 0), max)
        val g = el("gear")
        fun add(tag: String, text: String? = null) = g.appendChild(el(tag, text))
        fun flag(tag: String, default: Boolean = false) = if (d(tag).equals("True", true) || (d(tag).isEmpty() && default)) "True" else "False"
        add("sourceid", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("category", d("category"))
        add("capacity", d("capacity")); add("armorcapacity", d("armorcapacity"))
        add("minrating", d("minrating")); add("maxrating", if (max > 0) max.toString() else "")
        add("rating", chosen.toString())
        add("qty", if (qty == Math.rint(qty)) qty.toLong().toString() + (if (qty == 1.0) ".0" else "") else qty.toString())
        add("avail", d("avail")); add("cost", d("cost"))
        d("costfor").takeIf { it.isNotEmpty() }?.let { add("costfor", it) }
        add("weight", d("weight")); add("extra", extra)
        add("bonded", "False"); add("equipped", "True"); add("wirelesson", "False"); add("stolen", "False")
        // Chummer keeps only limit modifiers of a gear's bonus in the saved item (the rest is turned into improvements and dropped),
        // and leaves the wireless bonus empty (seen in Viper's gear).
        val savedBonus = el("bonus")
        entry.child("bonus")?.children("limitmodifier")?.forEach { savedBonus.appendChild(doc.importNode(it, true)) }
        g.appendChild(savedBonus)
        g.appendChild(el("wirelessbonus"))
        for (tag in listOf("weaponbonus", "flechetteweaponbonus")) {
            val node = entry.child(tag)
            g.appendChild(if (node != null) doc.importNode(node, true) else el(tag))
        }
        add("source", d("source")); add("page", d("page"))
        add("isflechetteammo", flag("isflechetteammo")); add("ammoforweapontype", d("ammoforweapontype"))
        add("canformpersona", d("canformpersona")); add("devicerating", d("devicerating")); add("gearname"); add("forcedvalue")
        add("matrixcmfilled", "0"); add("matrixcmbonus", "0"); add("parentid", parentId); add("allowrename", flag("allowrename")); add("children")
        add("location"); add("notes"); add("notesColor", NOTES_COLOR); add("discountedcost", "False")
        add("programlimit", d("programs")); add("overclocked", "None")
        for (tag in listOf("attack", "sleaze", "dataprocessing", "firewall", "attributearray", "modattack", "modsleaze", "moddataprocessing", "modfirewall", "modattributearray")) add(tag, d(tag))
        add("canswapattributes", flag("canswapattributes")); add("active", "False"); add("homenode", "False")
        val list = into ?: (root.child("gears") ?: el("gears").also { root.appendChild(it) })
        add("sortorder", list.children("gear").size.toString())
        // The bonus is applied for the rating chosen.
        val bonus = entry.child("bonus")
        if (bonus != null && bonus.children().isNotEmpty()) {
            val result = BonusHandlers.apply(bonus, BonusContext("Gear", guid, maxOf(chosen, 1), extra, d("name"), objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        list.appendChild(g)
        return guid
    }

    private fun topGear() = root.child("gears")?.children("gear").orEmpty()

    /** The loose top-level gear item with this GUID. */
    fun stack(guid: String): Element? = topGear().firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /**
     * Add gear the way Chummer's picker does: ammunition joins an identical loose stack (same name, rating and weapon type) instead of starting a new one.
     * Returns the GUID of the stack that now holds it.
     */
    fun addOrStack(entryId: String, rating: Int? = null, qty: Double = 1.0, extra: String = ""): String {
        val guid = addGear(entryId, rating, qty, extra)
        val added = stack(guid)!!
        if (!GearStacks.isAmmo(added)) return guid
        val into = GearStacks.twins(topGear(), added).firstOrNull() ?: return guid
        GearStacks.setQuantity(into, GearStacks.quantity(into) + GearStacks.quantity(added))
        removeGear(guid)
        return into.text("guid")!!.trim()
    }

    /** Fold every identical loose stack into this one, adding their quantities. False when there is none to merge. */
    fun mergeStacks(guid: String): Boolean {
        val target = stack(guid) ?: return false
        val others = GearStacks.twins(topGear(), target)
        if (others.isEmpty()) return false
        GearStacks.setQuantity(target, GearStacks.quantity(target) + others.sumOf { GearStacks.quantity(it) })
        for (o in others) removeGear(o.text("guid")!!.trim())
        return true
    }

    /** The gear entry with this name (and category, when given), as the data's `usegear` nodes name them. */
    fun findGear(name: String, category: String = ""): String? =
        (data as? DataPack)?.byId?.entries?.firstOrNull {
            it.value.file == "gear.xml" && it.value.element.tagName == "gear" && it.value.name == name &&
                (category.isEmpty() || it.value.category == category)
        }?.key

    /**
     * Add the gear a data `usegear` node names, into [into]. The node is either `<usegear>Name</usegear>` or has `name`,
     * `category` and `rating` children. Returns the new item's GUID, or null when no such gear exists.
     */
    fun addUseGear(u: Element, into: Element, parentId: String): String? {
        val name = u.child("name")?.textContent?.trim() ?: u.textContent.trim()
        val id = findGear(name, u.child("category")?.textContent?.trim().orEmpty()) ?: return null
        val guid = addGear(id, rating = u.child("rating")?.textContent?.trim()?.toIntOrNull(), into = into, parentId = parentId)
        // A part that comes with the item is already paid for.
        into.children("gear").lastOrNull { it.text("guid")?.trim() == guid }?.child("cost")?.textContent = "0"
        return guid
    }

    /** Remove a gear item wherever it is installed (also inside armor, weapons or ware) and the improvements it granted. */
    fun removeGear(guid: String): Boolean {
        fun find(e: Element): Element? {
            for (c in e.children()) {
                if (c.tagName == "gear" && c.text("guid")?.trim().equals(guid, true)) return c
                if (c.tagName != "bonus" && c.tagName != "improvements") find(c)?.let { return it }
            }
            return null
        }
        val item = find(root) ?: return false
        val imps = root.child("improvements")
        if (imps != null) {
            val ids = HashSet<String>()
            fun walk(e: Element) { e.text("guid")?.trim()?.let { ids += it.lowercase() }; for (c in e.children()) if (c.tagName != "bonus") walk(c) }
            walk(item)
            for (i in imps.children("improvement").filter { it.text("sourcename")?.trim()?.lowercase() in ids }) imps.removeChild(i)
        }
        item.parentNode.removeChild(item)
        return true
    }
}
