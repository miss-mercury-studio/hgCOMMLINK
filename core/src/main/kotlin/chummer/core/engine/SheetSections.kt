package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * The equipment and life sections of the sheet. These show values as stored in the file; only a few are
 * calculated (weapon damage from STR, essence of ware). Weapon figures do not yet include accessories and mods.
 */
data class SheetSections(
    val armorSets: List<ArmorSet>,
    val weapons: List<WeaponLine>,
    val ware: List<WareLine>,
    val gear: List<GearLine>,
    val vehicles: List<VehicleLine>,
    val contacts: List<ContactLine>,
    val lifestyles: List<LifestyleLine>,
    val martialArts: List<String>,
) {
    data class ArmorItemLine(val name: String, val value: String, val equipped: Boolean, val mods: List<String>)
    /** Armor grouped by equipment set; the set name is empty for armor not in any set. */
    data class ArmorSet(val name: String, val items: List<ArmorItemLine>)
    data class WeaponLine(
        val name: String, val category: String, val damage: String, val ap: String, val mode: String,
        val rc: String, val ammo: String, val reach: String, val accuracy: String, val accessories: List<String>,
    )
    data class WareLine(val name: String, val grade: String, val rating: Int, val essence: Double, val isBioware: Boolean, val children: List<WareLine>)
    data class GearLine(val name: String, val category: String, val quantity: String, val rating: Int, val children: List<String>)
    data class VehicleLine(val name: String, val category: String, val stats: String)
    data class ContactLine(val name: String, val type: String, val connection: Int, val loyalty: Int, val notes: String)
    data class LifestyleLine(val name: String, val type: String, val monthly: String, val notes: String)

    companion object {
        private fun Element.t(tag: String) = text(tag).orEmpty().trim()
        private fun Element.n(tag: String) = t(tag).toIntOrNull() ?: 0

        fun read(c: CharacterFile, strength: Int, essence: EssenceEngine): SheetSections {
            val locations = c.armorLocations()
            val armors = c.root.child("armors")?.children("armor").orEmpty()
            val sets = armors.groupBy { locations[it.t("location")].orEmpty() }.map { (set, items) ->
                ArmorSet(
                    set,
                    items.map { a ->
                        ArmorItemLine(
                            a.t("name"), armorWithMods(a), a.t("equipped").equals("True", true),
                            a.child("armormods")?.children("armormod").orEmpty()
                                .filter { it.t("included") != "True" }
                                .map { m -> m.t("name") + m.n("rating").takeIf { it > 0 }?.let { " $it" }.orEmpty() },
                        )
                    },
                )
            }.sortedBy { if (it.name.isEmpty()) "~" else it.name }

            val weapons = c.root.child("weapons")?.children("weapon").orEmpty()
                .map { w ->
                    val melee = w.t("type").equals("Melee", true)
                    WeaponLine(
                        w.t("name"), w.t("category"), damageText(w.t("damage"), strength), w.t("ap"), w.t("mode"),
                        w.t("rc"), if (melee) "" else w.t("ammo"), if (melee) w.t("reach") else "", w.t("accuracy"),
                        w.child("accessories")?.children("accessory").orEmpty().map { it.t("name") },
                    )
                }

            fun ware(a: CharacterFile.Augmentation, hasParent: Boolean): WareLine = WareLine(
                a.name, a.grade.takeIf { it != "None" }.orEmpty(), a.rating,
                essence.calculatedEss(a, hasParent), a.improvementSource.equals("Bioware", true),
                a.children.map { ware(it, true) },
            )
            val wares = c.augmentations().map { ware(it, false) }

            val gear = c.root.child("gears")?.children("gear").orEmpty().map { g ->
                GearLine(
                    g.t("name"), g.t("category"), g.t("qty").removeSuffix(".0"), g.n("rating"),
                    g.child("children")?.children("gear").orEmpty().map { it.t("name") },
                )
            }.sortedWith(compareBy({ it.category }, { it.name }))

            val vehicles = c.root.child("vehicles")?.children("vehicle").orEmpty().map { v ->
                VehicleLine(
                    v.t("name"), v.t("category"),
                    "Handling ${v.t("handling")}  Speed ${v.t("speed")}  Body ${v.t("body")}  Armor ${v.t("armor")}  Pilot ${v.t("pilot")}",
                )
            }

            val contacts = c.root.child("contacts")?.children("contact").orEmpty().map {
                ContactLine(it.t("name"), it.t("contacttype"), it.n("connection"), it.n("loyalty"), it.t("notes"))
            }.sortedBy { it.name.lowercase() }

            val lifestyles = c.root.child("lifestyles")?.children("lifestyle").orEmpty().map {
                LifestyleLine(it.t("name"), it.t("baselifestyle"), it.t("cost"), it.t("notes"))
            }

            val arts = c.root.child("martialarts")?.children("martialart").orEmpty().map { it.t("name") }
            return SheetSections(sets, weapons, wares, gear, vehicles, contacts, lifestyles, arts)
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

        /** Damage such as `({STR}+1)P` shown with STR filled in; anything that is not plain arithmetic is left as stored. */
        fun damageText(stored: String, strength: Int): String {
            if (!stored.contains("{STR}")) return stored
            val m = Regex("^(.*)([A-Za-z]+(?:\\(.*\\))?)$").find(stored) ?: return stored
            val expr = m.groupValues[1].replace("{STR}", strength.toString())
            val value = runCatching { standardRound(chummer.core.Expr.eval(expr)) }.getOrNull() ?: return stored
            return "$value${m.groupValues[2]}"
        }
    }
}
