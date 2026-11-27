package studio.missmercury.hgcommlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chummer.core.engine.AccessoryInfo
import chummer.core.engine.ClipInfo
import chummer.core.engine.WeaponTotals
import studio.missmercury.hgcommlink.ui.*

/** The name of an ammunition as a player says it: "APDS", not "Ammo: APDS". */
internal fun ammoLabel(gearName: String) = gearName.removePrefix("Ammo: ").ifBlank { "Empty" }

/**
 * Everything to do with one weapon, in a bottom sheet: what it does now, its clips (which one it fires from, what is loaded, loading,
 * topping up, unloading, firing rounds), the accessories (Spare Clips add clip slots), and selling or removing it. The sheet follows [w],
 * so each change shows at once.
 */
@Composable
internal fun WeaponSheet(
    w: WeaponTotals, vm: AppViewModel, nuyen: String, career: Boolean, creating: Boolean,
    onAccessory: () -> Unit, onSell: (() -> Unit)?, onRemove: (() -> Unit)?, onDismiss: () -> Unit,
) {
    // An accessory being sold (career) or taken off (creation).
    var sellingAccessory by remember { mutableStateOf<AccessoryInfo?>(null) }
    var removingAccessory by remember { mutableStateOf<AccessoryInfo?>(null) }
    // Loading is two steps: choose the ammunition, then how many rounds.
    var loadingSlot by remember { mutableStateOf<Int?>(null) }
    var chosen by remember { mutableStateOf<Pair<Int, chummer.core.engine.ArmorWeaponEditor.AmmoStack>?>(null) }
    val loose = remember(w.clips, w.guid) { vm.compatibleAmmo(w.guid) }

    TerminalSheet(w.name, onDismiss, accent = CriticalRed, tall = true) { close ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryStrip(listOf("DAMAGE" to w.damage, "AP" to w.ap, "ACC" to w.accuracy, "RC" to w.rc), accent = CriticalRed)
            if (w.loadedAmmo.isNotBlank()) Text(
                "Firing ${ammoLabel(w.loadedAmmo)}: its bonuses are in the damage and AP above.", style = MaterialTheme.typography.labelSmall, color = MutedSilver,
            )

            if (w.clips.isNotEmpty()) {
                GroupHeader("Clips", w.clips.size, PhosphorGreen)
                w.clips.forEach { c ->
                    ClipCard(w, c, canLoad = loose.any { c.rounds == 0 || it.name == c.ammo }, vm = vm, onLoad = { loadingSlot = c.slot })
                }
            }

            GroupHeader("Accessories", w.fitted.size, Quicksilver)
            if (w.fitted.isEmpty()) CardNote("Nothing fitted.")
            w.fitted.forEach { a ->
                AccessoryCard(
                    a, canSell = career && !a.included, canRemove = creating && !a.included,
                    onToggle = { vm.setAccessoryEquipped(a.guid, !a.equipped) },
                    onSell = { sellingAccessory = a }, onRemove = { removingAccessory = a },
                )
            }
            if (w.allowAccessory) CardActions {
                CardAction("+ Accessory", { onAccessory() }, accent = PhosphorGreen)
            }
            if (w.allowAccessory && w.clips.isNotEmpty()) CardNote("A Spare Clip adds a clip slot, so you can carry more loaded clips and switch between them.")

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

    // Career: sell it for a share of its price, or discard it for nothing. Creation: take it back for free.
    sellingAccessory?.let { a ->
        SellSheet(a.name, { pct -> vm.sellPurchase(a.guid, pct) }, { vm.discardPurchase(a.guid) }) { sellingAccessory = null }
    }
    removingAccessory?.let { a ->
        ConfirmSheet(
            "Remove ${a.name}", "Take it off the weapon and get its price back." + if (a.clipSlots > 0) " Any rounds in its clip go back to your loose ammunition." else "",
            "Remove", { vm.removePurchase(a.guid) }, { removingAccessory = null }, danger = true,
        )
    }
    loadingSlot?.let { slot ->
        val clip = w.clips.firstOrNull { it.slot == slot }
        val offered = loose.filter { clip == null || clip.rounds == 0 || it.name == clip.ammo }
        PickerSheet(
            if (clip != null && clip.rounds > 0) "Top up clip $slot" else "Load clip $slot",
            offered.map { PickItem(it.guid, ammoLabel(it.name), "×${it.quantity} loose") },
            onPick = { id -> offered.firstOrNull { it.guid == id }?.let { chosen = slot to it } },
            note = if (offered.isEmpty()) "No loose ammunition fits this weapon." else "Choose the ammunition, then how many rounds.",
            onDismiss = { loadingSlot = null },
        )
    }
    chosen?.let { (slot, stack) ->
        val clip = w.clips.firstOrNull { it.slot == slot }
        val room = ((clip?.capacity ?: 0) - (clip?.rounds ?: 0)).coerceAtLeast(0)
        val most = minOf(room, stack.quantity).coerceAtLeast(1)
        var rounds by remember(stack.guid) { mutableStateOf(most) }
        TerminalSheet("Load ${ammoLabel(stack.name)}", onDismiss = { chosen = null; loadingSlot = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Clip $slot holds ${clip?.rounds ?: 0} of ${clip?.capacity ?: 0}. You have ${stack.quantity} loose, so up to $most can go in.",
                    style = MaterialTheme.typography.bodySmall, color = MutedSilver,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("// ROUNDS", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
                    StepButton("−", 48) { rounds = (rounds - 1).coerceAtLeast(1) }
                    Text(rounds.toString(), Modifier.width(56.dp), style = MaterialTheme.typography.titleLarge, color = Quicksilver, textAlign = TextAlign.Center)
                    StepButton("+", 48) { rounds = (rounds + 1).coerceAtMost(most) }
                }
                CardActions {
                    CardAction("Fill (${most})", { rounds = most }, accent = Quicksilver)
                    if (most > 10) CardAction("10", { rounds = 10 }, accent = Quicksilver)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", { close(); loadingSlot = null }, Modifier.weight(1f))
                    TerminalButton("Load", { close(); chosen = null; loadingSlot = null; vm.loadAmmo(w.guid, stack.guid, slot, rounds) }, Modifier.weight(1f), filled = true)
                }
            }
        }
    }
}

/** One clip slot: its number, where it comes from, what is loaded and how full it is, and what you can do with it. */
@Composable
private fun ClipCard(w: WeaponTotals, c: ClipInfo, canLoad: Boolean, vm: AppViewModel, onLoad: () -> Unit) {
    ItemCard(if (c.active) PhosphorGreen else MutedSilver, compact = true) {
        CardTitle("Clip ${c.slot}", trailing = "${c.rounds} / ${c.capacity}", bold = true)
        CardTags(buildList {
            if (c.active) add("Firing from this" to PhosphorGreen)
            add((if (c.owner.isBlank()) "Main" else c.owner) to MutedSilver)
            // Rounds with no ammunition behind them (the ammo item was removed in an older version): say so, and Unload clears it.
            when {
                c.rounds <= 0 -> add("Empty" to DrainAmber)
                c.ammo.isBlank() -> add("Ammo missing" to DrainAmber)
                else -> add(ammoLabel(c.ammo) to Quicksilver)
            }
        })
        // How full it is: a bar in the clip's colour.
        val fill = if (c.capacity > 0) (c.rounds.toFloat() / c.capacity).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).background(TerminalGrid)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(if (c.active) PhosphorGreen else MutedSilver))
        }
        CardActions {
            if (!c.active) CardAction("Use this clip", { vm.setActiveClip(w.guid, c.slot) }, accent = PhosphorGreen, enabled = c.rounds > 0)
            if (c.rounds < c.capacity) CardAction(if (c.rounds > 0) "Top up" else "Load", onLoad, accent = PhosphorGreen, enabled = canLoad)
            if (c.rounds > 0) CardAction("Unload", { vm.unloadAmmo(w.guid, c.slot) }, accent = Quicksilver)
            if (c.active && c.rounds > 0) {
                CardAction("Fire 1", { vm.fireRounds(w.guid, 1, c.slot) }, accent = DrainAmber)
                CardAction("Fire 3", { vm.fireRounds(w.guid, 3, c.slot) }, accent = DrainAmber, enabled = c.rounds >= 3)
            }
        }
    }
}

