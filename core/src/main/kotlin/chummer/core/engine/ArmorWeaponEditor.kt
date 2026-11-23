package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** An armor or weapon entry for a picker. [cost] is null when the price formula is beyond the evaluator; [problem] says why it cannot be added yet. */
data class ItemOption(val id: String, val name: String, val category: String, val cost: Double?, val avail: String, val problem: String?, val detail: String, val maxRating: Int = 0, val mounts: List<String> = emptyList())

/**
 * Adds armor and weapons to a character in the layout Chummer writes (checked against Viper's items in ArmorWeaponEditorTest).
 * Armor that comes with mods or gear, and weapons that come with accessories or an underbarrel weapon, are not supported yet.
 */
class ArmorWeaponEditor(private val file: CharacterFile, private val data: GameData) {
    private val doc get() = file.doc
    private val root get() = file.root

    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    private fun matrixTail(g: Element, d: (String) -> String) {
        g.appendChild(el("devicerating", d("devicerating"))); g.appendChild(el("programlimit", d("programs"))); g.appendChild(el("overclocked", "None"))
        for (tag in listOf("attack", "sleaze", "dataprocessing", "firewall", "attributearray", "modattack", "modsleaze", "moddataprocessing", "modfirewall", "modattributearray")) g.appendChild(el(tag, d(tag)))
        g.appendChild(el("canswapattributes", "False"))
    }

    private fun applyBonus(entry: Element, source: String, guid: String, rating: Int, name: String) {
        val bonus = entry.child("bonus") ?: return
        if (bonus.children().isEmpty()) return
        val result = BonusHandlers.apply(bonus, BonusContext(source, guid, maxOf(rating, 1), "", name, objects = EditorObjects(file, data)))
        val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
        for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
    }

    fun armorProblem(entryId: String): String? {
        val e = data[entryId]?.element ?: return "unknown armor"
        if (e.tagName != "armor") return "not armor"
        return null
    }

