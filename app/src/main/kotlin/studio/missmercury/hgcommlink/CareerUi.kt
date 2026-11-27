package studio.missmercury.hgcommlink

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chummer.core.engine.ExpenseEntry
import chummer.core.engine.Sheet
import studio.missmercury.hgcommlink.ui.*

/**
 * A karma upgrade control for career characters: "+20" with the price, or "MAX" at the ceiling. Dimmed when the
 * character cannot pay (it still answers a tap, with the reason).
 */
@Composable
fun UpgradeButton(cost: Int?, karma: Int, onClick: () -> Unit) {
    val affordable = cost != null && cost <= karma
    Box(
        Modifier.widthIn(min = 52.dp).heightIn(min = 44.dp)
            .alpha(if (cost == null || affordable) 1f else 0.45f)
            .border(1.dp, if (affordable) PhosphorGreen else TerminalGrid)
            .clickable(enabled = cost != null, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (cost == null) "MAX" else "+$cost", style = MaterialTheme.typography.labelLarge, color = if (affordable) PhosphorGreen else MutedSilver)
    }
}

/** Karma and nuyen balances, a way to add an entry by hand, and the log with undo. */
@Composable
fun CareerCard(s: Sheet, vm: AppViewModel) {
    val career = s.career ?: return
    var adding by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var undoing by remember { mutableStateOf<ExpenseEntry?>(null) }
    TerminalCard("Career", tag = "00") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TelemetryStrip(listOf("KARMA" to s.karma, "NUYEN" to "¥${s.nuyen.toDoubleOrNull()?.let(::formatNuyen) ?: s.nuyen}"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalButton("Add entry", { adding = true }, Modifier.weight(1f))
                TerminalButton(if (showLog) "Hide log" else "Log (${career.expenses.size})", { showLog = !showLog }, Modifier.weight(1f))
            }
            if (showLog) career.expenses.take(60).forEach { e ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.reason.ifBlank { "(no reason)" }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(e.date.replace('T', ' ').take(16) + "  " + e.type, style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                    }
                    val amount = if (e.amount == Math.rint(e.amount)) e.amount.toLong().toString() else e.amount.toString()
                    Text(
                        (if (e.amount > 0) "+" else "") + amount, Modifier.padding(horizontal = 8.dp),
                        color = if (e.amount < 0) DrainAmber else PhosphorGreen, style = MaterialTheme.typography.labelLarge,
                    )
                    if (e.guid in career.undoable) StepButton("↶", 40, MutedSilver) { undoing = e } else Box(Modifier.widthIn(min = 40.dp))
                }
            }
            if (showLog && career.expenses.size > 60) Text("Showing the newest 60 of ${career.expenses.size}.", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
        }
    }
    if (adding) AddExpenseSheet(vm) { adding = false }
    undoing?.let { e ->
        ConfirmSheet("Undo entry", "Take back \"${e.reason}\"? The ${e.type.lowercase()} balance changes by ${-e.amount.toLong()}.", "Undo", { vm.undoExpense(e.guid) }, { undoing = null })
    }
}

@Composable
private fun AddExpenseSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var karma by remember { mutableStateOf(true) }
    var spend by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val value = amount.toDoubleOrNull()
    TerminalSheet("Add entry", onDismiss) { close ->
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceChips("Currency", listOf("k" to "Karma", "n" to "Nuyen"), if (karma) "k" else "n", { karma = it == "k" })
            ChoiceChips("Direction", listOf("g" to "Gain", "s" to "Spend"), if (spend) "s" else "g", { spend = it == "s" })
            TerminalField(amount, { amount = it.filter { c -> c.isDigit() || (c == '.' && !karma) } }, if (karma) "karma" else "nuyen", keyboardType = KeyboardType.Number)
            TerminalField(reason, { reason = it }, "reason (Mission Reward, ...)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalButton("Cancel", close, Modifier.weight(1f))
                TerminalButton("Save", { close(); vm.addExpense(karma, (if (spend) -1 else 1) * value!!, reason.trim()) }, Modifier.weight(1f), filled = true, enabled = value != null && value > 0)
            }
        }
    }
}

/** Pick a specialization from the game data's list, or type your own. */
@Composable
fun SpecializationSheet(skill: String, cost: Int, choices: List<String>, karma: Int, onPick: (String) -> Unit, onDismiss: () -> Unit, note: String? = null) {
    val affordable = note != null || cost <= karma
    var text by remember { mutableStateOf("") }
    TerminalSheet("Specialize $skill", onDismiss, tall = choices.size > 4) { close ->
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(note ?: "Costs $cost karma; you have $karma.", style = MaterialTheme.typography.labelSmall, color = if (affordable) MutedSilver else DrainAmber)
            choices.forEach { c ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, TerminalGrid).clickable(enabled = affordable) { close(); onPick(c) }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text(c)
                }
            }
            TerminalField(text, { text = it }, "or type your own")
            TerminalButton("Learn", { close(); onPick(text.trim()) }, Modifier.fillMaxWidth(), filled = true, enabled = text.isNotBlank() && affordable)
        }
    }
}

