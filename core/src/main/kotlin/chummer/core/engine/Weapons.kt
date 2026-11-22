package chummer.core.engine

import chummer.core.Expr
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import kotlin.math.ceil

/** A weapon's figures with accessories, loaded ammunition and the character's improvements applied. */
data class WeaponTotals(
    val name: String,
    val type: String,
    val category: String,
    val damage: String,
    val ap: String,
    val mode: String,
    val rc: String,
    val ammo: String,
    val reach: String,
    val accuracy: String,
    val conceal: String,
    val accessories: List<String>,
    val guid: String = "",
    /** Accessories can be added, and ammunition loaded (guns that need it; not melee, unarmed or gear-linked weapons). */
    val allowAccessory: Boolean = false,
    val canLoad: Boolean = false,
)

/**
 * Weapon figures, a port of the parts of Chummer's `Weapon` that decide damage, AP, accuracy, recoil
 * compensation and concealment. Accessory bonuses are read from the weapon as saved; the loaded ammunition is
 * the gear in the weapon's active ammo slot.
 *
 * Not yet covered: wireless bonuses, fire modes changed by accessories, underbarrel weapons inheriting the
 * parent's smartgun, flechette ammo, the dice pool, and weapons with non-numeric damage ("Special",
 * "Chemical"), which are shown as stored.
 */