    /** Add armor (with the mods and gear the data lists); returns the new item's GUID. */
    fun addArmor(entryId: String, rating: Int? = null): String {
        armorProblem(entryId)?.let { error("Cannot add this armor: $it") }
        val entry = data[entryId]!!.element
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val max = d("rating").toIntOrNull() ?: 0
        val chosen = if (max <= 0) 0 else (rating ?: 1).coerceIn(1, max)
        val g = el("armor")
        fun add(tag: String, text: String? = null) = g.appendChild(el(tag, text))
        add("sourceid", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("category", d("category"))
        add("armor", d("armor")); add("armoroverride", d("armoroverride")); add("armorcapacity", d("armorcapacity")); add("avail", d("avail")); add("cost", d("cost"))
        add("weight", d("weight")); add("source", d("source")); add("page", d("page")); add("armorname"); add("equipped", "True"); add("active", "False"); add("homenode", "False")
        matrixTail(g, ::d)
        add("matrixcmfilled", "0"); add("matrixcmbonus", "0"); add("wirelesson", "False"); add("canformpersona", d("canformpersona")); add("extra"); add("damage", "0")
        add("rating", chosen.toString()); add("maxrating", max.toString()); add("ratinglabel", "String_Rating"); add("stolen", "False"); add("encumbrance", "True")
        val mods = el("armormods")
        g.appendChild(mods)
        val savedBonus = el("bonus")
        entry.child("bonus")?.children("limitmodifier")?.forEach { savedBonus.appendChild(doc.importNode(it, true)) }
        g.appendChild(savedBonus)
        add("wirelessbonus"); add("location"); add("notes"); add("notesColor", NOTES_COLOR); add("discountedcost", "False")
        val list = root.child("armors") ?: el("armors").also { root.appendChild(it) }
        add("sortorder", list.children("armor").size.toString())
        applyBonus(entry, "Armor", guid, chosen, d("name"))
        list.appendChild(g)
        // Gear the data lists comes with the armor.
        val armorGear = entry.child("gears")?.children("usegear").orEmpty()
        if (armorGear.isNotEmpty()) {
            val container = el("gears")
            g.appendChild(container)
            val ge = GearEditor(file, data)
            for (u in armorGear) ge.addUseGear(u, container, guid)
        }
        // The mods the data lists come with the armor, already paid for.
        for (m in entry.child("mods")?.children("name").orEmpty()) {
            val modId = findIn("armor.xml", "mod", m.textContent.trim()) ?: continue
            addArmorMod(guid, modId, m.getAttribute("rating").toIntOrNull(), included = true)
        }
        return guid
    }

    fun weaponProblem(entryId: String): String? {
        val e = data[entryId]?.element ?: return "unknown weapon"
        if (e.tagName != "weapon") return "not a weapon"
        return null
    }

    /** Add a weapon (with the accessories the data lists); returns the new item's GUID. */
    fun addWeapon(entryId: String): String = addWeaponInternal(entryId, null)

    private fun addWeaponInternal(entryId: String, parent: Element?): String {
        weaponProblem(entryId)?.let { error("Cannot add this weapon: $it") }
        val entry = data[entryId]!!.element
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        fun n(tag: String, default: String) = d(tag).ifEmpty { default }
        val guid = UUID.randomUUID().toString()
        val g = el("weapon")
        fun add(tag: String, text: String? = null) = g.appendChild(el(tag, text))
        add("sourceid", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("category", d("category")); add("type", d("type"))
        add("spec", d("spec")); add("spec2", d("spec2")); add("reach", d("reach")); add("damage", d("damage")); add("ap", d("ap")); add("mode", d("mode")); add("rc", d("rc"))
        add("ammo", d("ammo")); add("cyberware", "False"); add("ammocategory", d("ammocategory")); add("ammoslots", n("ammoslots", "1")); add("sizecategory", d("sizecategory"))
        add("firingmode", "DogBrain"); add("minrating", d("minrating")); add("maxrating", d("rating")); add("rating", "0"); add("accuracy", d("accuracy"))
        add("activeammoslot", "1"); add("conceal", d("conceal")); add("avail", d("avail")); add("cost", if (parent != null) "0" else d("cost")); add("weight", d("weight"))
        add("useskill", d("useskill")); add("useskillspec", d("useskillspec")); add("range", d("range")); add("alternaterange", d("alternaterange"))
        add("rangemultiply", n("rangemultiply", "1"))
        add("singleshot", n("singleshot", "1")); add("shortburst", n("shortburst", "3")); add("longburst", n("longburst", "6")); add("fullburst", n("fullburst", "10")); add("suppressive", n("suppressive", "20"))
        for (t in listOf("allowsingleshot", "allowshortburst", "allowlongburst", "allowfullburst", "allowsuppressive")) add(t, n(t, "True"))
        add("source", d("source")); add("page", d("page")); add("parentid", parent?.text("guid")?.trim().orEmpty()); add("allowaccessory", n("allowaccessory", "True")); add("weaponname")
        add("included", if (parent != null) "True" else "False"); add("equipped", "True"); add("requireammo", n("requireammo", "True")); add("accuracy", d("accuracy")); add("mount"); add("stolen", "False"); add("extramount")
        add("accessories"); add("location"); add("notes"); add("notesColor", NOTES_COLOR); add("discountedcost", "False")
        add("weaponslots", entry.child("accessorymounts")?.children("mount")?.joinToString("/") { it.textContent.trim() }.orEmpty())
        add("doubledcostweaponslots", d("doubledcostweaponslots")); add("active", "False"); add("homenode", "False")
        matrixTail(g, ::d)
        add("matrixcmfilled", "0"); add("wirelessbonus"); add("wirelessweaponbonus"); add("wirelesson", "True")
        val list = if (parent != null) (parent.child("underbarrel") ?: el("underbarrel").also { parent.appendChild(it) })
        else (root.child("weapons") ?: el("weapons").also { root.appendChild(it) })
        add("sortorder", list.children("weapon").size.toString())
        add("weapontype", weaponType(entry, d("category")))
        list.appendChild(g)
        for (a in entry.child("accessories")?.children("accessory").orEmpty()) {
            val accId = findIn("weapons.xml", "accessory", a.text("name")?.trim().orEmpty()) ?: continue
            addAccessory(guid, accId, included = true)
        }
        // Weapons the data lists as underbarrel weapons come with it, already paid for.
        for (u in entry.child("underbarrels")?.children("underbarrel").orEmpty()) {
            val id = findIn("weapons.xml", "weapon", u.textContent.trim()) ?: continue
            addWeaponInternal(id, g)
        }
        return guid
    }

    /** The weapon type Chummer files a weapon under: the data's own, else its category's, else the category name. */
    private fun weaponType(entry: Element, category: String): String {
        entry.child("weapontype")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val categories = (entry.parentNode?.parentNode as? Element)?.child("categories")
        val type = categories?.children("category")?.firstOrNull { it.textContent.trim() == category }?.getAttribute("type")
        return type?.takeIf { it.isNotEmpty() } ?: category.lowercase()
    }

    /** A weapon anywhere on the character, including underbarrel weapons. */
    private fun findWeapon(guid: String): Element? {
        fun walk(list: Element?): Element? {
            for (w in list?.children("weapon").orEmpty()) {
                if (w.text("guid")?.trim().equals(guid, true)) return w
                walk(w.child("underbarrel"))?.let { return it }
            }
            return null
        }
        return walk(root.child("weapons"))
    }

    private fun allWeapons(list: Element? = root.child("weapons")): List<Element> =
        list?.children("weapon").orEmpty().flatMap { listOf(it) + allWeapons(it.child("underbarrel")) }

    /**
     * Load ammunition into a weapon's clip [slot] (1-based) from an ammunition gear item of the right kind. Takes up to the
     * weapon's capacity out of the stack; a partly used stack is split, and the loaded rounds become their own gear item in
     * the "loaded" place. Returns false when the slot or ammunition does not fit.
     */
    fun loadAmmo(weaponGuid: String, ammoGearGuid: String, slot: Int = 0): Boolean {
        val weapon = findWeapon(weaponGuid) ?: return false
        val gears = root.child("gears") ?: return false
        val ammo = gears.children("gear").firstOrNull { it.text("guid")?.trim().equals(ammoGearGuid, true) } ?: return false
        if (ammo.text("category")?.trim() != "Ammunition" && ammo.text("category")?.trim() != "Ammo") return false
        val capacity = Regex("^([0-9]+)").find(weapon.text("ammo")?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val slots = weapon.text("ammoslots")?.trim()?.toIntOrNull() ?: 1
        var slot = slot
        val accSlots = (weapon.child("accessories")?.children("accessory").orEmpty()).sumOf { it.text("ammoslots")?.trim()?.toIntOrNull() ?: 0 }
        val total = maxOf(slots + accSlots, 1)
        if (slot == 0) {
            // The first empty clip.
            val existing = weapon.child("clips")?.children("clip")
            slot = if (existing == null) 1 else existing.indexOfFirst { (it.text("count")?.trim()?.toIntOrNull() ?: 0) == 0 }.let { if (it < 0) return false else it + 1 }
        }
        if (slot !in 1..total) return false
        val stack = ammo.text("qty")?.trim()?.toDoubleOrNull() ?: return false
        if (stack <= 0) return false
        val n = minOf(capacity.toDouble(), stack)
        val clips = weapon.child("clips") ?: el("clips").also { c ->
            repeat(total) { c.appendChild(el("clip").also { k -> k.appendChild(el("count", "0")); k.appendChild(el("location")); k.appendChild(el("id", "00000000-0000-0000-0000-000000000000")) }) }
            val after = weapon.child("activeammoslot")
            if (after != null && after.nextSibling != null) weapon.insertBefore(c, after.nextSibling) else weapon.appendChild(c)
        }
        val clip = clips.children("clip").getOrNull(slot - 1) ?: return false
        if (clip.text("count")?.trim()?.toIntOrNull() ?: 0 > 0) return false
        val loadedGuid: String
        if (stack > n) {
            // Split the stack: the rest stays, the loaded rounds become a copy.
            ammo.child("qty")!!.textContent = fmtQty(stack - n)
            val copy = ammo.cloneNode(true) as Element
            loadedGuid = UUID.randomUUID().toString()
            copy.child("guid")!!.textContent = loadedGuid
            copy.child("qty")!!.textContent = fmtQty(n)
            copy.child("location")?.textContent = "loaded"
            gears.appendChild(copy)
        } else {
            ammo.child("location")?.textContent = "loaded"
            loadedGuid = ammo.text("guid")!!.trim()
        }
        clip.child("count")!!.textContent = n.toInt().toString()
        clip.child("location")!!.textContent = "loaded"
        clip.child("id")!!.textContent = loadedGuid
        return true
    }

    /**
     * Take the rounds out of a weapon's clip [slot] (1-based; 0 means the last loaded clip) and put them back into the
     * loose stack of the same ammunition, or leave them as their own loose item. Returns false when no clip holds rounds.
     */
    fun unloadAmmo(weaponGuid: String, slot: Int = 0): Boolean {
        val weapon = findWeapon(weaponGuid) ?: return false
        val clips = weapon.child("clips")?.children("clip").orEmpty()
        val index = if (slot > 0) slot - 1 else clips.indexOfLast { (it.text("count")?.trim()?.toIntOrNull() ?: 0) > 0 }
        val clip = clips.getOrNull(index) ?: return false
        val n = clip.text("count")?.trim()?.toIntOrNull() ?: 0
        if (n <= 0) return false
        val id = clip.text("id")?.trim().orEmpty()
        val gears = root.child("gears")
        val loaded = gears?.children("gear")?.firstOrNull { it.text("guid")?.trim().equals(id, true) }
        if (loaded != null) {
            val loose = gears.children("gear").firstOrNull {
                it !== loaded && it.text("sourceid")?.trim().equals(loaded.text("sourceid")?.trim(), true) && it.text("location")?.trim() != "loaded"
            }
            val rounds = loaded.text("qty")?.trim()?.toDoubleOrNull() ?: n.toDouble()
            // Another clip may share the loaded item; only its own rounds leave it.
            val sharedElsewhere = clips.any { it !== clip && it.text("id")?.trim().equals(id, true) && (it.text("count")?.trim()?.toIntOrNull() ?: 0) > 0 }
            if (sharedElsewhere) loaded.child("qty")!!.textContent = fmtQty(rounds - n)
            if (loose != null && !sharedElsewhere) {
                loose.child("qty")!!.textContent = fmtQty((loose.text("qty")?.trim()?.toDoubleOrNull() ?: 0.0) + rounds)
                gears.removeChild(loaded)
            } else if (loose != null) {
                loose.child("qty")!!.textContent = fmtQty((loose.text("qty")?.trim()?.toDoubleOrNull() ?: 0.0) + n)
            } else if (!sharedElsewhere) loaded.child("location")?.textContent = ""
        }
        clip.child("count")!!.textContent = "0"
        clip.child("location")!!.textContent = ""
        clip.child("id")!!.textContent = "00000000-0000-0000-0000-000000000000"
        return true
    }

    private fun fmtQty(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

    private fun findIn(file: String, tag: String, name: String): String? =
        (data as? chummer.core.DataPack)?.byId?.entries?.firstOrNull { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }?.key

    private fun topItem(listTag: String, itemTag: String, guid: String) =
        root.child(listTag)?.children(itemTag)?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** The whole price of an accessory formula; "Weapon Cost" stands for the weapon's own price. */
    fun accessoryCost(entry: Element, rating: Int, weaponCost: Double): Double? {
        val text = entry.child("cost")?.textContent?.trim().orEmpty()
        if (text.isEmpty()) return 0.0
        val t = text.replace("Weapon Cost", weaponCost.toString()).replace("{Rating}", rating.toString()).replace("Rating", rating.toString())
        return chummer.core.Expr.evalOrNull(t)
    }

    /**
     * Add an accessory to the weapon with this GUID. [mount] is the chosen mount (defaults to the first the data allows),
     * [included] marks a part that comes with the weapon. Returns the accessory's GUID.
     */
    fun addAccessory(weaponGuid: String, entryId: String, mount: String? = null, rating: Int? = null, included: Boolean = false): String {
        val weapon = findWeapon(weaponGuid) ?: error("No weapon with that GUID")
        val entry = data[entryId]?.element ?: error("No accessory with id $entryId")
        require(entry.tagName == "accessory") { "Not an accessory" }
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val max = d("rating").toIntOrNull() ?: 0
        val chosen = if (max <= 0) 0 else (rating ?: 1).coerceIn(1, max)
        val mounts = d("mount").split('/').filter { it.isNotEmpty() }
        val usedMount = mount ?: if (included) "Internal" else (mounts.firstOrNull() ?: "None")
        val a = el("accessory")
        fun add(tag: String, text: String? = null) = a.appendChild(el(tag, text))
        add("sourceid", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("mount", usedMount); add("extramount", "None"); add("addmount", d("addmount"))
        add("rc", d("rc")); add("maxrating", max.toString()); add("rating", chosen.toString()); add("ratinglabel", "String_Rating"); add("rcgroup", d("rcgroup").ifEmpty { "0" })
        add("rcdeployable", if (d("rcdeployable").equals("True", true)) "True" else "False"); add("specialmodification", if (d("specialmodification").equals("True", true)) "True" else "False")
        add("conceal", d("conceal")); add("avail", d("avail")); add("cost", d("cost")); add("weight", d("weight")); add("included", if (included) "True" else "False"); add("equipped", "True")
        add("source", d("source")); add("page", d("page")); add("accuracy", d("accuracy")); add("ammoreplace", d("ammoreplace")); add("ammoslots", d("ammoslots").ifEmpty { "0" })
        add("modifyammocapacity", d("modifyammocapacity")); add("damagetype", d("damagetype")); add("damage", d("damage")); add("reach", d("reach")); add("damagereplace", d("damagereplace"))
        add("firemode", d("firemode")); add("firemodereplace", d("firemodereplace")); add("ap", d("ap")); add("apreplace", d("apreplace")); add("notes"); add("notesColor", NOTES_COLOR)
        add("discountedcost", "False")
        for (t in listOf("singleshot", "shortburst", "longburst", "fullburst", "suppressive")) add(t, d(t).ifEmpty { "0" })
        add("replacerange", d("replacerange")); add("rangebonus", d("rangebonus").ifEmpty { "0" }); add("rangemodifier", d("rangemodifier").ifEmpty { "0" }); add("extra")
        add("ammobonus", d("ammobonus")); add("wirelesson", "True"); add("wirelessbonus"); add("wirelessweaponbonus"); add("stolen", "False")
        val list = weapon.child("accessories") ?: el("accessories").also { weapon.appendChild(it) }
        add("sortorder", list.children("accessory").size.toString()); add("parentid")
        list.appendChild(a)
        // Parts that come with the accessory (a smartgun system brings a range finder and a camera).
        val gears = entry.child("gears")?.children("usegear").orEmpty()
        if (gears.isNotEmpty()) {
            val container = el("gears")
            a.appendChild(container)
            val ge = GearEditor(file, data)
            for (u in gears) ge.addUseGear(u, container, guid)
        }
        applyBonus(entry, "WeaponAccessory", guid, chosen, d("name"))
        return guid
    }

    fun removeAccessory(guid: String): Boolean {
        val node = allWeapons().flatMap { it.child("accessories")?.children("accessory").orEmpty() }
            .firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        removeImprovementsOfTree(node)
        node.parentNode.removeChild(node)
        return true
    }

    private fun removeImprovementsOfTree(node: Element) {
        val imps = root.child("improvements") ?: return
        val ids = HashSet<String>()
        fun walk(e: Element) { e.text("guid")?.trim()?.let { ids += it.lowercase() }; for (c in e.children()) if (c.tagName != "bonus") walk(c) }
        walk(node)
        for (i in imps.children("improvement").filter { it.text("sourcename")?.trim()?.lowercase() in ids }) imps.removeChild(i)
    }

    /** A capacity figure such as `[2]`, `8` or `FixedValues([1],[2])` at a rating. */
    private fun capacityNumber(text: String, rating: Int): Double {
        val t = text.trim().removePrefix("[").removeSuffix("]")
        return CyberwareEditor.formula(t, rating.coerceAtLeast(1)) ?: 0.0
    }

    /** Capacity an armor has left for gear and mods: its own capacity minus what the installed mods and gear use. */
    fun armorCapacityLeft(armorGuid: String): Double? {
        val armor = topItem("armors", "armor", armorGuid) ?: return null
        val own = capacityNumber(armor.text("armorcapacity").orEmpty(), armor.text("rating")?.trim()?.toIntOrNull() ?: 0)
        val mods = armor.child("armormods")?.children("armormod").orEmpty().filter { it.text("included")?.trim() != "True" }
            .sumOf { capacityNumber(it.text("armorcapacity").orEmpty(), it.text("rating")?.trim()?.toIntOrNull() ?: 0) }
        val gear = armor.child("gears")?.children("gear").orEmpty().sumOf { g ->
            val t = g.text("armorcapacity")?.trim().orEmpty()
            if (t.startsWith("[")) capacityNumber(t, g.text("rating")?.trim()?.toIntOrNull() ?: 0) else 0.0
        }
        return own - mods - gear
    }

    /** Why this gear cannot go into this armor, or null: it needs an armor capacity of its own and room for it. */
    fun armorGearProblem(armorGuid: String, gearEntryId: String): String? {
        val left = armorCapacityLeft(armorGuid) ?: return "no such armor"
        val entry = data[gearEntryId]?.element ?: return "unknown gear"
        val need = entry.child("armorcapacity")?.textContent?.trim().orEmpty()
        if (!need.startsWith("[")) return "does not fit in armor"
        if (capacityNumber(need, 1) > left + 1e-9) return "not enough capacity"
        return null
    }

    /** Install gear in an armor's capacity; returns the new item's GUID. */
    fun addArmorGear(armorGuid: String, gearEntryId: String, rating: Int? = null, qty: Double = 1.0): String {
        armorGearProblem(armorGuid, gearEntryId)?.let { error("Cannot install this gear: $it") }
        val armor = topItem("armors", "armor", armorGuid)!!
        val container = armor.child("gears") ?: el("gears").also { armor.appendChild(it) }
        return GearEditor(file, data).addGear(gearEntryId, rating, qty, into = container, parentId = armorGuid)
    }

    /** Add a mod to the armor with this GUID. [included] marks a mod that comes with the armor. Returns the mod's GUID. */
    fun addArmorMod(armorGuid: String, entryId: String, rating: Int? = null, included: Boolean = false): String {
        val armor = topItem("armors", "armor", armorGuid) ?: error("No armor with that GUID")
        val entry = data[entryId]?.element ?: error("No armor mod with id $entryId")
        require(entry.tagName == "mod") { "Not an armor mod" }
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val max = d("maxrating").toIntOrNull() ?: 0
        // A mod whose highest rating is 1 is not a rated mod.
        val chosen = if (max <= 1) 0 else (rating ?: 1).coerceIn(1, max)
        val m = el("armormod")
        fun add(tag: String, text: String? = null) = m.appendChild(el(tag, text))
        add("guid", guid); add("sourceid", entryId.lowercase()); add("name", d("name")); add("category", d("category"))
        add("armor", d("armor").removePrefix("+")); add("armorcapacity", d("armorcapacity")); add("gearcapacity", d("gearcapacity")); add("maxrating", max.toString()); add("rating", chosen.toString())
        add("ratinglabel", "String_Rating"); add("avail", d("avail")); add("cost", d("cost")); add("weight", d("weight"))
        val savedBonus = el("bonus")
        entry.child("bonus")?.children("limitmodifier")?.forEach { savedBonus.appendChild(doc.importNode(it, true)) }
        m.appendChild(savedBonus)
        add("wirelessbonus"); add("wirelesson", "True"); add("source", d("source")); add("page", d("page")); add("included", if (included) "True" else "False"); add("equipped", "True")
        add("extra"); add("encumbrance", "True"); add("stolen", "False"); add("notes", d("notes")); add("notesColor", NOTES_COLOR); add("discountedcost", "False")
        val list = armor.child("armormods") ?: el("armormods").also { armor.appendChild(it) }
        add("sortorder", list.children("armormod").size.toString())
        list.appendChild(m)
        applyBonus(entry, "ArmorMod", guid, chosen, d("name"))
        return guid
    }

    fun removeArmorMod(guid: String): Boolean {
        val node = root.child("armors")?.children("armor")?.flatMap { it.child("armormods")?.children("armormod").orEmpty() }
            ?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        removeImprovementsOfTree(node)
        node.parentNode.removeChild(node)
        return true
    }

    fun removeArmor(guid: String): Boolean = removeTop("armors", "armor", guid)
    fun removeWeapon(guid: String): Boolean = removeTop("weapons", "weapon", guid)

    private fun removeTop(list: String, item: String, guid: String): Boolean {
        val node = root.child(list)?.children(item)?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        removeImprovementsOfTree(node)
        node.parentNode.removeChild(node)
        return true
    }
}

object ArmorWeaponCatalog {
    /** A capacity formula as a person reads it: `FixedValues([1],[2],[3])` is "[1] to [3] by rating"; other text is left as it is. */
    fun readableCapacity(text: String): String {
        val m = Regex("""^FixedValues\((.*)\)$""").find(text.trim()) ?: return text
        val values = Regex("""\[([^\]]*)\]""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        return if (values.size > 1) "[${values.first()}] to [${values.last()}] by rating" else text
    }

    fun armor(data: chummer.core.DataPack): List<ItemOption> =
        data.byId.entries.filter { it.value.file == "armor.xml" && it.value.element.tagName == "armor" }.map { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val max = t("rating").toIntOrNull() ?: 0
            val problem: String? = null
            ItemOption(id, t("name"), t("category"), GearEditor.unitCost(el, if (max > 0) 1 else 0), t("avail"), problem, "armor ${t("armor")}", max)
        }.sortedBy { it.name.lowercase() }

    fun weapons(data: chummer.core.DataPack): List<ItemOption> =
        data.byId.entries.filter { it.value.file == "weapons.xml" && it.value.element.tagName == "weapon" }.map { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val problem: String? = null
            ItemOption(id, t("name"), t("category"), GearEditor.unitCost(el, 0), t("avail"), problem, "${t("damage")}  AP ${t("ap")}")
        }.sortedBy { it.name.lowercase() }

    /** Weapon accessories (with the mounts the data allows) and armor mods, for pickers. */
    fun accessories(data: chummer.core.DataPack): List<ItemOption> =
        data.byId.entries.filter { it.value.file == "weapons.xml" && it.value.element.tagName == "accessory" }.map { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            ItemOption(id, t("name"), "", null, t("avail"), null, t("cost"), t("rating").toIntOrNull() ?: 0, t("mount").split('/').filter { it.isNotEmpty() })
        }.sortedBy { it.name.lowercase() }

    fun armorMods(data: chummer.core.DataPack): List<ItemOption> =
        data.byId.entries.filter { it.value.file == "armor.xml" && it.value.element.tagName == "mod" }.map { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val max = t("maxrating").toIntOrNull() ?: 0
            ItemOption(id, t("name"), t("category"), GearEditor.unitCost(el, if (max > 1) 1 else 0), t("avail"), null, "armor ${t("armor")}  capacity ${readableCapacity(t("armorcapacity"))}", if (max > 1) max else 0)
        }.sortedBy { it.name.lowercase() }
}