/** A field for what an item is for (a Fake License: which license), with the names Chummer suggests that match what is typed. */
@Composable
private fun WhatFor(label: String, value: String, choices: List<String>, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TerminalField(value, onChange, label)
        val shown = choices.filter { value.isBlank() || (it.contains(value, ignoreCase = true) && !it.equals(value, ignoreCase = true)) }.take(8)
        if (shown.isNotEmpty()) ChoiceChips("Suggestions", shown.map { it to it }, null, onChange)
    }
}

/**
 * Pick gear, a weapon, armor or a vehicle ([kind]: "gear", "weapon", "armor", "vehicle") from the game data, choose rating and quantity where they apply, and pay.
 * With a [vehicleGuid], gear is bought for that vehicle and goes in it.
 */
@Composable
fun BuyGearDialog(kind: String, vm: AppViewModel, nuyen: String, vehicleGuid: String? = null, onDismiss: () -> Unit) {
    class Opt(val id: String, val name: String, val category: String, val cost: Double?, val avail: String, val min: Int, val max: Int, val note: String?, val blocked: Boolean, val ammoFor: List<String> = emptyList(), val askFor: Boolean = false, val choices: List<String> = emptyList())
    val options = remember(kind) {
        when (kind) {
            "weapon" -> vm.weaponOptions().map { o ->
                Opt(o.id, o.name, o.category, o.cost, o.avail, 0, 0, o.detail, o.problem != null)
            }
            "vehicle" -> vm.vehicleOptions().map { o -> Opt(o.id, o.name, o.category, o.cost, o.avail, 0, 0, o.detail, o.problem != null) }
            "armor" -> vm.armorOptions().map { o -> Opt(o.id, o.name, o.category, o.cost, o.avail, 1, o.maxRating, o.detail, o.problem != null) }
            else -> vm.gearOptions().map { o -> Opt(o.id, o.name, o.category, o.cost, o.avail, o.minRating, o.maxRating, if (o.unsupported.isNotEmpty()) "some bonuses not applied yet" else null, false, o.ammoCategories, o.needsRestricted, o.restrictedChoices) }
        }
    }
    val items = remember(kind) {
        options.map { o ->
            PickItem(
                o.id, o.name,
                listOfNotNull(o.category, o.note, o.cost?.let { "¥" + formatNuyen(it) } ?: "price not readable yet", o.avail.takeIf { it.isNotBlank() && it != "0" }?.let { "avail " + chummer.core.engine.GearEditor.availText(it, if (o.max > 0) maxOf(o.min, 1) else 0) },
                    if (o.blocked) "not supported yet" else null).joinToString("  ·  "),
                o.category, warn = o.cost == null || o.blocked,
            )
        }
    }
    var chosen by remember { mutableStateOf<Opt?>(null) }
    PickerSheet(if (vehicleGuid != null) "Gear for the vehicle" else "Buy $kind", items, onPick = { id -> chosen = options.first { it.id == id } }, note = "You have ¥${money(nuyen)}.", keepOpenFor = { true }, onDismiss = onDismiss)
    chosen?.let { o ->
        val hasRating = o.max > 0
        var rating by remember(o.id) { mutableStateOf(if (hasRating) maxOf(o.min, 1).toString() else "") }
        var qty by remember(o.id) { mutableStateOf("1") }
        // Ammunition is bought for one kind of weapon, as in Chummer.
        var weaponType by remember(o.id) { mutableStateOf("") }
        // Some items ask what they are for, as a Fake License asks which license.
        var whatFor by remember(o.id) { mutableStateOf("") }
        val r = if (hasRating) rating.toIntOrNull()?.coerceIn(maxOf(o.min, 1), o.max) else null
        val q = if (kind == "gear") qty.toDoubleOrNull() else 1.0
        val cost = if (q == null || o.blocked) null else when (kind) {
            "weapon" -> vm.weaponCost(o.id)
            "vehicle" -> vm.vehicleCost(o.id)
            "armor" -> vm.armorCost(o.id, r)
            else -> vm.gearCost(o.id, r, q)
        }
        val have = nuyen.toDoubleOrNull() ?: 0.0
        TerminalSheet(o.name, onDismiss = { chosen = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasRating) NumberStepper("Rating", rating, { rating = it }, min = maxOf(o.min, 1), max = o.max)
                if (o.askFor) WhatFor("which license, or what it is for", whatFor, o.choices) { whatFor = it }
                if (o.ammoFor.isNotEmpty()) ChoiceChips("Weapon type", o.ammoFor.map { it to it }, weaponType.ifBlank { null }, { weaponType = it })
                if (kind == "gear") NumberStepper("Quantity", qty, { qty = it }, min = 1, max = 9999, note = "how many", integer = false)
                Text(
                    when {
                        o.blocked -> "This item comes with parts that are not supported yet."
                        cost == null -> "The price of this item cannot be worked out yet."
                        else -> "Costs ¥${formatNuyen(cost)}; you have ¥${money(nuyen)}."
                    },
                    style = MaterialTheme.typography.labelSmall, color = if (cost == null || cost > have) DrainAmber else MutedSilver,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Buy", {
                        close(); chosen = null
                        when (kind) { "weapon" -> vm.buyWeapon(o.id); "vehicle" -> vm.buyVehicle(o.id); "armor" -> vm.buyArmor(o.id, r); else -> if (vehicleGuid != null) vm.buyVehicleGear(vehicleGuid, o.id, r, q!!, weaponType.ifBlank { whatFor.trim() }) else vm.buyGear(o.id, r, q!!, weaponType.ifBlank { whatFor.trim() }) }
                        onDismiss()
                    }, Modifier.weight(1f), filled = true, enabled = cost != null && q != null && q > 0 && cost <= have && (o.ammoFor.isEmpty() || weaponType.isNotBlank()) && (!o.askFor || whatFor.isNotBlank()))
                }
            }
        }
    }
}