/** One accessory fitted to the weapon: its name, mount and rating, whether it comes with the weapon or is switched off, and what you can do with it. */
@Composable
private fun AccessoryCard(a: AccessoryInfo, canSell: Boolean, canRemove: Boolean, onToggle: () -> Unit, onSell: () -> Unit, onRemove: () -> Unit) {
    ItemCard(if (a.equipped) Quicksilver else MutedSilver, compact = true) {
        CardTitle(a.name, trailing = if (a.rating > 0) "RATING ${a.rating}" else null, bold = true)
        CardTags(buildList {
            if (a.mount.isNotBlank()) add(a.mount to MutedSilver)
            if (a.clipSlots > 0) add("+${a.clipSlots} clip slot${if (a.clipSlots > 1) "s" else ""}" to PhosphorGreen)
            if (a.included) add("Comes with the weapon" to MutedSilver)
            if (!a.equipped) add("Off" to DrainAmber)
        })
        CardActions {
            // Only where the switch changes something: a Spare Clip or a sling has no figures to turn off.
            if (a.switchable) CardAction(if (a.equipped) "Switch off" else "Switch on", onToggle, accent = Quicksilver)
            if (canSell) CardAction("Sell", onSell, accent = DrainAmber)
            if (canRemove) CardAction("Remove", onRemove, accent = CriticalRed)
        }
    }
}
