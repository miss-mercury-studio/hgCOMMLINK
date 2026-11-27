package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** One cyberware or bioware entry for a picker. */
data class WareOption(
    val id: String, val name: String, val category: String, val isBioware: Boolean, val cost: Double?, val essence: Double?, val avail: String,
    val minRating: Int, val maxRating: Int, val forcedGrade: String, val problem: String?, val unsupported: List<String>, val addsToParent: Boolean = false,
    /** The capacity it uses of the item it plugs into, as the data writes it (`[8]`). */
    val capacity: String = "",
    /** What the item asks the player to choose when bought: "skill" (one of the character's active skills), "text" (a few words), or "" for nothing. */
    val choice: String = "",
)

/**
 * Adds cyberware and bioware to a character in the layout Chummer writes (checked against Viper's items in CyberwareEditorTest),
 * with the improvements its bonus grants. Items that come with their own parts, and plugs into other items, are not supported yet.
 */
class CyberwareEditor(private val file: CharacterFile, private val data: GameData) {
    private val doc get() = file.doc
    private val root get() = file.root

    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    companion object {
        /** The grades a kind of ware offers, with cost, essence and availability modifiers, from the data. */
        fun grades(data: DataPack, bioware: Boolean): List<Element> =
            data.byId.values.filter { it.file == (if (bioware) "bioware.xml" else "cyberware.xml") && it.element.tagName == "grade" }.map { it.element }

        fun problem(entry: Element): String? {
            if (entry.child("forced") != null) return "needs a choice"
            if (entry.child("requireparent") != null) return "plugs into another item"
            return null
        }

        /** A formula such as `FixedValues(4000,6000,10000)` picks the entry for the rating; anything else is plain arithmetic. */
        fun formula(text: String, rating: Int, minRating: Int = 0): Double? {
            val t = text.trim().replace("MinRating", minRating.toString())
            if (t.isEmpty()) return 0.0
            Regex("""^FixedValues\((.*)\)$""").find(t)?.let { m ->
                val values = m.groupValues[1].split(',').map { it.trim().removePrefix("[").removeSuffix("]") }
                val v = values.getOrNull((rating - 1).coerceIn(0, values.size - 1)) ?: return null
                return v.toDoubleOrNull()
            }
            return chummer.core.Expr.evalOrNull(t.replace("{Rating}", rating.toString()).replace("Rating", rating.toString()))
        }
    }

    private fun gradeElement(bioware: Boolean, name: String): Element? =
        (data as? DataPack)?.let { grades(it, bioware).firstOrNull { g -> g.child("name")?.textContent?.trim() == name } }

    private fun isBio(entry: Element) = entry.tagName == "bioware"

    /** What a grade multiplies the price by (Standard 1, Alphaware 2, ...); 1 when the data does not name the grade. */
    fun gradeCostMultiplier(bioware: Boolean, grade: String): Double =
        gradeElement(bioware, grade)?.child("cost")?.textContent?.trim()?.toDoubleOrNull() ?: 1.0

    /** Move an installed item, and the plug-ins in it (they are always at their parent's grade), to [grade]. False when there is no such item. */
    fun setGrade(guid: String, grade: String): Boolean {
        val item = findWare(guid) ?: return false
        fun walk(e: Element) {
            e.child("grade")?.textContent = grade
            for (child in e.child("children")?.children("cyberware").orEmpty()) walk(child)
        }
        walk(item)
        return true
    }

    /** Rating used for a new item: the lowest the entry allows, or the one asked for within the range. */
    fun ratingFor(entry: Element, rating: Int?, parent: Element? = null): Int {
        val (min, max) = range(entry, parent)
        return if (max <= 0) 0 else (rating ?: maxOf(min, 1)).coerceIn(maxOf(min, 1), max)
    }