/** A nuyen amount kept as text on the sheet, with thousands separators ("¥117,675"); text that is not a number is left as it is. */
internal fun money(raw: String) = raw.toDoubleOrNull()?.let { formatNuyen(it) } ?: raw

internal fun formatNuyen(v: Double) = if (v == Math.rint(v)) "%,d".format(v.toLong()) else "%,.2f".format(v)

/**
 * What an accessory costs on this weapon, as money. Some prices depend on the weapon ("Weapon Cost", a share of it), so the price is worked out for
 * [parentGuid]; one that cannot be worked out keeps the data's own wording.
 */
private fun accessoryPrice(parentGuid: String, o: chummer.core.engine.ItemOption, vm: AppViewModel): String {
    val price = vm.accessoryCost(parentGuid, o.id, if (o.maxRating > 0) 1 else null)
    return when {
        price != null -> (if (o.maxRating > 1) "from ¥" else "¥") + formatNuyen(price)
        else -> "cost " + (o.detail.toDoubleOrNull()?.let { "¥" + formatNuyen(it) } ?: o.detail)
    }
}

/** Pick a weapon accessory ([kind] "accessory", for the weapon with [parentGuid]) or an armor mod ("mod"), choose mount and rating, and pay. */
@Composable
fun AttachPartDialog(kind: String, parentGuid: String, vm: AppViewModel, nuyen: String, onDismiss: () -> Unit) {
    val options = remember(kind) { if (kind == "accessory") vm.accessoryOptions(parentGuid) else vm.armorModOptions(parentGuid) }
    val items = remember(kind) {
        options.map { o ->
            PickItem(
                o.id, o.name,
                listOfNotNull(o.category.takeIf { it.isNotBlank() }, o.detail.takeIf { it.isNotBlank() }?.let { if (kind == "accessory") accessoryPrice(parentGuid, o, vm) else it },
                    // An armor mod's list line has its figures; its price goes after them. A rated one is priced at its lowest rating.
                    if (kind == "accessory") null else (o.cost?.let { (if (o.maxRating > 1) "from ¥" else "¥") + formatNuyen(it) } ?: "price not readable yet"),
                    o.avail.takeIf { it.isNotBlank() && it != "0" }?.let { "avail " + chummer.core.engine.GearEditor.availText(it, 1) }).joinToString("  ·  "),
                o.category,
            )
        }
    }
    var chosen by remember { mutableStateOf<chummer.core.engine.ItemOption?>(null) }
    PickerSheet(if (kind == "accessory") "Add accessory" else "Add armor mod", items, onPick = { id -> chosen = options.first { it.id == id } }, note = "You have ¥${money(nuyen)}.", keepOpenFor = { true }, onDismiss = onDismiss)
    chosen?.let { o ->
        val hasRating = o.maxRating > 1 || (kind == "accessory" && o.maxRating > 0)
        var rating by remember(o.id) { mutableStateOf(if (hasRating) "1" else "") }
        var mount by remember(o.id) { mutableStateOf(o.mounts.firstOrNull()) }
        val r = if (hasRating) rating.toIntOrNull()?.coerceIn(1, o.maxRating) else null
        val cost = if (kind == "accessory") vm.accessoryCost(parentGuid, o.id, r) else vm.armorModCost(o.id, r)
        val have = nuyen.toDoubleOrNull() ?: 0.0
        TerminalSheet(o.name, onDismiss = { chosen = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasRating) NumberStepper("Rating", rating, { rating = it }, min = 1, max = o.maxRating)
                if (kind == "accessory" && o.mounts.size > 1) ChoiceChips("Mount", o.mounts.map { it to it }, mount, { mount = it })
                Text(
                    if (cost == null) "The price of this item cannot be worked out yet." else "Costs ¥${formatNuyen(cost)}; you have ¥${money(nuyen)}.",
                    style = MaterialTheme.typography.labelSmall, color = if (cost == null || cost > have) DrainAmber else MutedSilver,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Buy", {
                        close(); chosen = null
                        if (kind == "accessory") vm.buyAccessory(parentGuid, o.id, mount, r) else vm.buyArmorMod(parentGuid, o.id, r)
                        onDismiss()
                    }, Modifier.weight(1f), filled = true, enabled = cost != null && cost <= have)
                }
            }
        }
    }
}

