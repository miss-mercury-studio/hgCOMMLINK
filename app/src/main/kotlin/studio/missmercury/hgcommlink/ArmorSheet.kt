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
import chummer.core.engine.SheetSections
import studio.missmercury.hgcommlink.ui.*

/**
 * Everything to do with one armor, in a bottom sheet: wearing it, the set it is in, switching its mods and installed gear, adding
 * mods and gear, and selling or removing it. The sheet follows [a], so each change shows at once.
 */
@Composable
internal fun ArmorSheet(
    a: SheetSections.ArmorItemLine, setGuid: String, sets: List<SheetSections.ArmorSet>, vm: AppViewModel,
    onAddMod: () -> Unit, onAddGear: (() -> Unit)?, onSell: (() -> Unit)?, onRemove: (() -> Unit)?, onDismiss: () -> Unit,
) {
    TerminalSheet(a.name, onDismiss, accent = PhosphorGreen, tall = true) { close ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryStrip(listOfNotNull("ARMOR" to a.value, "STATE" to if (a.equipped) "WORN" else "OFF", a.capacityLeft?.let { "CAPACITY" to "%.0f".format(java.util.Locale.ROOT, it) }))
            TerminalButton(
                if (a.equipped) "Take off" else "Wear", { vm.setArmorEquipped(a.guid, !a.equipped) }, Modifier.fillMaxWidth(),
                accent = if (a.equipped) DrainAmber else PhosphorGreen, filled = !a.equipped,
            )

            val named = sets.filter { it.guid.isNotEmpty() }
            if (named.isNotEmpty()) {
                GroupHeader("Set", named.size, Quicksilver)
                CardActions {
                    CardAction("No set", { vm.moveArmorToSet(a.guid, "") }, accent = if (setGuid.isEmpty()) PhosphorGreen else MutedSilver)
                    for (s in named) CardAction(s.name, { vm.moveArmorToSet(a.guid, s.guid) }, accent = if (s.guid == setGuid) PhosphorGreen else MutedSilver)
                }
            }

            val mods = a.parts.filter { it.isMod }
            val gear = a.parts.filter { !it.isMod }
            GroupHeader("Mods", mods.size, Quicksilver)
            if (mods.isEmpty()) CardNote("No mods fitted.")
            mods.forEach { PartCard(it, vm) }
            CardActions { CardAction("+ Mod", { onAddMod() }, accent = PhosphorGreen) }

            if (gear.isNotEmpty() || onAddGear != null) {
                GroupHeader("Installed gear", gear.size, Quicksilver)
                if (gear.isEmpty()) CardNote("Nothing installed.")
                gear.forEach { PartCard(it, vm) }
                onAddGear?.let { add -> CardActions { CardAction("+ Gear", { add() }, accent = PhosphorGreen) } }
            }

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

/** One mod or piece of gear in an armor: its name, whether it is switched off, and the switch. */
@Composable
private fun PartCard(p: SheetSections.ArmorPartLine, vm: AppViewModel) {
    ItemCard(if (p.equipped) Quicksilver else MutedSilver, compact = true) {
        CardTitle(p.name, bold = true)
        CardTags(buildList { if (!p.equipped) add("Off" to DrainAmber) })
        if (p.guid.isNotEmpty()) CardActions {
            CardAction(if (p.equipped) "Switch off" else "Switch on", { vm.setArmorPartEquipped(p.guid, p.isMod, !p.equipped) }, accent = Quicksilver)
        }
    }
}

/**
 * One armor set in a bottom sheet: wear or take off everything in it, rename or delete it, and for each armor in it wear it, take it
 * out of the set, or open its own sheet. Armor from elsewhere can be added. Deleting a set keeps its armor.
 */
@Composable
internal fun ArmorSetSheet(
    set: SheetSections.ArmorSet, others: List<SheetSections.ArmorItemLine>, vm: AppViewModel,
    onRename: () -> Unit, onOpenArmor: (String) -> Unit, onDismiss: () -> Unit,
) {
    val worn = set.items.count { it.equipped }
    TerminalSheet(set.name, onDismiss, accent = Quicksilver, tall = true) { close ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryStrip(listOf("ARMOR" to set.items.size.toString(), "WORN" to "$worn / ${set.items.size}"), accent = Quicksilver)
            CardActions {
                if (set.items.any { !it.equipped }) CardAction("Wear all", { vm.setArmorSetEquipped(set.guid, true) }, accent = PhosphorGreen)
                if (worn > 0) CardAction("Take off all", { vm.setArmorSetEquipped(set.guid, false) }, accent = DrainAmber)
                CardAction("Rename", { onRename() }, accent = Quicksilver)
            }
            GroupHeader("In this set", set.items.size, PhosphorGreen)
            if (set.items.isEmpty()) CardNote("Nothing in this set yet.")
            set.items.forEach { a ->
                ItemCard(if (a.equipped) PhosphorGreen else MutedSilver, compact = true) {
                    CardTitle(a.name, trailing = "ARMOR ${a.value}", bold = true)
                    CardTags(buildList { add((if (a.equipped) "Worn" else "Off") to if (a.equipped) PhosphorGreen else DrainAmber) })
                    if (a.guid.isNotEmpty()) CardActions {
                        CardAction(if (a.equipped) "Take off" else "Wear", { vm.setArmorEquipped(a.guid, !a.equipped) }, accent = if (a.equipped) DrainAmber else PhosphorGreen)
                        CardAction("Remove from set", { vm.moveArmorToSet(a.guid, "") }, accent = DrainAmber)
                        CardAction("Manage", { close(); onOpenArmor(a.guid) }, accent = Quicksilver)
                    }
                }
            }
            val addable = others.filter { it.guid.isNotEmpty() }
            if (addable.isNotEmpty()) {
                GroupHeader("Add armor", addable.size, Quicksilver)
                CardActions { for (a in addable) CardAction(a.name, { vm.moveArmorToSet(a.guid, set.guid) }, accent = MutedSilver) }
                CardNote("An armor is in one set at a time, so adding it here moves it out of its other set.")
            }
            GroupHeader("Get rid of it", 1, DrainAmber)
            CardActions { CardAction("Delete set", { vm.removeArmorSet(set.guid) }, accent = CriticalRed) }
            CardNote("Deleting a set keeps its armor.")
        }
        TerminalButton("Done", close, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