    /**
     * The lowest and highest rating an entry allows. A cyberlimb's attribute upgrades give their limits as `{AGIMinimum}+1` and
     * `{AGIMaximum}`: the minimum is the limb's own value (3 unless the limb says otherwise), the maximum the character's limit.
     */
    fun range(entry: Element, parent: Element? = null): Pair<Int, Int> {
        fun resolve(text: String): Int {
            var t = text.trim()
            if (t.isEmpty()) return 0
            if ('{' in t) {
                val limb = generateSequence(parent) { it.parentNode?.parentNode as? Element }
                    .firstOrNull { it.tagName == "cyberware" && (it.text("limbslot").orEmpty().isNotBlank() || it.text("category") == "Cyberlimb") }
                val attrs = AttributeEngine.forFile(file)
                val values = mapOf(
                    "AGIMinimum" to (limb?.text("minagility")?.trim()?.toIntOrNull() ?: 3), "STRMinimum" to (limb?.text("minstrength")?.trim()?.toIntOrNull() ?: 3),
                    "AGIMaximum" to (attrs["AGI"]?.totalMaximum ?: 6), "STRMaximum" to (attrs["STR"]?.totalMaximum ?: 6),
                )
                for ((k, v) in values) t = t.replace("{$k}", v.toString())
            }
            return t.toIntOrNull() ?: chummer.core.Expr.evalOrNull(t)?.toInt() ?: 0
        }
        return resolve(entry.child("minrating")?.textContent.orEmpty()) to resolve(entry.child("rating")?.textContent.orEmpty())
    }

    /** The limit text Chummer saves for a rating range: its `{AGIMinimum}` placeholders are written as `MinimumAGI`. */
    private fun savedLimit(text: String) = text.replace("{AGIMinimum}", "MinimumAGI").replace("{STRMinimum}", "MinimumSTR").replace("{AGIMaximum}", "MaximumAGI").replace("{STRMaximum}", "MaximumSTR")

    /** Nuyen cost at a grade and rating; null when the price formula cannot be read. */
    fun cost(entryId: String, grade: String, rating: Int?, parentGuid: String? = null): Double? {
        val entry = data[entryId]?.element ?: return null
        val parent = parentGuid?.let { findWare(it) }
        val base = formula(entry.child("cost")?.textContent.orEmpty(), ratingFor(entry, rating, parent), range(entry, parent).first) ?: return null
        val multiplier = gradeElement(isBio(entry), grade)?.child("cost")?.textContent?.trim()?.toDoubleOrNull() ?: 1.0
        return base * multiplier
    }

    /** Essence cost at a grade and rating, before the character's modifiers; null when the formula cannot be read. */
    fun essence(entryId: String, grade: String, rating: Int?, parentGuid: String? = null): Double? {
        val entry = data[entryId]?.element ?: return null
        val parent = parentGuid?.let { findWare(it) }
        val base = formula(entry.child("ess")?.textContent.orEmpty(), ratingFor(entry, rating, parent), range(entry, parent).first) ?: return null
        return base * (data.gradeEssence(isBio(entry), grade) ?: 1.0)
    }

    /** An installed item anywhere in the character's ware, including plug-ins. */
    fun findWare(guid: String): Element? {
        fun walk(list: Element?): Element? {
            for (w in list?.children("cyberware").orEmpty()) {
                if (w.text("guid")?.trim().equals(guid, true)) return w
                walk(w.child("children"))?.let { return it }
            }
            return null
        }
        return walk(root.child("cyberwares"))
    }

    /** Capacity a parent item offers at its rating, from the data entry it came from; 0 when it has none. */
    fun capacityOf(parent: Element): Double {
        val entry = data[parent.text("sourceid")?.trim().orEmpty()]?.element ?: return 0.0
        return formula(entry.child("capacity")?.textContent.orEmpty(), parent.text("rating")?.trim()?.toIntOrNull() ?: 0) ?: 0.0
    }