class WeaponEngine(
    private val root: Element,
    private val improvements: ImprovementSet,
    private val strength: Int,
    private val physicalLimit: Int,
    /** Weapon guid to the ammo slot to use instead of the saved one (for replaying a different state). */
    private val ammoSlots: Map<String, Int> = emptyMap(),
) {
    private val gearByGuid: Map<String, Element> by lazy {
        val map = HashMap<String, Element>()
        fun walk(e: Element) {
            for (g in e.children("gear")) {
                g.text("guid")?.trim()?.let { map[it] = g }
                g.child("children")?.let(::walk)
            }
        }
        root.child("gears")?.let(::walk)
        map
    }

    private fun Element.t(tag: String) = text(tag).orEmpty().trim()

    /** An accessory's own rating, used by expressions in its bonuses. */
    private fun Element.rating() = t("rating").toIntOrNull() ?: 0

    private fun num(text: String, rating: Int = 0): Double? {
        val s = text.trim().removePrefix("+")
        if (s.isEmpty()) return 0.0
        return runCatching { Expr.eval(s.replace("{Rating}", rating.toString()).replace("Rating", rating.toString())) }.getOrNull()
    }

    fun all(): List<WeaponTotals> = root.child("weapons")?.children("weapon").orEmpty().map(::totals)

    private class Bonus(val damage: Double = 0.0, val ap: Double = 0.0, val accuracy: Double = 0.0)

    /** The gear in the weapon's active ammo slot, if any. */
    private fun loadedAmmo(w: Element): Element? {
        val slot = ammoSlots[w.t("guid")] ?: w.t("activeammoslot").toIntOrNull() ?: return null
        val clip = w.child("clips")?.children("clip")?.getOrNull(slot - 1) ?: return null
        return gearByGuid[clip.t("id")]
    }

    private fun totals(w: Element): WeaponTotals {
        val name = w.t("name")
        val category = w.t("category")
        val rating = w.t("rating").toIntOrNull() ?: 0
        val accessories = w.child("accessories")?.children("accessory").orEmpty().filter { it.t("equipped").equals("True", true) }
        val ammo = loadedAmmo(w)?.child("weaponbonus")

        // ---- damage ----
        var damageText = ammo?.t("damagereplace")?.takeIf { it.isNotEmpty() } ?: w.t("damage")
        damageText = damageText.replace("{Rating}", rating.toString())
        val damage = damage(w, name, category, damageText, accessories, ammo)

        // ---- armor penetration ----
        val ap = armorPenetration(w.t("ap"), accessories, ammo)

        // ---- accuracy ----
        val accuracy = accuracy(w.t("accuracy"), accessories, ammo, rating)

        // ---- recoil compensation ----
        val rc = recoil(w.t("rc"), accessories, rating)

        // ---- concealment ----
        val conceal = (num(w.t("conceal"), rating) ?: 0.0) + accessories.sumOf { num(it.t("conceal"), it.rating()) ?: 0.0 }

        return WeaponTotals(
            name, w.t("type"), category, damage, ap, w.t("mode").takeIf { it != "0" }.orEmpty(), rc,
            ammoCapacity(w.t("ammo"), accessories),
            w.t("reach"), accuracy, signed(conceal), accessories.map { it.t("name") }, w.t("guid"),
            allowAccessory = w.t("allowaccessory").equals("True", true) && w.t("weapontype") != "gear" && w.t("included") != "True",
            canLoad = w.t("requireammo").equals("True", true) && w.t("type").equals("Ranged", true) && w.t("weapontype") != "gear" && w.t("ammo").let { it.isNotEmpty() && it != "0" },
        )
    }

    /** Ammo capacity such as `38(c)`, raised by extended-clip style accessories (a percentage of the base). */
    private fun ammoCapacity(stored: String, accessories: List<Element>): String {
        val m = Regex("^([0-9]+)(.*)$").find(stored.trim()) ?: return stored
        val percent = accessories.sumOf { num(it.t("ammobonus"), it.rating()) ?: 0.0 }
        if (percent == 0.0) return stored
        return "${standardRound(m.groupValues[1].toInt() * (1 + percent / 100))}${m.groupValues[2]}"
    }

    private fun signed(v: Double): String {
        val n = standardRound(v)
        return if (n > 0) "+$n" else n.toString()
    }

    private fun damage(w: Element, name: String, category: String, stored: String, accessories: List<Element>, ammo: Element?): String {
        var text = stored.replace("{STR}", strength.toString())
        var type = ""
        var extra = ""
        if (text.contains("P or S")) { type = "P or S"; text = text.replace("P or S", "") }
        else if (text.contains('P')) { type = "P"; text = text.replace("P", "") }
        else if (text.contains('S')) { type = "S"; text = text.replace("S", "") }
        else if (text.contains("(M)")) { type = "M"; text = text.replace("(M)", "") }
        for (e in listOf("(e)", "(f)", "(fire)")) if (text.contains(e)) { extra = e; text = text.replace(e, ""); break }
        var splash = ""
        if (text.contains("/m)") || text.contains(" Radius)")) {
            val start = text.indexOf('(')
            val end = text.indexOf(')')
            if (start in 0 until end) { splash = " " + text.substring(start, end + 1); text = text.removeRange(start, end + 1).trim() }
        }
        val base = Expr.evalOrNull(text) ?: return stored

        var bonus = improvements.valueOf("WeaponCategoryDV", category) + improvements.valueOf("WeaponCategoryDV", name)
        if (name == "Unarmed Attack") {
            if (type == "S" && improvements.matching("UnarmedDVPhysical").isNotEmpty()) type = "P"
            bonus += improvements.valueOf("UnarmedDV")
        }
        for (a in accessories) {
            a.t("damagetype").takeIf { it.isNotEmpty() }?.let { type = ""; extra = it }
            bonus += num(a.t("damage"), a.rating()) ?: 0.0
        }
        if (ammo != null) {
            ammo.t("damagetype").takeIf { it.isNotEmpty() }?.let { type = ""; extra = it }
            bonus += num(ammo.t("damage")) ?: 0.0
        }
        return "${standardRound(base + bonus)}$type$extra$splash"
    }

    private fun armorPenetration(stored: String, accessories: List<Element>, ammo: Element?): String {
        var base = ammo?.t("apreplace")?.takeIf { it.isNotEmpty() } ?: stored
        base = base.trim()
        val value = if (base == "-" || base.isEmpty()) 0.0 else base.toDoubleOrNull() ?: return stored
        val bonus = accessories.sumOf { num(it.t("ap"), it.rating()) ?: 0.0 } + (ammo?.let { num(it.t("ap")) } ?: 0.0)
        val total = standardRound(value + bonus)
        return if (total == 0) "-" else total.toString()
    }

    private fun accuracy(stored: String, accessories: List<Element>, ammo: Element?, rating: Int): String {
        val baseText = stored.trim()
        val base = if (baseText.equals("Physical", true)) physicalLimit.toDouble() else baseText.toDoubleOrNull() ?: return stored
        var bonus = 0.0
        // Smartgun and sight accessories do not stack with each other: only the best counts.
        val nonStacking = ArrayList<Double>()
        for (a in accessories) {
            val v = num(a.t("accuracy"), a.rating()) ?: continue
            if (v == 0.0) continue
            if (a.t("name").startsWith("Smartgun") || a.t("name").contains("Sight")) nonStacking += v else bonus += v
        }
        nonStacking.maxOrNull()?.let { bonus += it }
        ammo?.let { bonus += num(it.t("accuracy")) ?: 0.0 }
        val total = standardRound(base + bonus)
        val shownBase = standardRound(base)
        return if (!baseText.equals("Physical", true) && shownBase != total) "$shownBase ($total)" else total.toString()
    }

    private fun recoil(stored: String, accessories: List<Element>, rating: Int): String {
        val free = 1 + ceil(strength / 3.0).toInt()
        val base = num(stored.substringBefore('(').ifBlank { "0" }, rating)?.let { standardRound(it) } ?: 0
        // Accessories in the same recoil group do not stack: the best one counts; group 0 always stacks.
        fun sum(deployable: Boolean): Int {
            val chosen = accessories.filter { it.t("rcdeployable").equals("True", true) == deployable }
            var total = 0.0
            for ((group, list) in chosen.groupBy { it.t("rcgroup").ifEmpty { "0" } }) {
                val values = list.map { num(it.t("rc"), it.rating()) ?: 0.0 }
                total += if (group == "0") values.sum() else values.maxOrNull() ?: 0.0
            }
            return standardRound(total)
        }
        val plain = free + base + sum(false)
        val deploy = sum(true)
        return if (deploy != 0) "$plain (${plain + deploy})" else plain.toString()
    }
}
