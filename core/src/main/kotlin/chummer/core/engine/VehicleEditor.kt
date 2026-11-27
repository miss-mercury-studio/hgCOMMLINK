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
 * Mods can be added to and removed from a vehicle afterwards ([modOptions], [addMod], [removeMod]); gear goes in through
 * `CareerAdvancement.buyVehicleGear`. A mod with capacity and subsystems (a drone arm or leg) takes cyberware ([wareOptions], [addWare]), kept in the
 * mod as Chummer keeps it. Not built: weapon mounts and vehicle weapons, and the effect of a mod's or its ware's bonus on the figures.
 */
class VehicleEditor(private val file: CharacterFile, private val data: DataPack) {
    companion object {
        /** A mod sold by the piece (tires) can be bought up to this many. */
        const val MAX_QUANTITY = 20
    }

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
        writeMod(into, e.key, e.value.element, rating, included = true, vehicle = null)
    }

    /**
     * Write a mod in the layout Chummer saves. A mod that comes with the vehicle is [included] (already paid for, cannot be taken off); one bought
     * later is not, and takes its limits and rating from the [vehicle] it goes on.
     */
    private fun writeMod(into: Element, entryId: String, entry: Element, rating: Int?, included: Boolean, vehicle: Element?): String {
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val max = if (included || vehicle == null) d("rating").toIntOrNull() ?: 0 else modMaxRating(entry, vehicle)
        val min = if (included || vehicle == null) 1 else maxOf(modMinRating(entry, vehicle), 1)
        val guid = UUID.randomUUID().toString()
        val m = el("mod")
        fun add(tag: String, text: String? = null) = m.appendChild(el(tag, text))
        add("sourceid", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("category", d("category")); add("limit", d("limit"))
        add("slots", d("slots").ifEmpty { "0" }); add("capacity", d("capacity")); add("rating", if (max > 0) (rating ?: min).coerceIn(minOf(min, max), max).toString() else "0")
        add("maxrating", max.toString()); add("ratinglabel", d("ratinglabel").ifEmpty { "String_Rating" }); add("conditionmonitor", d("conditionmonitor").ifEmpty { "0" })
        add("avail", d("avail")); add("cost", d("cost")); add("extra"); add("source", d("source")); add("page", d("page"))
        add("included", if (included) "True" else "False"); add("equipped", "True"); add("wirelesson", "False"); add("subsystems"); add("weaponmountcategories")
        add("ammobonus", "0"); add("ammobonuspercent", "0"); add("ammoreplace"); add("weapons"); add("notes"); add("notesColor", NOTES_COLOR)
        add("discountedcost", "False"); add("useownattributesforweapon", "False"); add("sortorder", into.children("mod").size.toString()); add("stolen", "False")
        into.appendChild(m)
        return guid
    }

    private fun vehicleNode(guid: String): Element? =
        root.child("vehicles")?.children("vehicle")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** The data entry a stored vehicle came from (what Chummer's requirements are checked against), or the stored vehicle when the data no longer has it. */
    private fun entryOf(vehicle: Element): Element = vehicle.text("sourceid")?.trim()?.let { data[it]?.element } ?: vehicle

    /** The rating a mod can go up to on this vehicle: a number, the vehicle's Body or Seats, or a quantity (tires) up to [MAX_QUANTITY]. 0 when it has no rating. */
    private fun modMaxRating(mod: Element, vehicle: Element): Int {
        val t = mod.child("rating")?.textContent?.trim().orEmpty()
        val n = when {
            t.equals("qty", true) -> MAX_QUANTITY
            t.equals("body", true) -> VehicleFormula.figure(vehicle, "body").toInt()
            t.equals("seats", true) -> VehicleFormula.figure(vehicle, "seats").toInt()
            else -> t.toIntOrNull() ?: VehicleFormula.eval(t, 0, vehicle, 0.0)?.toInt() ?: 0
        }
        return n.coerceIn(0, 99)
    }

    /** The lowest rating a mod can be bought at: the drone upgrades start one above the figure they raise ("Handling + 1"). */
    private fun modMinRating(mod: Element, vehicle: Element): Int {
        val t = mod.child("minrating")?.textContent?.trim().orEmpty()
        return if (t.isEmpty()) 1 else (t.toIntOrNull() ?: VehicleFormula.eval(t, 0, vehicle, 0.0)?.toInt() ?: 1).coerceAtLeast(1)
    }

    private fun isDrone(vehicle: Element) = vehicle.text("category")?.trim().orEmpty().startsWith("Drones")

    /** The price of the vehicle itself, which some mods are a share of ("Vehicle Cost * 0.25"). */
    private fun vehicleOwnCost(vehicle: Element) = VehicleFormula.number(vehicle.text("cost").orEmpty())

    /**
     * The mods that may be fitted to this vehicle, as the mod picker in Chummer offers them: not hidden, not for a weapon mount or an add-on to one, drone-only
     * ones only on a drone when the settings allow drone mods, and each one's `required` and `forbidden` vehicle details met. [cost] is the
     * price at the lowest rating, or null when its formula cannot be read.
     */
    fun modOptions(vehicleGuid: String, droneMods: Boolean): List<ItemOption> {
        val vehicle = vehicleNode(vehicleGuid) ?: return emptyList()
        val entry = entryOf(vehicle)
        val own = vehicleOwnCost(vehicle)
        val drone = isDrone(vehicle)
        return data.byId.entries.filter { it.value.file == "vehicles.xml" && it.value.element.tagName == "mod" }.filter { (_, e) ->
            val mod = e.element
            if (mod.child("hide") != null || mod.child("weaponmountcategories") != null) return@filter false
            // The mods in the data's own weaponmountmods list (add-ons and ammo bins) belong to a weapon mount, and a price can name the mount ("Parent Slots * 100").
            // The app has no weapon mounts yet, so they are not plain vehicle mods.
            if (mod.parentNode?.nodeName == "weaponmountmods") return@filter false
            if (mod.child("optionaldrone") != null && !(drone && droneMods)) return@filter false
            val forbidden = mod.child("forbidden")?.child("vehicledetails")
            if (forbidden != null && WeaponFilter.passes(entry, forbidden)) return@filter false
            val required = mod.child("required")?.child("vehicledetails")
            required == null || WeaponFilter.passes(entry, required)
        }.map { (id, e) ->
            val mod = e.element
            fun t(tag: String) = mod.child(tag)?.textContent?.trim().orEmpty()
            val max = modMaxRating(mod, vehicle)
            val min = if (max > 0) maxOf(modMinRating(mod, vehicle), 1) else 0
            val slots = VehicleFormula.slots(t("slots"), min, vehicle)
            ItemOption(id, t("name"), t("category"), VehicleFormula.cost(t("cost"), min, vehicle, own, t("slots")), t("avail"), null,
                if (slots != null && slots != 0) "slots $slots" else "", max, minRating = min)
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
    }

    /** The price of a mod on this vehicle at [rating]; null when there is no such mod or its formula cannot be read. */
    fun modCost(vehicleGuid: String, entryId: String, rating: Int?): Double? {
        val vehicle = vehicleNode(vehicleGuid) ?: return null
        val mod = data[entryId]?.element?.takeIf { it.tagName == "mod" } ?: return null
        val max = modMaxRating(mod, vehicle)
        val r = if (max > 0) (rating ?: maxOf(modMinRating(mod, vehicle), 1)).coerceIn(minOf(maxOf(modMinRating(mod, vehicle), 1), max), max) else 0
        return VehicleFormula.cost(mod.child("cost")?.textContent?.trim().orEmpty(), r, vehicle, vehicleOwnCost(vehicle), mod.child("slots")?.textContent?.trim().orEmpty())
    }

    /** Why this mod cannot go on this vehicle; null when it can. [droneMods] is the settings' drone-mods rule. */
    fun modProblem(vehicleGuid: String, entryId: String, droneMods: Boolean): String? {
        if (vehicleNode(vehicleGuid) == null) return "unknown vehicle"
        return if (modOptions(vehicleGuid, droneMods).any { it.id.equals(entryId, true) }) null else "that mod does not fit this vehicle"
    }

    /** Fit a mod to a vehicle (not included, so it is priced and can be taken off); returns the mod's GUID. */
    fun addMod(vehicleGuid: String, entryId: String, rating: Int?, droneMods: Boolean): String {
        modProblem(vehicleGuid, entryId, droneMods)?.let { error("Cannot add this mod: $it") }
        val vehicle = vehicleNode(vehicleGuid)!!
        val mods = vehicle.child("mods") ?: el("mods").also { vehicle.appendChild(it) }
        return writeMod(mods, entryId, data[entryId]!!.element, rating, included = false, vehicle = vehicle)
    }

    // ---- cyberware in a mod (a drone's arms and legs)

    private fun modNode(modGuid: String): Element? =
        root.child("vehicles")?.children("vehicle").orEmpty().firstNotNullOfOrNull { v -> v.child("mods")?.children("mod")?.firstOrNull { it.text("guid")?.trim().equals(modGuid, true) } }

    private fun subsystemsOf(modEntry: Element?): Set<String> = modEntry?.child("subsystems")?.children("subsystem").orEmpty().map { it.textContent.trim() }.filter { it.isNotEmpty() }.toSet()

    /** The mod's data entry, or the stored mod when the data no longer has it. */
    private fun modEntryOf(mod: Element): Element? = mod.text("sourceid")?.trim()?.let { data[it]?.element }

    /** True when this installed mod takes cyberware: its data entry lists subsystems (a drone arm or leg). */
    fun takesWare(modGuid: String): Boolean = modNode(modGuid)?.let { subsystemsOf(modEntryOf(it)).isNotEmpty() } == true

    /** What a piece of ware uses of its mod's capacity: the number in brackets (`[8]`); `[*]` and anything else use none. */
    private fun capacityNeed(capacityText: String): Double = Regex("""^\[([0-9.]+)\]$""").find(capacityText.trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /** Capacity the mod has in all, and what its ware uses. */
    fun wareCapacity(modGuid: String): Pair<Double, Double>? {
        val mod = modNode(modGuid) ?: return null
        val total = mod.text("capacity")?.trim()?.toDoubleOrNull() ?: modEntryOf(mod)?.child("capacity")?.textContent?.trim()?.toDoubleOrNull() ?: 0.0
        return total to wareUsed(mod)
    }

    private fun wareUsed(mod: Element) = mod.child("cyberwares")?.children("cyberware").orEmpty().sumOf { capacityNeed(it.text("capacity").orEmpty()) }

    private fun vehicleOfMod(mod: Element): Element? = mod.parentNode?.parentNode as? Element

    /** Which limb attribute an entry sets ("STR" or "AGI"), for the Customized Strength and Agility upgrades whose limits are the limb's own figures; null for other ware. */
    private fun upgradeStat(entry: Element): String? {
        val text = entry.child("minrating")?.textContent.orEmpty() + entry.child("rating")?.textContent.orEmpty()
        return when { "{STR" in text -> "STR"; "{AGI" in text -> "AGI"; else -> null }
    }

    /**
     * What an item asks to be chosen when it is bought, read from its bonus blocks: "skill" for a skill selector (Cyberlimb Optimization), "text" for a
     * free-text one (Cyberfinger (Custom)), "" for none. Desktop Chummer asks at purchase and saves the answer in the item's `extra`.
     */
    fun wareChoice(entry: Element): String {
        var kind = ""
        for (tag in listOf("bonus", "pairbonus", "wirelessbonus", "wirelesspairbonus")) {
            val all = entry.child(tag)?.getElementsByTagName("*") ?: continue
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                if (e.tagName == "selectskill") kind = "skill" else if (e.tagName == "selecttext" && kind.isEmpty()) kind = "text"
            }
        }
        return kind
    }

    /** The character's active skills, which a skill choice is made from. */
    fun choosableSkills(): List<String> = file.skills().filter { !it.isKnowledge }.map { it.name }.filter { it.isNotEmpty() }

    /**
     * A drone limb's base figures: Strength is the drone's Body and Agility its Pilot. An upgrade then raises the figure up to double the base, so
     * Customized Agility on a Pilot 4 drone is rated from 5 to 8 (and the rating is the new Agility).
     */
    fun limbBase(vehicle: Element, stat: String): Int = VehicleFormula.figure(vehicle, if (stat == "STR") "body" else "pilot").toInt()

    private fun upgradeRange(vehicle: Element, entry: Element): Pair<Int, Int>? =
        upgradeStat(entry)?.let { limbBase(vehicle, it) }?.let { base -> (base + 1) to (base * 2) }

    /** The stats ("STR", "AGI") that already have an upgrade in this mod. */
    private fun upgradedStats(mod: Element): Set<String> =
        mod.child("cyberwares")?.children("cyberware").orEmpty().mapNotNull { w -> w.text("sourceid")?.trim()?.let { data[it]?.element }?.let(::upgradeStat) }.toSet()

    /**
     * The cyberware that may go into this mod: entries of the categories the mod lists as subsystems that name this part in their `required` parent
     * details (so a Cyberarm Gyromount is offered to an arm and not a leg) and are not `forbidden` for it. Also the Customized Strength and Agility
     * upgrades, once for each, at the limb's own limits (see [limbBase]). Left out: items that need a choice made first and items that plug into other
     * ware. [WareOption.cost] is the Standard grade price at the lowest rating; the drone has no essence, so [WareOption.essence] is null.
     */
    fun wareOptions(modGuid: String): List<WareOption> {
        val mod = modNode(modGuid) ?: return emptyList()
        val vehicle = vehicleOfMod(mod) ?: return emptyList()
        val entry = modEntryOf(mod) ?: return emptyList()
        val allowed = subsystemsOf(entry)
        if (allowed.isEmpty()) return emptyList()
        val ed = CyberwareEditor(file, data)
        val have = upgradedStats(mod)
        return data.byId.entries.filter { it.value.file == "cyberware.xml" && it.value.element.tagName == "cyberware" }.filter { (_, e) ->
            val w = e.element
            if (w.child("category")?.textContent?.trim() !in allowed) return@filter false
            val stat = upgradeStat(w)
            if (stat != null) {
                // An attribute upgrade plugs into a limb (`requireparent`); here the part is the limb, once per attribute.
                val (min, max) = upgradeRange(vehicle, w)!!
                return@filter w.child("forced") == null && stat !in have && max >= min
            }
            if (CyberwareEditor.problem(w) != null) return@filter false
            val forbidden = w.child("forbidden")?.child("parentdetails")
            if (forbidden != null && WeaponFilter.passes(entry, forbidden)) return@filter false
            // Only ware the data names for this part. The category list alone would let a Datajack or an "Obvious Full Arm" into a drone's arm, since those
            // entries make no demand of their parent; the ones made for a drone limb say "Drone Arm" or "Drone Leg".
            val required = w.child("required")?.child("parentdetails") ?: return@filter false
            WeaponFilter.passes(entry, required)
        }.map { (id, e) ->
            val w = e.element
            fun t(tag: String) = w.child(tag)?.textContent?.trim().orEmpty()
            val (min, max) = upgradeRange(vehicle, w) ?: ed.range(w, null)
            val r = if (max > 0) maxOf(min, 1) else 0
            WareOption(id, t("name"), t("category"), false, CyberwareEditor.formula(t("cost"), r, min), null, t("avail"), min, max, "", null, emptyList(), capacity = t("capacity"), choice = wareChoice(w))
        }.filter { it.name.isNotEmpty() }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
    }

    /** Why this ware cannot go into this mod now; null when it can. */
    fun wareProblem(modGuid: String, entryId: String, extra: String = ""): String? {
        if (modNode(modGuid) == null) return "no such mod"
        if (wareOptions(modGuid).none { it.id.equals(entryId, true) }) return "that does not fit this part"
        when (data[entryId]?.element?.let(::wareChoice).orEmpty()) {
            "skill" -> if (choosableSkills().none { it.equals(extra.trim(), true) }) return "needs one of your skills chosen"
            "text" -> if (extra.isBlank()) return "needs a choice"
        }
        val need = capacityNeed(data[entryId]?.element?.child("capacity")?.textContent.orEmpty())
        val (total, used) = wareCapacity(modGuid) ?: return "no such mod"
        return if (used + need > total + 1e-9) "not enough capacity" else null
    }

    /** The Standard grade price of this ware in this mod at [rating]; null when the entry or mod is unknown or its formula cannot be read. */
    fun wareCost(modGuid: String, entryId: String, rating: Int?): Double? {
        val mod = modNode(modGuid) ?: return null
        val w = data[entryId]?.element?.takeIf { it.tagName == "cyberware" } ?: return null
        val (min, max) = vehicleOfMod(mod)?.let { upgradeRange(it, w) } ?: CyberwareEditor(file, data).range(w, null)
        val r = if (max > 0) (rating ?: maxOf(min, 1)).coerceIn(maxOf(min, 1), max) else 0
        return CyberwareEditor.formula(w.child("cost")?.textContent.orEmpty(), r, min)
    }

    /** Fit ware into a mod at the Standard grade, in Chummer's layout; returns its GUID. Its bonuses are not applied: it is the drone's, not the character's. */
    fun addWare(modGuid: String, entryId: String, rating: Int?, extra: String = ""): String {
        wareProblem(modGuid, entryId, extra)?.let { error("Cannot fit this: $it") }
        val mod = modNode(modGuid)!!
        val w = data[entryId]!!.element
        val ed = CyberwareEditor(file, data)
        val (min, max) = vehicleOfMod(mod)?.let { upgradeRange(it, w) } ?: ed.range(w, null)
        val chosen = if (max > 0) (rating ?: maxOf(min, 1)).coerceIn(maxOf(min, 1), max) else 0
        val guid = UUID.randomUUID().toString()
        val g = ed.wareElement(entryId, w, guid, "Standard", chosen, max > 0, asDefault = false, parentGuid = null)
        // What the player chose (a skill, or a few words), saved where Chummer keeps it; the skill is saved as the sheet spells it.
        if (extra.isNotBlank()) g.child("extra")?.textContent = choosableSkills().firstOrNull { it.equals(extra.trim(), true) } ?: extra.trim()
        // An attribute upgrade's limits name the limb's own figures ("MinimumAGI"); on a drone they are worked out here, so save the numbers.
        if (vehicleOfMod(mod)?.let { upgradeRange(it, w) } != null) { g.child("minrating")?.textContent = min.toString(); g.child("maxrating")?.textContent = max.toString() }
        val list = mod.child("cyberwares") ?: el("cyberwares").also { mod.appendChild(it) }
        g.appendChild(el("sortorder", list.children("cyberware").size.toString()))
        list.appendChild(g)
        return guid
    }

    /** A limb's Strength or Agility ("STR" or "AGI"): its base, or the rating of the upgrade fitted to it. */
    fun limbFigure(modGuid: String, stat: String): Int? {
        val mod = modNode(modGuid) ?: return null
        val vehicle = vehicleOfMod(mod) ?: return null
        val up = mod.child("cyberwares")?.children("cyberware").orEmpty().firstOrNull { w -> w.text("sourceid")?.trim()?.let { data[it]?.element }?.let(::upgradeStat) == stat }
        return up?.text("rating")?.trim()?.toIntOrNull() ?: limbBase(vehicle, stat)
    }

    /** Take ware out of a drone's mod. */
    fun removeWare(guid: String): Boolean {
        for (v in root.child("vehicles")?.children("vehicle").orEmpty()) for (m in v.child("mods")?.children("mod").orEmpty()) {
            val list = m.child("cyberwares") ?: continue
            val node = list.children("cyberware").firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: continue
            list.removeChild(node)
            return true
        }
        return false
    }

    /** Take a mod off a vehicle, with the improvements it gave. A mod that came with the vehicle stays. */
    fun removeMod(guid: String): Boolean {
        for (vehicle in root.child("vehicles")?.children("vehicle").orEmpty()) {
            val mods = vehicle.child("mods") ?: continue
            val node = mods.children("mod").firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: continue
            if (node.text("included")?.trim().equals("True", true)) return false
            val imps = root.child("improvements")
            if (imps != null) for (i in imps.children("improvement").filter { it.text("sourcename")?.trim().equals(guid, true) }) imps.removeChild(i)
            mods.removeChild(node)
            return true
        }
        return false
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

/** Evaluates the price, slot and rating formulas of vehicle mods, which name the vehicle's own figures (Body, Handling, Speed, Acceleration, Armor, Sensor, Pilot, Seats) and "Vehicle Cost". */
object VehicleFormula {
    /** A figure of a stored vehicle; an "on/off road" figure ("4/3") counts as its first value, as Chummer does. */
    fun figure(vehicle: Element, tag: String): Double = vehicle.text(tag)?.trim().orEmpty().substringBefore('/').trim().toDoubleOrNull() ?: 0.0

    fun number(text: String): Double = chummer.core.Expr.evalOrNull(text.ifBlank { "0" }) ?: 0.0

    private fun vars(vehicle: Element, rating: Int, vehicleCost: Double, slots: Double = 0.0) = mapOf(
        "Rating" to rating.toDouble(), "Body" to figure(vehicle, "body"), "Handling" to figure(vehicle, "handling"), "Speed" to figure(vehicle, "speed"),
        "Acceleration" to figure(vehicle, "accel"), "Armor" to figure(vehicle, "armor"), "Sensor" to figure(vehicle, "sensor"), "Pilot" to figure(vehicle, "pilot"),
        "Seats" to figure(vehicle, "seats"), "VehicleCost" to vehicleCost, "Slots" to slots,
    )

    /** Split "a,b(c,d),e" at the commas outside brackets. */
    private fun split(list: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var start = 0
        for ((i, c) in list.withIndex()) {
            if (c == '(') depth++ else if (c == ')') depth-- else if (c == ',' && depth == 0) { out += list.substring(start, i); start = i + 1 }
        }
        return out + list.substring(start)
    }

    /** The value of a formula for a mod at [rating] on [vehicle], or null when it is beyond the evaluator. `FixedValues(a,b,c)` picks by rating, and each choice may itself be a formula. */
    fun eval(text: String, rating: Int, vehicle: Element, vehicleCost: Double, slots: Double = 0.0): Double? {
        val t = text.trim().replace("Vehicle Cost", "VehicleCost")
        if (t.isEmpty()) return 0.0
        val fixed = Regex("""^FixedValues\((.*)\)$""").find(t)
        val chosen = if (fixed != null) split(fixed.groupValues[1]).let { it.getOrNull((rating - 1).coerceIn(0, it.size - 1)) } ?: return null else t
        return chummer.core.Expr.evalOrNull(chosen.trim().removePrefix("[").removeSuffix("]"), vars(vehicle, rating, vehicleCost, slots))
    }

    /** A mod's price; "Slots" in it is the number of slots the mod itself takes ([slotsText] is its `slots` formula). */
    fun cost(text: String, rating: Int, vehicle: Element, vehicleCost: Double, slotsText: String = ""): Double? =
        eval(text, rating, vehicle, vehicleCost, slots = slots(slotsText, rating, vehicle)?.toDouble() ?: 0.0)

    /** Slots a mod takes (can be negative: a Fragile drone frees some); null when unreadable. */
    fun slots(text: String, rating: Int, vehicle: Element): Int? = eval(text, rating, vehicle, 0.0)?.let { Math.round(it).toInt() }

    /** Slots used by the mods added to a stored vehicle, and the slots it has (its `modslots`, or its Body). */
    fun slots(vehicle: Element): Pair<Int, Int> {
        var used = 0
        for (m in vehicle.child("mods")?.children("mod").orEmpty()) {
            if (m.text("included")?.trim().equals("True", true)) continue
            used += slots(m.text("slots").orEmpty(), m.text("rating")?.trim()?.toIntOrNull() ?: 0, vehicle) ?: 0
        }
        val total = vehicle.text("modslots")?.trim()?.toIntOrNull() ?: figure(vehicle, "body").toInt()
        return used to total
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
