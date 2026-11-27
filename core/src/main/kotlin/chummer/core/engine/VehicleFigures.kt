package chummer.core.engine

import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * A vehicle's figures with its fitted mods applied. The saved vehicle holds the base figures; each mod's `bonus` changes them:
 *  - a signed value (`+Rating`, `-1`, `+Seats * 0.5`) is added;
 *  - an unsigned value (`Rating`, `0`) sets the figure, as the drone upgrades do (their rating is the new figure) and Immobile does (speed 0).
 * Sets are applied first, then the additions. Mods that came with the vehicle are already in its base figures, and a mod switched off (`equipped` False)
 * adds nothing. A bonus that asks for a choice (`selecttext`) has no figure. Not applied: a mod's effect on the figures that the price and slot formulas
 * read (they use the base), and the device rating, which the sheet does not show.
 */
object VehicleFigures {
    /** The stored tags of the figures, in the order the card shows them, with their names. */
    val names = linkedMapOf(
        "handling" to "Handling", "offroadhandling" to "Off-road handling", "speed" to "Speed", "offroadspeed" to "Off-road speed",
        "accel" to "Accel", "offroadaccel" to "Off-road accel", "body" to "Body", "armor" to "Armor", "pilot" to "Pilot", "sensor" to "Sensor", "seats" to "Seats",
    )

    /** The base figure as saved. An off-road figure missing from the file is the on-road one. */
    private fun base(vehicle: Element, tag: String): Int {
        fun read(t: String) = vehicle.text(t)?.trim().orEmpty().substringBefore('/').trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
        return read(tag) ?: (if (tag.startsWith("offroad")) read(tag.removePrefix("offroad")) else null) ?: 0
    }

    fun base(vehicle: Element): Map<String, Int> = names.keys.associateWith { base(vehicle, it) }

    /** The figures with the fitted mods' bonuses applied; a figure never goes below 0. */
    fun total(vehicle: Element, data: GameData?): Map<String, Int> {
        val base = base(vehicle)
        val set = HashMap<String, Int>()
        val add = HashMap<String, Int>()
        for (m in vehicle.child("mods")?.children("mod").orEmpty()) {
            if (m.text("included")?.trim().equals("True", true) || m.text("equipped")?.trim().equals("False", true)) continue
            val bonus = m.text("sourceid")?.trim()?.takeIf { it.isNotEmpty() }?.let { data?.get(it)?.element }?.child("bonus") ?: m.child("bonus") ?: continue
            val rating = m.text("rating")?.trim()?.toIntOrNull() ?: 0
            for (tag in names.keys) {
                val text = bonus.child(tag)?.textContent?.trim().orEmpty()
                if (text.isEmpty()) continue
                val signed = text.startsWith("+") || text.startsWith("-")
                val value = VehicleFormula.eval(text.removePrefix("+"), rating, vehicle, 0.0)?.let { Math.round(it).toInt() } ?: continue
                if (signed) add[tag] = (add[tag] ?: 0) + value else set[tag] = value
            }
        }
        return base.mapValues { (tag, b) -> maxOf((set[tag] ?: b) + (add[tag] ?: 0), 0) }
    }

    /**
     * What the mods changed, for a player: "Handling 4 → 5". An on-road and off-road pair that moved by the same amount is one line ("Speed 4/3 → 6/5",
     * or "Speed 4 → 6" when both were the same); one that moved differently, as tires do, is two.
     */
    fun changes(base: Map<String, Int>, total: Map<String, Int>): List<String> {
        val out = ArrayList<String>()
        val merged = HashSet<String>()
        fun figure(map: Map<String, Int>, road: String, off: String) = if (map[road] == map[off]) "${map[road]}" else "${map[road]}/${map[off]}"
        for ((tag, name) in names) {
            if (tag in merged || base[tag] == total[tag]) continue
            val off = "offroad$tag"
            if (off in names && base[off] != total[off] && total[tag]!! - base[tag]!! == total[off]!! - base[off]!!) {
                out += "$name ${figure(base, tag, off)} → ${figure(total, tag, off)}"
                merged += off
            } else out += "$name ${base[tag]} → ${total[tag]}"
        }
        return out
    }
}