    /** What a plug-in uses of its parent's capacity: the number in brackets (`[3]`); `[*]` and anything else use none. */
    fun slotsUsedBy(capacityText: String): Double =
        Regex("""^\[([0-9.]+)\]$""").find(capacityText.trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    fun capacityUsed(parent: Element): Double =
        parent.child("children")?.children("cyberware").orEmpty().sumOf { slotsUsedBy(it.text("capacity").orEmpty()) }

    /** Why this entry cannot plug into this parent, or null: the parent must allow its category and have capacity left. */
    fun plugProblem(parentGuid: String, entryId: String): String? {
        val parent = findWare(parentGuid) ?: return "no such item"
        val parentEntry = data[parent.text("sourceid")?.trim().orEmpty()]?.element ?: return "unknown parent"
        val entry = data[entryId]?.element ?: return "unknown item"
        val allowed = parentEntry.child("allowsubsystems")?.children("category").orEmpty().map { it.textContent.trim() }
        if (entry.child("category")?.textContent?.trim() !in allowed) return "does not fit this item"
        val need = slotsUsedBy(entry.child("capacity")?.textContent.orEmpty())
        if (capacityUsed(parent) + need > capacityOf(parent) + 1e-9) return "not enough capacity"
        return null
    }

    /** Add a cyberware or bioware entry at a grade; returns the new item's GUID. [parentGuid] plugs it into another item. */
    fun add(entryId: String, grade: String = "Standard", rating: Int? = null, parentGuid: String? = null, asDefault: Boolean = false): String {
        val entry = data[entryId]?.element ?: error("No cyberware with id $entryId in the game data")
        require(entry.tagName == "cyberware" || entry.tagName == "bioware") { "Not cyberware or bioware" }
        val parent = parentGuid?.let { findWare(it) ?: error("No installed item with that GUID") }
        if (parent == null) problem(entry)?.let { error("Cannot add this item: $it") }
        else if (!asDefault) plugProblem(parentGuid, entryId)?.let { error("Cannot plug this in: $it") }
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val bio = isBio(entry)
        val guid = UUID.randomUUID().toString()
        val forced = d("forcegrade")
        val usedGrade = if (parent != null) parent.text("grade")?.trim().orEmpty().ifEmpty { grade } else if (forced.isNotEmpty()) forced else grade
        val chosen = ratingFor(entry, rating, parent)
        val g = wareElement(entryId, entry, guid, usedGrade, chosen, range(entry, parent).second > 0, asDefault, parentGuid)
        val list = if (parent != null) (parent.child("children") ?: el("children").also { parent.appendChild(it) })
        else (root.child("cyberwares") ?: el("cyberwares").also { root.appendChild(it) })
        g.appendChild(el("sortorder", list.children("cyberware").size.toString()))
        list.appendChild(g)

        applyBonus(entry, guid, chosen)

        // Gear the data lists comes with the item; subsystems come installed as plug-ins.
        val gearNodes = entry.child("gears")?.children("usegear").orEmpty()
        if (gearNodes.isNotEmpty()) {
            val container = el("gears")
            g.appendChild(container)
            val ge = GearEditor(file, data)
            for (u in gearNodes) ge.addUseGear(u, container, guid)
        }
        for (sub in entry.child("subsystems")?.children("cyberware").orEmpty()) {
            val subId = findNamed(entry.tagName, sub.text("name")?.trim().orEmpty()) ?: continue
            add(subId, usedGrade, sub.text("rating")?.trim()?.toIntOrNull(), parentGuid = guid, asDefault = true)
        }
        return guid
    }

    /**
     * The saved element for one cyberware entry at a grade and rating, in the layout Chummer writes, not yet placed anywhere (the caller appends it and
     * adds its `sortorder`). [hasRange] is whether the entry has a rating range here; [asDefault] marks an item that comes free with its parent.
     */
    internal fun wareElement(entryId: String, entry: Element, guid: String, usedGrade: String, chosen: Int, hasRange: Boolean, asDefault: Boolean, parentGuid: String?): Element {
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val bio = isBio(entry)
        val forced = d("forcegrade")
        val g = el("cyberware")
        fun add(tag: String, text: String? = null) = g.appendChild(el(tag, text))
        fun flag(tag: String) = if (d(tag).equals("True", true)) "True" else "False"
        add("guid", guid); add("sourceid", entryId.lowercase()); add("name", d("name")); add("category", d("category"))
        add("limbslot", d("limbslot")); add("limbslotcount", d("limbslotcount").ifEmpty { "1" }); add("inheritattributes", "False")
        add("ess", d("ess")); add("capacity", d("capacity")); add("avail", d("avail")); add("cost", if (asDefault) "0" else d("cost")); add("weight", d("weight")); add("source", d("source")); add("page", d("page"))
        add("parentid", if (asDefault) parentGuid else ""); add("hasmodularmount", d("hasmodularmount")); add("plugsintomodularmount", d("plugsintomodularmount")); add("blocksmounts", d("blocksmounts")); add("forced")
        add("rating", chosen.toString()); add("minagility", d("minagility").ifEmpty { "3" }); add("minstrength", d("minstrength").ifEmpty { "3" })
        add("minrating", savedLimit(d("minrating"))); add("maxrating", if (hasRange) savedLimit(d("rating")) else ""); add("ratinglabel", "String_Rating"); add("subsystems", d("subsystems"))
        add("wirelesson", "False"); add("grade", usedGrade); add("location"); add("extra"); add("suite", "False"); add("stolen", "False")
        add("essdiscount", "0"); add("extraessadditivemultiplier", "0"); add("extraessmultiplicativemultiplier", "1"); add("forcegrade", forced)
        add("matrixcmfilled", "0"); add("matrixcmbonus", "0"); add("prototypetranshuman", "False")
        for (tag in listOf("bonus", "pairbonus", "wirelessbonus", "wirelesspairbonus")) {
            val node = entry.child(tag)
            g.appendChild(if (node != null && tag != "wirelessbonus" && tag != "wirelesspairbonus") doc.importNode(node, true) else el(tag))
        }
        add("improvementsource", if (bio) "Bioware" else "Cyberware")
        for (tag in listOf("pairinclude", "wirelesspairinclude")) {
            val node = entry.child(tag)
            g.appendChild(if (node != null) doc.importNode(node, true) else el(tag))
        }
        add("notes"); add("notesColor", NOTES_COLOR); add("discountedcost", "False"); add("addtoparentess", flag("addtoparentess")); add("addtoparentcapacity", flag("addtoparentcapacity"))
        add("isgeneware", if (entry.child("isgeneware") != null && !d("isgeneware").equals("False", true)) "True" else "False"); add("active", "False"); add("homenode", "False")
        add("devicerating", d("devicerating")); add("programlimit", d("programs")); add("overclocked", "None"); add("canformpersona", d("canformpersona"))
        for (tag in listOf("attack", "sleaze", "dataprocessing", "firewall", "attributearray", "modattack", "modsleaze", "moddataprocessing", "modfirewall", "modattributearray")) add(tag, d(tag))
        add("canswapattributes", "False")
        return g
    }

    /** Write the improvements an item's bonus grants at [rating]. */
    private fun applyBonus(entry: Element, guid: String, rating: Int) {
        val bonus = entry.child("bonus")
        if (bonus == null || bonus.children().isEmpty()) return
        val bio = isBio(entry)
        val result = BonusHandlers.apply(bonus, BonusContext(if (bio) "Bioware" else "Cyberware", guid, maxOf(rating, 1), "", entry.child("name")?.textContent?.trim().orEmpty(), objects = EditorObjects(file, data)))
        val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
        for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
    }

    /** Change the rating of an installed item: its rating field, and the improvements its bonus grants at that rating. False when there is no such item or the rating is outside what it allows. */
    fun setRating(guid: String, rating: Int): Boolean {
        val item = findWare(guid) ?: return false
        val entry = data[item.text("sourceid")?.trim().orEmpty()]?.element ?: return false
        val parent = generateSequence(item.parentNode?.parentNode as? Element) { it.parentNode?.parentNode as? Element }.firstOrNull { it.tagName == "cyberware" }
        val (min, max) = range(entry, parent)
        if (max <= 0 || rating !in maxOf(min, 1)..max) return false
        item.child("rating")?.textContent = rating.toString()
        val imps = root.child("improvements")
        if (imps != null) for (i in imps.children("improvement").filter { it.text("sourcename")?.trim().equals(guid, true) }) imps.removeChild(i)
        applyBonus(entry, guid, rating)
        return true
    }

    private fun findNamed(tag: String, name: String): String? =
        (data as? DataPack)?.byId?.entries?.firstOrNull {
            (it.value.file == "cyberware.xml" || it.value.file == "bioware.xml") && it.value.element.tagName == tag && it.value.name == name
        }?.key

    /** Remove an installed item (or plug-in) with everything inside it and the improvements they granted. */
    fun remove(guid: String): Boolean {
        val node = findWare(guid) ?: return false
        val imps = root.child("improvements")
        if (imps != null) {
            val ids = HashSet<String>()
            fun walk(e: Element) { e.text("guid")?.trim()?.let { ids += it.lowercase() }; for (c in e.children()) if (c.tagName != "bonus") walk(c) }
            walk(node)
            for (i in imps.children("improvement").filter { it.text("sourcename")?.trim()?.lowercase() in ids }) imps.removeChild(i)
        }
        node.parentNode.removeChild(node)
        return true
    }
}

object WareCatalog {
    fun list(data: DataPack): List<WareOption> =
        data.byId.entries.filter { (it.value.file == "cyberware.xml" || it.value.file == "bioware.xml") && (it.value.element.tagName == "cyberware" || it.value.element.tagName == "bioware") }
            .mapNotNull { (id, e) ->
                val el = e.element
                fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
                if (t("name").isEmpty()) return@mapNotNull null
                val max = t("rating").toIntOrNull() ?: 0
                val min = t("minrating").toIntOrNull() ?: 0
                val r = if (max > 0) maxOf(min, 1) else 0
                val unsupported = el.child("bonus")?.let { BonusHandlers.apply(it, BonusContext("Cyberware", "", 1)).unhandled.distinct() }.orEmpty()
                WareOption(
                    id, t("name"), t("category"), el.tagName == "bioware", CyberwareEditor.formula(t("cost"), r), CyberwareEditor.formula(t("ess"), r),
                    t("avail"), min, max, t("forcegrade"), CyberwareEditor.problem(el), unsupported, el.child("addtoparentess") != null,
                )
            }.sortedBy { it.name.lowercase() }

