package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * The equipment and life sections of the sheet. Most show values as stored in the file; weapons and ware are
 * calculated (see [WeaponEngine] and [EssenceEngine]).
 */
data class SheetSections(
    val armorSets: List<ArmorSet>,
    val weapons: List<WeaponTotals>,
    val ware: List<WareLine>,
    val gear: List<GearLine>,
    val vehicles: List<VehicleLine>,
    val contacts: List<ContactLine>,
    val lifestyles: List<LifestyleLine>,
    val martialArts: List<MartialArtLine>,
) {
    /** A martial arts style and its techniques (GUIDs for removing them). */
    data class MartialArtLine(val guid: String, val name: String, val techniques: List<Pair<String, String>>)
    data class ArmorItemLine(val name: String, val value: String, val equipped: Boolean, val mods: List<String>, val guid: String = "",
        /** Gear installed in the armor, and the capacity left for more (null when the armor has none). */
        val gear: List<String> = emptyList(), val capacityLeft: Double? = null,
        /** The mods and installed gear that can be switched on and off, and whether each is on. */
        val parts: List<ArmorPartLine> = emptyList())
    data class ArmorPartLine(val guid: String, val name: String, val equipped: Boolean, val isMod: Boolean)
    /** Armor grouped by equipment set; the set name (and [guid]) is empty for armor not in any set. A set with no armor still shows. */
    data class ArmorSet(val name: String, val items: List<ArmorItemLine>, val guid: String = "")
    data class WareLine(val name: String, val grade: String, val rating: Int, val essence: Double, val isBioware: Boolean, val children: List<WareLine>, val guid: String = "", val sourceId: String = "")
    data class GearPartLine(val guid: String, val name: String, val quantity: String, val rating: Int, val extra: String, val equipped: Boolean = true)
    data class GearLine(val name: String, val category: String, val quantity: String, val rating: Int, val children: List<String>, val guid: String = "", val loaded: Boolean = false, val mergeable: Boolean = false,
        /** The plug-ins and included items inside it, and whether more can be plugged in. */
        val parts: List<GearPartLine> = emptyList(), val pluggable: Boolean = false,
        /** What the item was chosen for (ammunition: the kind of weapon), shown after the name as Chummer does. */
        val extra: String = "")
    /** A mod on a vehicle. [included] ones came with it (already paid, cannot be taken off); [slots] is what it uses. */
    data class VehicleModLine(val guid: String, val name: String, val rating: Int, val included: Boolean, val slots: Int,
        /** For a drone arm or leg: whether it takes cyberware, the capacity used and in all, and the ware in it. */
        val takesWare: Boolean = false, val capacityUsed: Double = 0.0, val capacityTotal: Double = 0.0, val ware: List<DroneWareLine> = emptyList(),
        /** For a limb: its Strength (the drone's Body, or the upgrade's rating) and Agility (the drone's Pilot, or the upgrade's rating). */
        val strength: Int = 0, val agility: Int = 0)
    data class DroneWareLine(val guid: String, val name: String, val category: String, val rating: Int, val capacity: String, val extra: String = "")
    data class VehicleLine(val name: String, val category: String, val stats: String, val guid: String = "", val mods: List<String> = emptyList(),
        /** Each mod and each piece of gear on it, for the vehicle's own sheet. */
        val modLines: List<VehicleModLine> = emptyList(), val gearLines: List<GearPartLine> = emptyList(),
        /** Mod slots used by mods added to it, and the slots it has. */
        val slotsUsed: Int = 0, val slotsTotal: Int = 0,
        /** What fitted mods changed in the figures, "Handling 4 → 5". */
        val changes: List<String> = emptyList())
    data class ContactLine(val name: String, val type: String, val connection: Int, val loyalty: Int, val notes: String)
    data class LifestyleLine(val name: String, val type: String, val monthly: String, val notes: String, val guid: String = "", val months: String = "")

    companion object {
        private fun Element.t(tag: String) = text(tag).orEmpty().trim()

        /** What is inside an item, for its card: one part by its name and what it is for, several of one kind as "Fake License x8" (the sheet lists them in full). */
        private fun includes(parts: List<Element>): List<String> =
            parts.groupBy { it.t("name") }.map { (name, same) ->
                if (same.size > 1) "$name x${same.size}" else name + same[0].t("extra").takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            }
        private fun Element.n(tag: String) = t(tag).toIntOrNull() ?: 0

        fun read(c: CharacterFile, weapons: List<WeaponTotals>, essence: EssenceEngine, data: GameData? = null): SheetSections {
            val locations = c.armorLocations()
            val armors = c.root.child("armors")?.children("armor").orEmpty()
            // Group by set id; a set with no armor still shows, so armor can be moved into it. An unknown id counts as no set.
            val byId = armors.groupBy { it.t("location").takeIf { id -> id in locations }.orEmpty() }
            val sets = (locations.keys + "").distinct().filter { it in byId || it.isNotEmpty() }.map { id -> id to byId[id].orEmpty() }.map { (id, items) ->
                ArmorSet(
                    locations[id].orEmpty(),
                    guid = id,
                    items = items.map { a ->
                        ArmorItemLine(
                            a.t("name"), armorWithMods(a), a.t("equipped").equals("True", true),
                            a.child("armormods")?.children("armormod").orEmpty()
                                .filter { it.t("included") != "True" }
                                .map { m -> m.t("name") + m.n("rating").takeIf { it > 0 }?.let { " $it" }.orEmpty() },
                            a.t("guid"),
                            a.child("gears")?.children("gear").orEmpty().map { g -> g.t("name") + g.n("rating").takeIf { it > 0 }?.let { " $it" }.orEmpty() },
                            capacityLeft(c, data, a),
                            parts = a.child("armormods")?.children("armormod").orEmpty().filter { it.t("included") != "True" }
                                .map { m -> ArmorPartLine(m.t("guid"), m.t("name"), m.t("equipped").equals("True", true), true) } +
                                a.child("gears")?.children("gear").orEmpty().map { g -> ArmorPartLine(g.t("guid"), g.t("name"), g.t("equipped").equals("True", true), false) },
                        )
                    },
                )
            }.sortedBy { if (it.name.isEmpty()) "~" else it.name }

            fun ware(a: CharacterFile.Augmentation, hasParent: Boolean): WareLine = WareLine(
                a.name, a.grade.takeIf { it != "None" }.orEmpty(), a.rating,
                essence.calculatedEss(a, hasParent), a.improvementSource.equals("Bioware", true),
                a.children.map { ware(it, true) }, a.guid, a.sourceId,
            )
            val wares = c.augmentations().map { ware(it, false) }

            val allGear = c.root.child("gears")?.children("gear").orEmpty()
            val inClips = GearStacks.loadedIds(c.root)
            val gear = allGear.map { g ->
                GearLine(
                    g.t("name"), g.t("category"), g.t("qty").removeSuffix(".0"), g.n("rating"),
                    includes(g.child("children")?.children("gear").orEmpty()),
                    g.t("guid"), g.t("location") == "loaded" || g.t("guid").lowercase() in inClips, GearStacks.twins(allGear, g, inClips).isNotEmpty(),
                    parts = g.child("children")?.children("gear").orEmpty().map { p -> GearPartLine(p.t("guid"), p.t("name"), p.t("qty").removeSuffix(".0"), p.n("rating"), p.t("extra"), !p.t("equipped").equals("False", true)) },
                    pluggable = (g.t("sourceid").takeIf { it.isNotEmpty() }?.let { data?.get(it)?.element } ?: g).let { GearPlugins.accepts(it) },
                    extra = g.t("extra"),
                )
            }.sortedWith(compareBy({ it.category }, { it.name }, { it.extra }))

            val vehicles = c.root.child("vehicles")?.children("vehicle").orEmpty().map { v ->
                VehicleLine(
                    v.t("name"), v.t("category"),
                    run {
                        // The figures with the fitted mods applied (see VehicleFigures); "4/3" is on-road and off-road.
                        val t = VehicleFigures.total(v, data)
                        fun fig(on: String, off: String) = if (t[off] != t[on]) "${t[on]}/${t[off]}" else "${t[on]}"
                        "Handling ${fig("handling", "offroadhandling")}  Speed ${fig("speed", "offroadspeed")}  Accel ${fig("accel", "offroadaccel")}  Body ${t["body"]}  Armor ${t["armor"]}  Pilot ${t["pilot"]}  Sensor ${t["sensor"]}" +
                            (t["seats"]?.takeIf { it != 0 }?.let { "  Seats $it" } ?: "")
                    },
                    v.t("guid"), v.child("mods")?.children("mod").orEmpty().map { it.t("name") },
                    modLines = v.child("mods")?.children("mod").orEmpty().map { m ->
                        val takes = data?.get(m.t("sourceid"))?.element?.child("subsystems")?.children("subsystem").orEmpty().isNotEmpty()
                        VehicleModLine(m.t("guid"), m.t("name"), m.n("rating"), m.t("included").equals("True", true), VehicleFormula.slots(m.t("slots"), m.n("rating"), v) ?: 0,
                            takesWare = takes,
                            capacityUsed = m.child("cyberwares")?.children("cyberware").orEmpty().sumOf { Regex("""^\[([0-9.]+)\]$""").find(it.t("capacity"))?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 },
                            capacityTotal = m.t("capacity").toDoubleOrNull() ?: 0.0,
                            ware = m.child("cyberwares")?.children("cyberware").orEmpty().map { w -> DroneWareLine(w.t("guid"), w.t("name"), w.t("category"), w.n("rating"), w.t("capacity"), w.t("extra")) },
                            strength = if (takes) limbFigure(v, m, data, "{STR", "body") else 0, agility = if (takes) limbFigure(v, m, data, "{AGI", "pilot") else 0)
                    },
                    gearLines = v.child("gears")?.children("gear").orEmpty().map { g -> GearPartLine(g.t("guid"), g.t("name"), g.t("qty").removeSuffix(".0"), g.n("rating"), g.t("extra"), !g.t("equipped").equals("False", true)) },
                    slotsUsed = VehicleFormula.slots(v).first, slotsTotal = VehicleFormula.slots(v).second,
                    changes = VehicleFigures.changes(VehicleFigures.base(v), VehicleFigures.total(v, data)),
                )
            }

            val contacts = c.root.child("contacts")?.children("contact").orEmpty().map {
                ContactLine(it.t("name"), it.t("contacttype"), it.n("connection"), it.n("loyalty"), it.t("notes"))
            }.sortedBy { it.name.lowercase() }

            val lifestyles = c.root.child("lifestyles")?.children("lifestyle").orEmpty().map {
                LifestyleLine(it.t("name"), it.t("baselifestyle"), it.t("cost"), it.t("notes"), it.t("guid"), it.t("months"))
            }

            val arts = c.root.child("martialarts")?.children("martialart").orEmpty().map { a ->
                MartialArtLine(a.t("guid"), a.t("name"), a.child("martialarttechniques")?.children("martialarttechnique").orEmpty().map { it.t("guid") to it.t("name") })
            }
            return SheetSections(sets, weapons, wares, gear, vehicles, contacts, lifestyles, arts)
        }

        /** A limb's Strength or Agility: the rating of the upgrade that sets it (its data entry's limits name [marker]), else the drone's [base] figure. */
        private fun limbFigure(vehicle: Element, mod: Element, data: GameData?, marker: String, base: String): Int =
            mod.child("cyberwares")?.children("cyberware").orEmpty().firstOrNull { w ->
                data?.get(w.t("sourceid"))?.element?.let { (it.child("minrating")?.textContent.orEmpty() + it.child("rating")?.textContent.orEmpty()).contains(marker) } == true
            }?.n("rating") ?: vehicle.n(base)

        private fun capacityLeft(c: CharacterFile, data: GameData?, a: Element): Double? {
            if (data == null || a.t("armorcapacity").isBlank() || a.t("armorcapacity") == "0") return null
            return ArmorWeaponEditor(c, data).armorCapacityLeft(a.t("guid"))
        }

        /** The armor value with its equipped mods added, keeping the leading `+` of a stacking accessory. */
        private fun armorWithMods(a: Element): String {
            val base = a.t("armor")
            val mods = a.child("armormods")?.children("armormod").orEmpty()
                .filter { it.t("equipped").equals("True", true) }.sumOf { it.n("armor") }
            val own = runCatching {
                standardRound(chummer.core.Expr.eval(base.removePrefix("+").ifBlank { "0" }, mapOf("Rating" to a.n("rating").toDouble())))
            }.getOrNull() ?: return base
            val total = own + mods
            return if (base.startsWith("+")) "+$total" else total.toString()
        }
    }
}
