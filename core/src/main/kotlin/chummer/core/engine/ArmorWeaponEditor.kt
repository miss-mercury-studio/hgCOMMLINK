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

    // ---- ammunition and clips

    /** A stack of loose ammunition a weapon can be loaded from. */
    data class AmmoStack(val guid: String, val name: String, val quantity: Int)

    private val emptyGuid = "00000000-0000-0000-0000-000000000000"

    private fun count(e: Element, tag: String = "count") = e.text(tag)?.trim()?.toIntOrNull() ?: 0

    /** The gear items now sitting in a clip of some weapon (Chummer keeps them in the gear list, tied to the clip by its id). */
    private fun loadedGuids(): Set<String> = allWeapons().flatMap { it.child("clips")?.children("clip").orEmpty() }
        .mapNotNull { c -> c.text("id")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != emptyGuid } }.toSet()

    /**
     * The owner of each clip slot of a weapon, in Chummer's order: the weapon's own slots first (owner ""), then the slots each Spare Clip style
     * accessory adds (owner = the accessory's GUID). A weapon file lists only the clips that hold something, so slots past the list are empty.
     */
    private fun slotOwners(weapon: Element): List<String> {
        val out = ArrayList<String>()
        repeat(maxOf(weapon.text("ammoslots")?.trim()?.toIntOrNull() ?: 1, 1)) { out += "" }
        for (a in weapon.child("accessories")?.children("accessory").orEmpty()) {
            repeat(a.text("ammoslots")?.trim()?.toIntOrNull() ?: 0) { out += a.text("guid")?.trim().orEmpty() }
        }
        return out
    }

    /** How many clip slots a weapon has. */
    fun clipSlotCount(weaponGuid: String): Int = findWeapon(weaponGuid)?.let { slotOwners(it).size } ?: 0

    /** The clip at [slot] (1-based), adding empty clips to the list up to it. Null when the weapon has no such slot. */
    private fun clipAt(weapon: Element, slot: Int): Element? {
        if (slot !in 1..slotOwners(weapon).size) return null
        val clips = weapon.child("clips") ?: el("clips").also { c ->
            val after = weapon.child("activeammoslot")
            if (after != null && after.nextSibling != null) weapon.insertBefore(c, after.nextSibling) else weapon.appendChild(c)
        }
        while (clips.children("clip").size < slot) {
            clips.appendChild(el("clip").also { k -> k.appendChild(el("count", "0")); k.appendChild(el("location")); k.appendChild(el("id", emptyGuid)) })
        }
        return clips.children("clip")[slot - 1]
    }

    private fun clipGear(clip: Element): Element? {
        val id = clip.text("id")?.trim().orEmpty()
        if (id.isEmpty() || id == emptyGuid) return null
        return root.child("gears")?.children("gear")?.firstOrNull { it.text("guid")?.trim().equals(id, true) }
    }

    /** How many rounds a clip of this weapon holds: its ammo figure, raised by extended-clip accessories (a percentage of the base). 0 for a weapon with no clips. */
    fun clipCapacity(weaponGuid: String): Int = findWeapon(weaponGuid)?.let(::capacityOf) ?: 0

    private fun capacityOf(weapon: Element): Int {
        val base = Regex("^([0-9]+)").find(weapon.text("ammo")?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        val percent = weapon.child("accessories")?.children("accessory").orEmpty().sumOf { a ->
            val t = a.text("ammobonus")?.trim().orEmpty().removePrefix("+")
            if (t.isEmpty()) 0.0 else chummer.core.Expr.evalOrNull(t.replace("{Rating}", (a.text("rating")?.trim() ?: "0")).replace("Rating", (a.text("rating")?.trim() ?: "0"))) ?: 0.0
        }
        return if (percent == 0.0) base else standardRound(base * (1 + percent / 100))
    }

    /**
     * The loose ammunition this weapon can be loaded from, as Chummer's reload offers it: ammunition for this kind of weapon (the gear's
     * `ammoforweapontype` names the weapon's type), made for this weapon's ammunition category (or for any), and flechette ammunition only
     * for a flechette weapon. Rounds already in a clip are not loose.
     */
    fun compatibleAmmo(weaponGuid: String): List<AmmoStack> {
        val weapon = findWeapon(weaponGuid) ?: return emptyList()
        val loaded = loadedGuids()
        val type = weapon.text("weapontype")?.trim().orEmpty()
        val category = weapon.text("ammocategory")?.trim().orEmpty().ifEmpty { weapon.text("category")?.trim().orEmpty() }
        val flechette = weapon.text("damage")?.contains("(f)") == true
        return root.child("gears")?.children("gear").orEmpty().filter { g ->
            val guid = g.text("guid")?.trim()?.lowercase().orEmpty()
            val qty = g.text("qty")?.trim()?.toDoubleOrNull() ?: 0.0
            g.text("category")?.trim() == "Ammunition" && qty >= 1.0 && guid !in loaded && g.text("equipped")?.trim() != "False" &&
                g.text("ammoforweapontype").orEmpty().split(',').map { it.trim() }.contains(type) &&
                g.text("extra")?.trim().orEmpty().let { it.isEmpty() || it == category } &&
                (!flechette || g.text("isflechetteammo")?.trim().equals("True", true))
        }.map { AmmoStack(it.text("guid")!!.trim(), it.text("name")?.trim().orEmpty(), (it.text("qty")?.trim()?.toDoubleOrNull() ?: 0.0).toInt()) }
    }

    /**
     * Load ammunition into a weapon's clip [slot] (1-based; 0 means the first empty clip) from a loose ammunition stack, taking up to [rounds] (the clip's
     * capacity when null) out of it. A partly used stack is split, and the loaded rounds become their own gear item tied to the clip. A clip that already
     * holds the same ammunition is topped up instead; one holding something else must be unloaded first. Returns false when the slot, the ammunition
     * or the amount does not fit.
     */
    fun loadAmmo(weaponGuid: String, ammoGearGuid: String, slot: Int = 0, rounds: Int? = null): Boolean {
        val weapon = findWeapon(weaponGuid) ?: return false
        val gears = root.child("gears") ?: return false
        val ammo = gears.children("gear").firstOrNull { it.text("guid")?.trim().equals(ammoGearGuid, true) } ?: return false
        if (compatibleAmmo(weaponGuid).none { it.guid.equals(ammoGearGuid, true) }) return false
        val capacity = capacityOf(weapon)
        if (capacity <= 0) return false
        val owners = slotOwners(weapon)
        val index = if (slot > 0) slot else owners.indices.firstOrNull { i ->
            val c = weapon.child("clips")?.children("clip")?.getOrNull(i)
            c == null || (count(c) == 0 && clipGear(c) == null)
        }?.plus(1) ?: return false
        val clip = clipAt(weapon, index) ?: return false
        val stack = ammo.text("qty")?.trim()?.toDoubleOrNull() ?: return false
        val inClip = count(clip)
        val existing = clipGear(clip)
        if (existing != null && inClip > 0) {
            // Top up: the same ammunition only.
            val same = existing.text("sourceid")?.trim().equals(ammo.text("sourceid")?.trim(), true) && existing.text("rating")?.trim() == ammo.text("rating")?.trim()
            if (!same) return false
        }
        val room = capacity - (if (existing != null) inClip else 0)
        val want = minOf(rounds ?: room, room, stack.toInt())
        if (want <= 0) return false
        val n = want.toDouble()
        if (existing != null && inClip > 0) {
            existing.child("qty")!!.textContent = fmtQty((existing.text("qty")?.trim()?.toDoubleOrNull() ?: inClip.toDouble()) + n)
            if (stack > n) ammo.child("qty")!!.textContent = fmtQty(stack - n) else gears.removeChild(ammo)
            clip.child("count")!!.textContent = (inClip + want).toString()
            return true
        }
        val loadedGuid: String
        if (stack > n) {
            // Split the stack: the rest stays, the loaded rounds become a copy.
            ammo.child("qty")!!.textContent = fmtQty(stack - n)
            val copy = ammo.cloneNode(true) as Element
            loadedGuid = UUID.randomUUID().toString()
            copy.child("guid")!!.textContent = loadedGuid
            copy.child("qty")!!.textContent = fmtQty(n)
            gears.appendChild(copy)
        } else {
            loadedGuid = ammo.text("guid")!!.trim()
        }
        clip.child("count")!!.textContent = want.toString()
        clip.child("location")!!.textContent = "loaded"
        clip.child("id")!!.textContent = loadedGuid
        return true
    }

    /**
     * Take the rounds out of a weapon's clip [slot] (1-based; 0 means the last loaded clip) and put them back into the loose stack of the same
     * ammunition, or leave them as their own loose item. Returns false when no clip holds rounds.
     */
    fun unloadAmmo(weaponGuid: String, slot: Int = 0): Boolean {
        val weapon = findWeapon(weaponGuid) ?: return false
        val clips = weapon.child("clips")?.children("clip").orEmpty()
        val index = if (slot > 0) slot - 1 else clips.indexOfLast { count(it) > 0 }
        val clip = clips.getOrNull(index) ?: return false
        if (count(clip) <= 0) return false
        emptyClip(clip, clips)
        return true
    }

    /** Empty a clip: its rounds return to a loose stack of the same ammunition (or stay as their own loose item). */
    private fun emptyClip(clip: Element, clips: List<Element>) {
        val n = count(clip)
        val id = clip.text("id")?.trim().orEmpty()
        val gears = root.child("gears")
        val loaded = clipGear(clip)
        if (loaded != null && gears != null) {
            val others = loadedGuids() - id.lowercase()
            val loose = gears.children("gear").firstOrNull {
                // The same ammunition for the same kind of gun: Viper has Regular Ammo in several stacks, one for each weapon category (the gear's `extra`).
                it !== loaded && it.text("sourceid")?.trim().equals(loaded.text("sourceid")?.trim(), true) && it.text("rating")?.trim() == loaded.text("rating")?.trim() &&
                    it.text("extra")?.trim().orEmpty() == loaded.text("extra")?.trim().orEmpty() &&
                    it.text("guid")?.trim()?.lowercase() !in others && it.text("guid")?.trim()?.lowercase() != id.lowercase()
            }
            val rounds = loaded.text("qty")?.trim()?.toDoubleOrNull() ?: n.toDouble()
            // Another clip may share the loaded item; only its own rounds leave it.
            val sharedElsewhere = clips.any { it !== clip && it.text("id")?.trim().equals(id, true) && count(it) > 0 }
            if (sharedElsewhere) loaded.child("qty")!!.textContent = fmtQty(rounds - n)
            if (loose != null && !sharedElsewhere) {
                loose.child("qty")!!.textContent = fmtQty((loose.text("qty")?.trim()?.toDoubleOrNull() ?: 0.0) + rounds)
                gears.removeChild(loaded)
            } else if (loose != null) {
                loose.child("qty")!!.textContent = fmtQty((loose.text("qty")?.trim()?.toDoubleOrNull() ?: 0.0) + n)
            }
        }
        clip.child("count")!!.textContent = "0"
        clip.child("location")!!.textContent = ""
        clip.child("id")!!.textContent = emptyGuid
    }

    /** Make clip [slot] (1-based) the one the weapon fires from; its ammunition then decides the damage and AP. False when the weapon has no such slot. */
    fun setActiveClip(weaponGuid: String, slot: Int): Boolean {
        val weapon = findWeapon(weaponGuid) ?: return false
        if (slot !in 1..slotOwners(weapon).size) return false
        (weapon.child("activeammoslot") ?: el("activeammoslot").also { weapon.appendChild(it) }).textContent = slot.toString()
        return true
    }

    /**
     * Spend [rounds] from clip [slot] (1-based; 0 means the active clip), as firing does: the clip and the loaded ammunition both lose them, and an
     * emptied clip is cleared. False when the clip holds fewer rounds than that.
     */
    fun fireRounds(weaponGuid: String, rounds: Int, slot: Int = 0): Boolean {
        val weapon = findWeapon(weaponGuid) ?: return false
        if (rounds <= 0) return false
        val index = if (slot > 0) slot else weapon.text("activeammoslot")?.trim()?.toIntOrNull() ?: 1
        val clip = weapon.child("clips")?.children("clip")?.getOrNull(index - 1) ?: return false
        val inClip = count(clip)
        if (rounds > inClip) return false
        val loaded = clipGear(clip)
        val left = inClip - rounds
        if (loaded != null) {
            val qty = (loaded.text("qty")?.trim()?.toDoubleOrNull() ?: inClip.toDouble()) - rounds
            if (qty > 0 && left > 0) loaded.child("qty")!!.textContent = fmtQty(qty)
            else root.child("gears")?.removeChild(loaded)
        }
        if (left > 0) clip.child("count")!!.textContent = left.toString()
        else { clip.child("count")!!.textContent = "0"; clip.child("location")!!.textContent = ""; clip.child("id")!!.textContent = emptyGuid }
        return true
    }

    /**
     * A removed accessory takes the clip slots it added with it: what is loaded in them goes back to the loose stacks, later clips move up, and the
     * active clip follows (or falls back to the first when it was one of them).
     */
    private fun dropClipSlotsOf(weapon: Element, accessory: Element) {
        val owners = slotOwners(weapon)
        val guid = accessory.text("guid")?.trim().orEmpty()
        val mine = owners.indices.filter { owners[it] == guid }
        if (mine.isEmpty()) return
        val clips = weapon.child("clips")?.children("clip").orEmpty()
        var active = weapon.text("activeammoslot")?.trim()?.toIntOrNull() ?: 1
        for (i in mine.reversed()) {
            val clip = clips.getOrNull(i) ?: continue
            if (count(clip) > 0 || clipGear(clip) != null) emptyClip(clip, clips)
            clip.parentNode.removeChild(clip)
            active = if (active == i + 1) 1 else if (active > i + 1) active - 1 else active
        }
        weapon.child("activeammoslot")?.textContent = active.toString()
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
    /** The data entry of the installed weapon [weaponGuid] (what accessory requirements are checked against). */
    fun weaponEntry(weaponGuid: String): Element? = findWeapon(weaponGuid)?.text("sourceid")?.trim()?.let { data[it]?.element }

    /** Why this accessory cannot be fitted to this weapon, or null when it can. */
    fun accessoryProblem(weaponGuid: String, entryId: String): String? {
        val entry = data[entryId]?.element ?: return "unknown accessory"
        val weapon = weaponEntry(weaponGuid) ?: return null
        return if (WeaponFilter.accessoryFits(entry, weapon)) null else "does not fit this weapon"
    }

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

    /** Switch an accessory on or off; one that is off adds nothing to the weapon but stays fitted (and keeps its clip slots). False when there is no such accessory. */
    fun setAccessoryEquipped(guid: String, equipped: Boolean): Boolean {
        val node = allWeapons().flatMap { it.child("accessories")?.children("accessory").orEmpty() }
            .firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        (node.child("equipped") ?: el("equipped").also { node.appendChild(it) }).textContent = if (equipped) "True" else "False"
        return true
    }

    fun removeAccessory(guid: String): Boolean {
        val node = allWeapons().flatMap { it.child("accessories")?.children("accessory").orEmpty() }
            .firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        removeImprovementsOfTree(node)
        // A Spare Clip takes its clip slots with it.
        (node.parentNode?.parentNode as? Element)?.takeIf { it.tagName == "weapon" }?.let { dropClipSlotsOf(it, node) }
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

    // ---- wearing armor, and armor sets

    private fun flagText(on: Boolean) = if (on) "True" else "False"
    private fun setFlag(node: Element, tag: String, on: Boolean) { (node.child(tag) ?: el(tag).also { node.appendChild(it) }).textContent = flagText(on) }
    private fun isOn(node: Element, tag: String) = node.text(tag)?.trim().equals("True", true)

    /** Turn the stored improvements of one source (`Armor`, `ArmorMod`, `Gear`) for one item on or off, as Chummer does when an item is equipped or not. */
    private fun switchImprovements(source: String, sourceName: String, on: Boolean) {
        val imps = root.child("improvements") ?: return
        for (i in imps.children("improvement")) {
            if (i.text("improvementsource")?.trim() != source || !i.text("sourcename")?.trim().equals(sourceName, true)) continue
            (i.child("enabled") ?: el("enabled").also { i.appendChild(it) }).textContent = if (on) "1" else "0"
        }
    }

    /** Switch the improvements of a piece of gear and of everything inside it. */
    private fun switchGear(gear: Element, on: Boolean) {
        gear.text("guid")?.trim()?.let { switchImprovements("Gear", it, on) }
        for (c in gear.child("children")?.children("gear").orEmpty()) switchGear(c, on)
    }

    private fun armorNode(guid: String) = topItem("armors", "armor", guid)

    /**
     * Put an armor on or take it off. Like Chummer, this sets the flag and turns the armor's own improvements and those of the gear
     * installed in it on or off; wearing one armor never takes another off. False when there is no such armor.
     */
    fun setArmorEquipped(guid: String, equipped: Boolean): Boolean {
        val armor = armorNode(guid) ?: return false
        setFlag(armor, "equipped", equipped)
        switchImprovements("Armor", guid, equipped)
        val gear = armor.child("gears")?.children("gear").orEmpty() +
            armor.child("armormods")?.children("armormod").orEmpty().filter { !equipped || isOn(it, "equipped") }.flatMap { it.child("gears")?.children("gear").orEmpty() }
        for (g in gear) if (!equipped || isOn(g, "equipped")) switchGear(g, equipped)
        // Chummer looks a mod's improvements up by the armor's id here, so they are not switched with the armor (see PLAN.md).
        return true
    }

    /** Switch an armor mod on or off. Its improvements come back only while its armor is worn. False when there is no such mod. */
    fun setArmorModEquipped(guid: String, equipped: Boolean): Boolean {
        val armor = root.child("armors")?.children("armor")?.firstOrNull { a -> a.child("armormods")?.children("armormod").orEmpty().any { it.text("guid")?.trim().equals(guid, true) } } ?: return false
        val mod = armor.child("armormods")!!.children("armormod").first { it.text("guid")?.trim().equals(guid, true) }
        setFlag(mod, "equipped", equipped)
        if (equipped && !isOn(armor, "equipped")) return true
        switchImprovements("ArmorMod", guid, equipped)
        for (g in mod.child("gears")?.children("gear").orEmpty()) if (!equipped || isOn(g, "equipped")) switchGear(g, equipped)
        return true
    }

    /** Switch gear installed in an armor (or in one of its mods) on or off. Its improvements follow only while the armor and mod are on. */
    fun setArmorGearEquipped(guid: String, equipped: Boolean): Boolean {
        for (armor in root.child("armors")?.children("armor").orEmpty()) {
            val holders = listOf(armor) + armor.child("armormods")?.children("armormod").orEmpty()
            for (holder in holders) {
                val gear = findGear(holder.child("gears")?.children("gear").orEmpty(), guid) ?: continue
                setFlag(gear, "equipped", equipped)
                if (!equipped || (isOn(armor, "equipped") && (holder === armor || isOn(holder, "equipped")))) switchGear(gear, equipped)
                return true
            }
        }
        return false
    }

    private fun findGear(list: List<Element>, guid: String): Element? {
        for (g in list) {
            if (g.text("guid")?.trim().equals(guid, true)) return g
            findGear(g.child("children")?.children("gear").orEmpty(), guid)?.let { return it }
        }
        return null
    }

    private fun locationNode(guid: String) = root.child("armorlocations")?.children("location")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** Make a new armor set (Chummer's armor location); returns its GUID, or null when the name is empty or already used. */
    fun addArmorSet(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || root.child("armorlocations")?.children("location").orEmpty().any { it.text("name")?.trim().equals(n, true) }) return null
        val list = root.child("armorlocations") ?: el("armorlocations").also { root.appendChild(it) }
        val guid = UUID.randomUUID().toString()
        val loc = el("location")
        loc.appendChild(el("guid", guid)); loc.appendChild(el("name", n)); loc.appendChild(el("notes")); loc.appendChild(el("notesColor", NOTES_COLOR))
        loc.appendChild(el("sortorder", list.children("location").size.toString()))
        list.appendChild(loc)
        return guid
    }

    fun renameArmorSet(guid: String, name: String): Boolean {
        val n = name.trim()
        val loc = locationNode(guid) ?: return false
        if (n.isEmpty() || root.child("armorlocations")?.children("location").orEmpty().any { it !== loc && it.text("name")?.trim().equals(n, true) }) return false
        loc.child("name")!!.textContent = n
        return true
    }

    /** Delete a set; the armor in it stays, outside any set. */
    fun removeArmorSet(guid: String): Boolean {
        val loc = locationNode(guid) ?: return false
        for (a in root.child("armors")?.children("armor").orEmpty()) if (a.text("location")?.trim().equals(guid, true)) a.child("location")?.textContent = ""
        loc.parentNode.removeChild(loc)
        return true
    }

    /** Put an armor in a set, or in none when [setGuid] is empty. */
    fun moveArmorToSet(armorGuid: String, setGuid: String): Boolean {
        val armor = armorNode(armorGuid) ?: return false
        if (setGuid.isNotEmpty() && locationNode(setGuid) == null) return false
        (armor.child("location") ?: el("location").also { armor.appendChild(it) }).textContent = setGuid
        return true
    }

    /** Wear or take off every armor in a set (those in no set when [setGuid] is empty). Armor outside the set is left as it is. Returns how many changed. */
    fun setArmorSetEquipped(setGuid: String, equipped: Boolean): Int {
        val members = root.child("armors")?.children("armor").orEmpty().filter { (it.text("location")?.trim().orEmpty()).equals(setGuid, true) && isOn(it, "equipped") != equipped }
        for (a in members) setArmorEquipped(a.text("guid")!!.trim(), equipped)
        return members.size
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

    /** The accessories that may be fitted to the weapon whose data entry is [weapon]: those whose required and forbidden weapon details allow it. */
    fun accessoriesFor(data: chummer.core.DataPack, weapon: Element): List<ItemOption> {
        val fits = data.byId.entries.filter { it.value.file == "weapons.xml" && it.value.element.tagName == "accessory" }
            .filter { WeaponFilter.accessoryFits(it.value.element, weapon) }.map { it.key }.toSet()
        return accessories(data).filter { it.id in fits }
    }

    /** Armor mods a player can choose from: not the ones the data hides (Custom Fit and the like, which only come with an armor). */
    fun armorMods(data: chummer.core.DataPack): List<ItemOption> =
        data.byId.entries.filter { it.value.file == "armor.xml" && it.value.element.tagName == "mod" && it.value.element.child("hide") == null }.map { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val max = t("maxrating").toIntOrNull() ?: 0
            ItemOption(id, t("name"), t("category"), GearEditor.unitCost(el, if (max > 1) 1 else 0), t("avail"), null, "armor ${t("armor")}  capacity ${readableCapacity(t("armorcapacity"))}", if (max > 1) max else 0)
        }.sortedBy { it.name.lowercase() }

    /**
     * The mods that may be fitted to an armor, as Chummer's mod picker decides. [armor] is the armor's data entry and [installed] the names of the mods it
     * already has. A mod needs a category the armor allows (General, the armor's own category or name, and its `addmodcategory`; `forcemodcategory` replaces
     * all of those), must pass its `required` and `forbidden` armor details, and its required or forbidden mods must (not) be fitted.
     */
    fun armorModsFor(data: chummer.core.DataPack, armor: Element, installed: List<String>): List<ItemOption> {
        fun t(e: Element, tag: String) = e.child(tag)?.textContent?.trim().orEmpty()
        fun split(v: String) = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val forced = t(armor, "forcemodcategory")
        val allowed = if (forced.isNotEmpty()) split(forced).toSet()
        else (setOf("General", t(armor, "category"), t(armor, "name")) + split(t(armor, "addmodcategory"))).filter { it.isNotEmpty() }.toSet()
        fun mods(node: Element?, group: String) = node?.child(group)?.children("armormod").orEmpty().map { it.textContent.trim() }
        val fits = data.byId.entries.filter { it.value.file == "armor.xml" && it.value.element.tagName == "mod" }.filter { (_, e) ->
            val mod = e.element
            if (t(mod, "category") !in allowed) return@filter false
            val forbidden = mod.child("forbidden")
            if (forbidden?.child("parentdetails")?.let { WeaponFilter.passes(armor, it) } == true) return@filter false
            if (mods(forbidden, "oneof").any { it in installed }) return@filter false
            val required = mod.child("required")
            if (required?.child("parentdetails")?.let { WeaponFilter.passes(armor, it) } == false) return@filter false
            val oneOf = mods(required, "oneof")
            if (oneOf.isNotEmpty() && oneOf.none { it in installed }) return@filter false
            mods(required, "allof").all { it in installed }
        }.map { it.key }.toSet()
        return armorMods(data).filter { it.id in fits }
    }
}