    /** The plug-ins a parent item takes: entries of the categories it allows, with the capacity they use. */
    fun pluginsFor(data: DataPack, parentSourceId: String): List<WareOption> {
        val parent = data[parentSourceId]?.element ?: return emptyList()
        val allowed = parent.child("allowsubsystems")?.children("category").orEmpty().map { it.textContent.trim() }.toSet()
        if (allowed.isEmpty()) return emptyList()
        return list(data).filter { it.category in allowed }.map { it.copy(problem = null) }
    }

    /** The same plug-ins with rating limits worked out for the limb or item with GUID [parentGuid] on this character (limb upgrades depend on it). */
    fun pluginsFor(data: DataPack, parentSourceId: String, editor: CyberwareEditor, parentGuid: String): List<WareOption> {
        val parent = editor.findWare(parentGuid)
        return pluginsFor(data, parentSourceId).map { o ->
            val el = data[o.id]?.element ?: return@map o
            val text = (el.child("minrating")?.textContent.orEmpty()) + (el.child("rating")?.textContent.orEmpty())
            if ('{' !in text) o else editor.range(el, parent).let { (min, max) ->
                o.copy(minRating = min, maxRating = max, cost = CyberwareEditor.formula(el.child("cost")?.textContent.orEmpty(), maxOf(min, 1), min), essence = CyberwareEditor.formula(el.child("ess")?.textContent.orEmpty(), maxOf(min, 1), min))
            }
        }
    }

    /**
     * The grades to offer, best essence first: not the ones the settings ban, not the Adapsin and Burnout's Way variants (they
     * need the matching quality) and not "None" (items that force it say so).
     */
    fun gradeNames(data: DataPack, bioware: Boolean, banned: List<String> = emptyList()): List<String> =
        CyberwareEditor.grades(data, bioware)
            .mapNotNull { g -> g.child("name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { it to (g.child("ess")?.textContent?.trim()?.toDoubleOrNull() ?: 1.0) } }
            .filter { (n, _) -> n !in banned && !n.contains('(') && n != "None" }
            .sortedByDescending { it.second }.map { it.first }
}
