package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/**
 * Adds vehicles and drones to a character in the layout Chummer writes (field order and defaults read from Viper's three
 * vehicles). A vehicle comes with the mods and gear its data entry lists, already paid for (`included`, cost 0 for gear).
 * Not built: buying mods, gear or weapon mounts for a vehicle afterwards.
 */
class VehicleEditor(private val file: CharacterFile, private val data: DataPack) {
    private val doc get() = file.doc
    private val root get() = file.root

    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    fun problem(entryId: String): String? {
        val e = data[entryId]?.element ?: return "unknown vehicle"
        return if (e.tagName != "vehicle") "not a vehicle" else null
    }

    /** Add a vehicle or drone; returns the new item's GUID. */
    fun add(entryId: String): String {
        problem(entryId)?.let { error("Cannot add this vehicle: $it") }
        val entry = data[entryId]!!.element
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        /** A figure that may be given on and off road as "4/3"; a single value serves for both. */
        fun split(tag: String): Pair<String, String> { val v = d(tag).split('/'); return v[0].trim() to (v.getOrNull(1) ?: v[0]).trim() }
        val guid = UUID.randomUUID().toString()
        val list = root.child("vehicles") ?: el("vehicles").also { root.appendChild(it) }
        val v = el("vehicle")
        fun add(tag: String, text: String? = null) = v.appendChild(el(tag, text))
        add("sourceid", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("category", d("category"))
        val (handling, offHandling) = split("handling"); val (accel, offAccel) = split("accel"); val (speed, offSpeed) = split("speed")
        add("handling", handling); add("offroadhandling", offHandling); add("accel", accel); add("offroadaccel", offAccel)
        add("speed", speed); add("offroadspeed", offSpeed)
        add("pilot", d("pilot")); add("body", d("body")); add("seats", d("seats").ifEmpty { "0" }); add("armor", d("armor")); add("sensor", d("sensor"))
        add("avail", d("avail")); add("cost", d("cost"))
        add("addslots", d("addslots").ifEmpty { "0" }); add("modslots", d("modslots").ifEmpty { d("body").ifEmpty { "0" } })
        for (tag in listOf("powertrainmodslots", "protectionmodslots", "weaponmodslots", "bodymodslots", "electromagneticmodslots", "cosmeticmodslots")) add(tag, d(tag).ifEmpty { "0" })
        add("source", d("source")); add("page", d("page")); add("parentid"); add("sortorder", list.children("vehicle").size.toString()); add("stolen", "False")
        add("physicalcmfilled", "0"); add("matrixcmfilled", "0"); add("vehiclename")
        val mods = el("mods"); v.appendChild(mods)
        add("weaponmounts")
        val gears = el("gears"); v.appendChild(gears)
        add("weapons"); add("location"); add("notes"); add("notesColor", NOTES_COLOR); add("discountedcost", "False"); add("dealerconnection", "False")
        add("active", "False"); add("homenode", "False")
        add("devicerating", d("devicerating").ifEmpty { d("pilot") }); add("programlimit"); add("overclocked", "None")
        for (tag in listOf("attack", "sleaze", "dataprocessing", "firewall", "attributearray", "modattack", "modsleaze", "moddataprocessing", "modfirewall", "modattributearray")) add(tag, d(tag))
        add("canswapattributes", "False"); add("sortorder", "0")
        list.appendChild(v)
        // What comes with the vehicle: its mods (already paid) and its gear.
        for (m in entry.child("mods")?.children("name").orEmpty()) addIncludedMod(mods, m.textContent.trim(), m.getAttribute("rating").toIntOrNull())
        if (entry.child("gears") != null) {
            val ge = GearEditor(file, data)
            for (g in entry.child("gears")!!.children("gear")) ge.addUseGear(g, gears, guid)
        }
        return guid
    }

    private fun addIncludedMod(into: Element, name: String, rating: Int?) {
        val e = data.byId.entries.firstOrNull { it.value.file == "vehicles.xml" && it.value.element.tagName == "mod" && it.value.name == name } ?: return
        val entry = e.value.element
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val max = d("rating").toIntOrNull() ?: 0
        val m = el("mod")
        fun add(tag: String, text: String? = null) = m.appendChild(el(tag, text))
        add("sourceid", e.key.lowercase()); add("guid", UUID.randomUUID().toString()); add("name", name); add("category", d("category")); add("limit", d("limit"))
        add("slots", d("slots").ifEmpty { "0" }); add("capacity", d("capacity")); add("rating", if (max > 0) (rating ?: 1).coerceIn(1, max).toString() else "0")
        add("maxrating", max.toString()); add("ratinglabel", "String_Rating"); add("conditionmonitor", d("conditionmonitor").ifEmpty { "0" })
        add("avail", d("avail")); add("cost", d("cost")); add("extra"); add("source", d("source")); add("page", d("page"))
        add("included", "True"); add("equipped", "True"); add("wirelesson", "False"); add("subsystems"); add("weaponmountcategories")
        add("ammobonus", "0"); add("ammobonuspercent", "0"); add("ammoreplace"); add("weapons"); add("notes"); add("notesColor", NOTES_COLOR)
        add("discountedcost", "False"); add("useownattributesforweapon", "False"); add("sortorder", into.children("mod").size.toString()); add("stolen", "False")
        into.appendChild(m)
    }

    /** Remove a vehicle with its mods, gear and weapons. */
    fun remove(guid: String): Boolean {
        val node = root.child("vehicles")?.children("vehicle")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
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

object VehicleCatalog {
    fun vehicles(data: DataPack): List<ItemOption> =
        data.byId.entries.filter { it.value.file == "vehicles.xml" && it.value.element.tagName == "vehicle" }.map { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            ItemOption(id, t("name"), t("category"), GearEditor.unitCost(el, 0), t("avail"), null, "handling ${t("handling")}  speed ${t("speed")}  body ${t("body")}  armor ${t("armor")}")
        }.sortedBy { it.name.lowercase() }
}