/** Pick a mod for the vehicle or drone [vehicleGuid] (only those that fit it), choose its rating where it has one, see the price and slots, and pay. */
@Composable
fun VehicleModDialog(vehicleGuid: String, vm: AppViewModel, nuyen: String, onDismiss: () -> Unit) {
    val options = remember(vehicleGuid) { vm.vehicleModOptions(vehicleGuid) }
    val items = remember(options) {
        options.map { o ->
            PickItem(
                o.id, o.name,
                listOfNotNull(
                    o.category.takeIf { it.isNotBlank() }, o.detail.takeIf { it.isNotBlank() },
                    o.cost?.let { (if (o.maxRating > 0) "from " else "") + "¥" + formatNuyen(it) } ?: "price not readable yet",
                    o.avail.takeIf { it.isNotBlank() && it != "0" }?.let { "avail " + runCatching { chummer.core.engine.GearEditor.availText(it, maxOf(o.minRating, 1)) }.getOrDefault(it) },
                ).joinToString("  ·  "),
                // Chummer's mod category "All" means any vehicle, which the picker's own "All" filter would be confused with.
                if (o.category == "All") "General" else o.category, warn = o.cost == null,
            )
        }
    }
    var chosen by remember { mutableStateOf<chummer.core.engine.ItemOption?>(null) }
    PickerSheet("Add vehicle mod", items, onPick = { id -> chosen = options.first { it.id == id } }, note = if (options.isEmpty()) "No mods fit this vehicle." else "You have ¥${money(nuyen)}.", keepOpenFor = { true }, onDismiss = onDismiss)
    chosen?.let { o ->
        val hasRating = o.maxRating > 0
        val low = maxOf(o.minRating, 1)
        var rating by remember(o.id) { mutableStateOf(if (hasRating) low.toString() else "") }
        val r = if (hasRating) rating.toIntOrNull()?.coerceIn(minOf(low, o.maxRating), o.maxRating) else null
        val cost = vm.vehicleModCost(vehicleGuid, o.id, r)
        val have = nuyen.toDoubleOrNull() ?: 0.0
        TerminalSheet(o.name, onDismiss = { chosen = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasRating) NumberStepper("Rating", rating, { rating = it }, min = low, max = o.maxRating)
                o.detail.removePrefix("slots ").toIntOrNull()?.let { n ->
                    Text("Uses $n mod slot${if (n == 1) "" else "s"}${if (hasRating) " at its lowest rating" else ""}.", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                }
                Text(
                    if (cost == null) "The price of this item cannot be worked out yet." else "Costs ¥${formatNuyen(cost)}; you have ¥${money(nuyen)}.",
                    style = MaterialTheme.typography.labelSmall, color = if (cost == null || cost > have) DrainAmber else MutedSilver,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Buy", { close(); chosen = null; vm.buyVehicleMod(vehicleGuid, o.id, r); onDismiss() }, Modifier.weight(1f), filled = true, enabled = cost != null && cost <= have)
                }
            }
        }
    }
}

