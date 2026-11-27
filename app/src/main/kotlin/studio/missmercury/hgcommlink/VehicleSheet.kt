package studio.missmercury.hgcommlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chummer.core.engine.GearStacks
import chummer.core.engine.SheetSections
import studio.missmercury.hgcommlink.ui.*

/**
 * Everything to do with one vehicle or drone, in a bottom sheet: its figures, the mods on it (the ones it came with stay; ones you added can be
 * sold or taken off), the gear in it, adding either, and selling or removing the whole vehicle. The sheet follows [v], so each change shows at once.
 */
@Composable
internal fun VehicleSheet(
    v: SheetSections.VehicleLine, vm: AppViewModel,
    onAddMod: () -> Unit, onAddGear: () -> Unit, onAddWare: (String) -> Unit, onSell: (() -> Unit)?, onRemove: (() -> Unit)?,
    sell: (String, String) -> (() -> Unit)?, remove: (String) -> (() -> Unit)?, onDismiss: () -> Unit,
) {
    TerminalSheet(v.name, onDismiss, accent = DrainAmber, tall = true) { close ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryStrip(listOfNotNull(
                v.category.takeIf { it.isNotBlank() }?.let { "TYPE" to it },
                v.slotsTotal.takeIf { it > 0 }?.let { "SLOTS" to "${v.slotsUsed} / $it" },
            ), accent = DrainAmber)
            CardTags(v.stats.split("  ").map { it.trim() }.filter { it.isNotEmpty() }.map { it to MutedSilver })
            // The figures above include the fitted mods; say what they changed so a figure that moved is not a surprise.
            CardNote(if (v.changes.isEmpty()) "Figures are the vehicle's own." else "Fitted mods changed: " + v.changes.joinToString(", ") + ".")
            if (v.slotsTotal > 0 && v.slotsUsed > v.slotsTotal) CardTags(listOf("Over by ${v.slotsUsed - v.slotsTotal} slot${if (v.slotsUsed - v.slotsTotal > 1) "s" else ""}" to DrainAmber))

            GroupHeader("Mods", v.modLines.size, DrainAmber)
            if (v.modLines.isEmpty()) CardNote("No mods fitted.")
            v.modLines.forEach { m ->
                ItemCard(if (m.included) MutedSilver else DrainAmber, compact = true) {
                    CardTitle(if (m.rating > 0) "${m.name} ${m.rating}" else m.name, bold = true)
                    CardTags(buildList {
                        if (m.included) add("Came with it" to MutedSilver)
                        if (m.slots != 0) add("${m.slots} slot${if (m.slots == 1 || m.slots == -1) "" else "s"}" to MutedSilver)
                    })
                    // A mod the vehicle came with is part of it and cannot be taken off.
                    // A drone's arm or leg takes cyberware: what is in it, the capacity left, and adding more. It is the drone's, not the character's.
                    if (m.takesWare) {
                        CardTags(listOf(
                            "Capacity ${GearStacks.format(m.capacityUsed)} / ${GearStacks.format(m.capacityTotal)}" to (if (m.capacityUsed > m.capacityTotal) DrainAmber else PhosphorGreen),
                            "STR ${m.strength}" to Quicksilver, "AGI ${m.agility}" to Quicksilver,
                        ))
                        m.ware.forEach { w ->
                            CardTitle(if (w.rating > 0) "${w.name} ${w.rating}" else w.name, trailing = w.capacity.takeIf { it.isNotBlank() }, bold = false)
                            val wareSold = sell(w.guid, w.name)
                            val wareGone = remove(w.guid)
                            if (w.guid.isNotEmpty() && (wareSold != null || wareGone != null)) CardActions {
                                wareSold?.let { CardAction("Sell", { it() }, accent = DrainAmber) }
                                wareGone?.let { CardAction("Remove", { it() }, accent = CriticalRed) }
                            }
                        }
                        CardActions { CardAction("+ Ware", { onAddWare(m.guid) }, accent = PhosphorGreen) }
                    }
                    val sold = if (m.included) null else sell(m.guid, m.name)
                    val gone = if (m.included) null else remove(m.guid)
                    if (m.guid.isNotEmpty() && (sold != null || gone != null)) CardActions {
                        sold?.let { CardAction("Sell", { it() }, accent = DrainAmber) }
                        gone?.let { CardAction("Remove", { it() }, accent = CriticalRed) }
                    }
                }
            }
            CardActions { CardAction("+ Mod", { onAddMod() }, accent = PhosphorGreen) }

            GroupHeader("Gear", v.gearLines.size, Quicksilver)
            if (v.gearLines.isEmpty()) CardNote("No gear in it.")
            v.gearLines.forEach { p ->
                ItemCard(if (p.equipped) Quicksilver else MutedSilver, compact = true) {
                    CardTitle(if (p.extra.isBlank()) p.name else "${p.name} (${p.extra})", trailing = if (p.quantity != "1") "x${p.quantity}" else null, bold = true)
                    CardTags(buildList {
                        if (p.rating > 0) add("Rating ${p.rating}" to MutedSilver)
                        if (!p.equipped) add("Off" to DrainAmber)
                    })
                    val sold = sell(p.guid, p.name)
                    val gone = remove(p.guid)
                    if (p.guid.isNotEmpty()) CardActions {
                        CardAction(if (p.equipped) "Switch off" else "Switch on", { vm.setGearEquipped(p.guid, !p.equipped) }, accent = Quicksilver)
                        sold?.let { CardAction("Sell", { it() }, accent = DrainAmber) }
                        gone?.let { CardAction("Remove", { it() }, accent = CriticalRed) }
                    }
                }
            }
            CardActions { CardAction("+ Gear", { onAddGear() }, accent = PhosphorGreen) }

            if (onSell != null || onRemove != null) {
                GroupHeader("Get rid of it", 1, DrainAmber)
                CardActions {
                    onSell?.let { CardAction("Sell", { it() }, accent = DrainAmber) }
                    onRemove?.let { CardAction("Remove", { it() }, accent = CriticalRed) }
                }
            }
        }
        TerminalButton("Done", close, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