/** Pick cyberware or bioware, choose grade and rating, see price and essence, and install it. */
@Composable
fun BuyWareDialog(bioware: Boolean, vm: AppViewModel, nuyen: String, essence: Double, parent: chummer.core.engine.SheetSections.WareLine? = null, onDismiss: () -> Unit) {
    val options = remember(bioware, parent?.guid) { if (parent != null) vm.pluginOptions(parent.guid) else vm.wareOptions().filter { it.isBioware == bioware } }
    val grades = remember(bioware) { vm.wareGrades(bioware) }
    val items = remember(bioware) {
        options.map { o ->
            PickItem(
                o.id, o.name,
                listOfNotNull(
                    o.category, o.cost?.let { "from ¥" + formatNuyen(it) } ?: "price not readable yet", o.essence?.takeIf { parent == null || o.addsToParent }?.let { "ess " + "%.2f".format(java.util.Locale.ROOT, it) },
                    o.avail.takeIf { it.isNotBlank() && it != "0" }?.let { "avail " + chummer.core.engine.GearEditor.availText(it, if (o.maxRating > 0) maxOf(o.minRating, 1) else 0) }, o.problem?.let { "not supported yet: $it" },
                    if (o.unsupported.isNotEmpty()) "some bonuses not applied yet" else null,
                ).joinToString("  ·  "),
                o.category, warn = o.problem != null || o.cost == null || o.unsupported.isNotEmpty(),
            )
        }
    }
    var chosen by remember { mutableStateOf<chummer.core.engine.WareOption?>(null) }
    PickerSheet(if (bioware) "Install bioware" else "Install cyberware", items, onPick = { id -> chosen = options.first { it.id == id } }, note = "You have ¥${money(nuyen)} and ${"%.2f".format(java.util.Locale.ROOT, essence)} essence.", keepOpenFor = { true }, onDismiss = onDismiss)
    chosen?.let { o ->
        val forced = o.forcedGrade.isNotEmpty() || parent != null
        var grade by remember(o.id) { mutableStateOf(if (parent != null) parent.grade.ifEmpty { "Standard" } else if (forced) o.forcedGrade else if ("Standard" in grades) "Standard" else grades.firstOrNull().orEmpty()) }
        val hasRating = o.maxRating > 0
        var rating by remember(o.id) { mutableStateOf(if (hasRating) maxOf(o.minRating, 1).toString() else "") }
        val r = if (hasRating) rating.toIntOrNull()?.coerceIn(maxOf(o.minRating, 1), o.maxRating) else null
        val cost = if (o.problem == null) vm.wareCost(o.id, grade, r, parent?.guid) else null
        val ess = if (o.problem == null) vm.wareEssence(o.id, grade, r, parent?.guid) else null
        val have = nuyen.toDoubleOrNull() ?: 0.0
        val ok = cost != null && ess != null && cost <= have && (parent != null && o.essence != null && !o.addsToParent || essence - ess >= 0)
        TerminalSheet(o.name, onDismiss = { chosen = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!forced && grades.size > 1) ChoiceChips("Grade", grades.map { it to it }, grade, { grade = it })
                if (hasRating) NumberStepper("Rating", rating, { rating = it }, min = maxOf(o.minRating, 1), max = o.maxRating)
                Text(
                    when {
                        o.problem != null -> "This item is not supported yet: ${o.problem}."
                        cost == null || ess == null -> "The price or essence of this item cannot be worked out yet."
                        parent != null && !o.addsToParent -> "Costs ¥${formatNuyen(cost)}, no essence (a plug-in); you have ¥${money(nuyen)}."
                        else -> "Costs ¥${formatNuyen(cost)} and ${"%.2f".format(java.util.Locale.ROOT, ess)} essence; you have ¥${money(nuyen)} and ${"%.2f".format(java.util.Locale.ROOT, essence)} essence."
                    },
                    style = MaterialTheme.typography.labelSmall, color = if (ok) MutedSilver else DrainAmber,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Install", { close(); chosen = null; vm.buyWare(o.id, grade, r, parent?.guid); onDismiss() }, Modifier.weight(1f), filled = true, enabled = ok)
                }
            }
        }
    }
}

/** The creation nuyen budget: karma put into nuyen, what the character carries, and what is left. */
@Composable
fun NuyenCard(l: chummer.core.engine.NuyenLedger, vm: AppViewModel) {
    TerminalCard("Nuyen", tag = "02") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TelemetryStrip(
                listOf("BUDGET" to "¥" + formatNuyen(l.budget), "SPENT" to "¥" + formatNuyen(l.spent), "LEFT" to "¥" + formatNuyen(l.remaining)),
                accent = if (l.remaining < 0) CriticalRed else PhosphorGreen,
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Karma into nuyen" + if (l.maxKarma >= 0) "  ${l.karmaBought}/${l.maxKarma}" else "  ${l.karmaBought}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                StepButton("−", 44) { vm.changeNuyenBp(-1) }
                StepButton("+", 44) { vm.changeNuyenBp(1) }
            }
            if (l.unreadable.isNotEmpty()) Text("Prices not counted yet: " + l.unreadable.take(4).joinToString(), style = MaterialTheme.typography.labelSmall, color = DrainAmber)
        }
    }
}

/** The adept's power points and powers as list items: a counter, one row per power with its levels, and a way to add more. */
fun androidx.compose.foundation.lazy.LazyListScope.adeptPowerItems(pp: chummer.core.engine.PowerPoints, vm: AppViewModel, onAdd: () -> Unit) {
    item {
        val over = pp.used > pp.total + 0.001
        TerminalCard("Adept powers", tag = "03", accent = if (over) CriticalRed else PhosphorGreen) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TelemetryStrip(
                    listOf("POINTS" to formatPoints(pp.total), "USED" to formatPoints(pp.used), "LEFT" to formatPoints(pp.left)),
                    accent = if (over) CriticalRed else PhosphorGreen,
                )
                if (pp.powers.isEmpty()) Text("No powers yet.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
                pp.powers.forEach { p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (p.extra.isNotBlank()) " (${p.extra})" else "", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text(
                                listOfNotNull(formatPoints(p.points) + " pts", if (p.freeLevels > 0) "${p.freeLevels} free level" + if (p.freeLevels > 1) "s" else "" else null).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall, color = MutedSilver,
                            )
                        }
                        if (p.levels) {
                            StepButton("−", 44) { vm.changePowerLevel(p.guid, -1) }
                            Text("${p.rating}", Modifier.widthIn(min = 22.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            StepButton("+", 44) { vm.changePowerLevel(p.guid, 1) }
                        }
                        StepButton("×", 36, CriticalRed.copy(alpha = 0.85f)) { vm.removePower(p.guid) }
                    }
                }
                TerminalButton("Add power", onAdd, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun formatPoints(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0')

/** Pick an adept power from the data; powers that ask for a skill, an attribute or text get a second step. */
@Composable
fun AddPowerDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember { vm.powerOptions() }
    val items = remember {
        options.map { o ->
            PickItem(
                o.id, o.name,
                listOfNotNull(
                    formatPoints(o.pointsPerLevel) + if (o.levels) " pts per level" else " pts",
                    if (o.maxLevels > 0) "max ${o.maxLevels}" else null, o.source.takeIf { it.isNotBlank() }?.let { it + " " + o.page },
                    if (o.unsupported.isNotEmpty()) "some effects not applied yet" else null, if (o.needsChoice) "needs a choice" else null,
                ).joinToString("  ·  "),
                warn = o.unsupported.isNotEmpty(),
            )
        }
    }
    var choosing by remember { mutableStateOf<chummer.core.engine.PowerOption?>(null) }
    var text by remember { mutableStateOf("") }
    PickerSheet(
        "Add power", items,
        onPick = { id ->
            val o = options.first { it.id == id }
            if (o.needsChoice) { choosing = o; text = "" } else vm.addPower(id)
        },
        note = "Power points come from MAG. Prerequisites are not checked yet.",
        keepOpenFor = { id -> options.first { it.id == id }.needsChoice },
        onDismiss = onDismiss,
    )
    choosing?.takeIf { it.choices.isNotEmpty() }?.let { o ->
        PickerSheet(o.name, o.choices.map { PickItem(it, it, "") }, onPick = { vm.addPower(o.id, it); choosing = null; onDismiss() }, note = "Choose one.", onDismiss = { choosing = null })
    }
    choosing?.takeIf { it.choices.isEmpty() }?.let { o ->
        TerminalSheet(o.name, onDismiss = { choosing = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This power asks for a name or a choice.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
                TerminalField(text, { text = it }, "name, skill...")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Add", { vm.addPower(o.id, text.trim()); choosing = null; onDismiss() }, Modifier.weight(1f), filled = true, enabled = text.isNotBlank())
                }
            }
        }
    }
}

/** Pick gear that fits an armor's remaining capacity, choose a rating, and pay. */
@Composable
fun ArmorGearDialog(armorGuid: String, room: Double, vm: AppViewModel, nuyen: String, onDismiss: () -> Unit) {
    val options = remember(armorGuid, room) { vm.armorGearOptions(room) }
    val items = remember(options) {
        options.map { o ->
            PickItem(o.id, o.name, listOfNotNull(o.category, o.armorCapacity?.let { "capacity %.0f".format(it) }, o.cost?.let { "¥" + formatNuyen(it) } ?: "price not readable yet").joinToString("  ·  "), o.category, warn = o.cost == null)
        }
    }
    var chosen by remember { mutableStateOf<chummer.core.engine.GearOption?>(null) }
    PickerSheet("Install armor gear", items, onPick = { id -> chosen = options.first { it.id == id } }, note = "Capacity left %.0f. You have ¥${money(nuyen)}.".format(room), keepOpenFor = { true }, onDismiss = onDismiss)
    chosen?.let { o ->
        val hasRating = o.maxRating > 0
        var rating by remember(o.id) { mutableStateOf(if (hasRating) maxOf(o.minRating, 1).toString() else "") }
        val r = if (hasRating) rating.toIntOrNull()?.coerceIn(maxOf(o.minRating, 1), o.maxRating) else null
        val cost = vm.gearCost(o.id, r, 1.0)
        val have = nuyen.toDoubleOrNull() ?: 0.0
        TerminalSheet(o.name, onDismiss = { chosen = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasRating) NumberStepper("Rating", rating, { rating = it }, min = maxOf(o.minRating, 1), max = o.maxRating)
                Text(
                    if (cost == null) "The price of this item cannot be worked out yet." else "Costs ¥${formatNuyen(cost)}; you have ¥${money(nuyen)}.",
                    style = MaterialTheme.typography.labelSmall, color = if (cost == null || cost > have) DrainAmber else MutedSilver,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Buy", { close(); chosen = null; vm.buyArmorGear(armorGuid, o.id, r); onDismiss() }, Modifier.weight(1f), filled = true, enabled = cost != null && cost <= have)
                }
            }
        }
    }
}

/** Pick gear to plug into another gear item (what its categories and capacity allow), choose a rating, and pay. */
@Composable
fun GearPluginDialog(parentGuid: String, parentName: String, vm: AppViewModel, nuyen: String, onDismiss: () -> Unit) {
    val options = remember(parentGuid) { vm.gearPluginOptions(parentGuid) }
    val items = remember(options) {
        options.map { o ->
            PickItem(o.id, o.name, listOfNotNull(o.category, o.armorCapacity?.let { "capacity %.0f".format(it) }, o.cost?.let { "¥" + formatNuyen(it) } ?: "price not readable yet").joinToString("  ·  "), o.category, warn = o.cost == null || o.unsupported.isNotEmpty())
        }
    }
    var chosen by remember { mutableStateOf<chummer.core.engine.GearOption?>(null) }
    PickerSheet("Plug into $parentName", items, onPick = { id -> chosen = options.first { it.id == id } }, note = if (options.isEmpty()) "Nothing fits in this item." else "You have ¥${money(nuyen)}.", keepOpenFor = { true }, onDismiss = onDismiss)
    chosen?.let { o ->
        val hasRating = o.maxRating > 0
        var rating by remember(o.id) { mutableStateOf(if (hasRating) maxOf(o.minRating, 1).toString() else "") }
        val r = if (hasRating) rating.toIntOrNull()?.coerceIn(maxOf(o.minRating, 1), o.maxRating) else null
        val cost = vm.gearCost(o.id, r, 1.0)
        val have = nuyen.toDoubleOrNull() ?: 0.0
        var whatFor by remember(o.id) { mutableStateOf("") }
        TerminalSheet(o.name, onDismiss = { chosen = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (o.needsRestricted) WhatFor("which license, or what it is for", whatFor, o.restrictedChoices) { whatFor = it }
                if (hasRating) NumberStepper("Rating", rating, { rating = it }, min = maxOf(o.minRating, 1), max = o.maxRating)
                Text(
                    if (cost == null) "The price of this item cannot be worked out yet." else "Costs ¥${formatNuyen(cost)}; you have ¥${money(nuyen)}.",
                    style = MaterialTheme.typography.labelSmall, color = if (cost == null || cost > have) DrainAmber else MutedSilver,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Buy", { close(); chosen = null; vm.buyGearPlugin(parentGuid, o.id, r, whatFor.trim()); onDismiss() }, Modifier.weight(1f), filled = true, enabled = cost != null && cost <= have && (!o.needsRestricted || whatFor.isNotBlank()))
                }
            }
        }
    }
}

/** Initiation (or submersion) as list items: the grade, what the next one costs, and the metamagics (echoes) taken. */
fun androidx.compose.foundation.lazy.LazyListScope.initiationItems(section: chummer.core.engine.InitiationSection, vm: AppViewModel, onInitiate: () -> Unit, onMetamagic: () -> Unit, onArt: () -> Unit = {}, onMore: (String) -> Unit = {}) {
    val word = if (section.technomancer) "Submersion" else "Initiation"
    val thing = if (section.technomancer) "echo" else "metamagic"
    item {
        Column(Modifier.padding(top = 8.dp)) {
            Text("// ${word.uppercase()}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "grade ${section.grade} of ${section.maxGrade} possible" + if (section.grade < section.maxGrade) "  ·  next grade ${section.nextCost} karma" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (section.metamagics.isEmpty()) item { Text("No ${thing}s.", style = MaterialTheme.typography.bodySmall) }
    items(section.metamagics.size) { i ->
        val m = section.metamagics[i]
        Column(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalArrangement = Arrangement.Center) {
            Text(m.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("grade ${m.grade}" + if (m.paid) "  ·  paid with karma" else "", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
        }
    }
    if (section.arts.isNotEmpty()) {
        item { Text("Arts", style = MaterialTheme.typography.labelMedium, color = MutedSilver, modifier = Modifier.padding(top = 6.dp)) }
        items(section.arts.size) { i ->
            val a = section.arts[i]
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("grade ${a.grade}", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                }
                StepButton("×", 36) { vm.removeArt(a.guid) }
            }
        }
    }
    if (section.enhancements.isNotEmpty()) {
        item { Text("Enhancements", style = MaterialTheme.typography.labelMedium, color = MutedSilver, modifier = Modifier.padding(top = 6.dp)) }
        items(section.enhancements.size) { i -> Text(section.enhancements[i].name + "  ·  grade ${section.enhancements[i].grade}", Modifier.heightIn(min = 36.dp)) }
    }
    item {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton(if (section.grade >= section.maxGrade) "Grade limit" else "+ Grade", { if (section.grade < section.maxGrade) onInitiate() }, Modifier.weight(1f), enabled = section.grade < section.maxGrade)
            TerminalButton("+ ${thing.replaceFirstChar { it.uppercase() }}", onMetamagic, Modifier.weight(1f), enabled = section.grade > 0)
            if (!section.technomancer) TerminalButton("+ Art", onArt, Modifier.weight(1f), enabled = section.grade > 0)
        }
        if (!section.technomancer) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("+ Enhance", { onMore("enhancement") }, Modifier.weight(1f), enabled = section.grade > 0)
            TerminalButton("+ Enchant", { onMore("Enchantments") }, Modifier.weight(1f), enabled = section.grade > 0)
            TerminalButton("+ Ritual", { onMore("Rituals") }, Modifier.weight(1f), enabled = section.grade > 0)
        }
    }
}

/** Take the next initiation grade: the discounts it can have, and the karma (and nuyen) it costs. */
@Composable
fun InitiateSheet(section: chummer.core.engine.InitiationSection, vm: AppViewModel, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf("No") }
    var ordeal by remember { mutableStateOf("No") }
    var schooling by remember { mutableStateOf("No") }
    val cost = vm.initiationCost(group == "Yes", ordeal == "Yes", schooling == "Yes")
    TerminalSheet(if (section.technomancer) "Submerge" else "Initiate", onDismiss) { close ->
        val yesNo = listOf("No" to "No", "Yes" to "Yes")
        ChoiceChips("Group initiation", yesNo, group, { group = it })
        ChoiceChips("Ordeal", yesNo, ordeal, { ordeal = it })
        ChoiceChips("Schooling (10,000 nuyen)", yesNo, schooling, { schooling = it })
        Text("Grade ${section.grade + 1} costs $cost karma" + if (schooling == "Yes") " and 10,000 nuyen" else "", Modifier.padding(vertical = 10.dp))
        TerminalButton("Take grade ${section.grade + 1}", { vm.initiate(group == "Yes", ordeal == "Yes", schooling == "Yes"); close() }, Modifier.fillMaxWidth().padding(bottom = 12.dp), filled = true)
    }
}

/** The metamagics (echoes) on offer; ones with unmet requirements are shown with what they need and cannot be taken. */
@Composable
fun MetamagicPicker(section: chummer.core.engine.InitiationSection, vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember { vm.metamagicOptions() }
    val items = options.map {
        PickItem(it.id, it.name, listOf(it.source, if (it.unmet.isNotEmpty()) it.unmet else "").filter { x -> x.isNotBlank() }.joinToString("  ·  "), warn = it.unmet.isNotEmpty())
    }
    val thing = if (section.technomancer) "echo" else "metamagic"
    val free = section.metamagics.none { it.grade == section.grade }
    PickerSheet(
        "Add a $thing", items, { id -> if (options.firstOrNull { it.id == id }?.unmet.isNullOrEmpty()) vm.addMetamagic(id) }, keepOpenFor = { id -> options.firstOrNull { it.id == id }?.unmet?.isNotEmpty() == true },
        note = if (free) "The first one of grade ${section.grade} is part of the grade." else "A second one of grade ${section.grade} costs ${section.extraMetamagicCost} karma.",
        onDismiss = onDismiss,
    )
}

/** The arts on offer (free); ones whose requirements are not met show what they need. */
@Composable
fun ArtPicker(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember { vm.artOptions() }
    val items = options.map { PickItem(it.id, it.name, listOf(it.source, it.unmet).filter { x -> x.isNotBlank() }.joinToString("  ·  "), warn = it.unmet.isNotEmpty()) }
    PickerSheet("Add an art", items, { id -> if (options.firstOrNull { it.id == id }?.unmet.isNullOrEmpty()) vm.addArt(id) }, keepOpenFor = { id -> options.firstOrNull { it.id == id }?.unmet?.isNotEmpty() == true }, note = "Arts cost nothing.", onDismiss = onDismiss)
}

/** Enhancements (2 karma each), or enchantments and rituals at the latest grade (free when it is the first thing of the grade). */
@Composable
fun GradeItemPicker(kind: String, section: chummer.core.engine.InitiationSection, vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember(kind) { if (kind == "enhancement") vm.enhancementOptions() else vm.gradeSpellOptions(kind) }
    val items = options.map { PickItem(it.id, it.name, listOf(it.source, it.unmet).filter { x -> x.isNotBlank() }.joinToString("  ·  "), warn = it.unmet.isNotEmpty()) }
    val note = if (kind == "enhancement") "An enhancement costs ${section.enhancementCost} karma."
    else if (section.metamagics.any { it.grade == section.grade } || section.gradeSpells.any { it.grade == section.grade }) "Something already came with grade ${section.grade}, so this costs the spell price."
    else "The first thing taken with grade ${section.grade} is free."
    PickerSheet(
        if (kind == "enhancement") "Add an enhancement" else "Add " + (if (kind == "Rituals") "a ritual" else "an enchantment"), items,
        { id -> if (options.firstOrNull { it.id == id }?.unmet.isNullOrEmpty()) { if (kind == "enhancement") vm.addEnhancement(id) else vm.addGradeSpell(id) } },
        keepOpenFor = { id -> options.firstOrNull { it.id == id }?.unmet?.isNotEmpty() == true }, note = note, onDismiss = onDismiss,
    )
}
