package studio.missmercury.hgcommlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import chummer.core.engine.QualityOption
import chummer.core.engine.Sheet
import chummer.core.engine.SkillResult

private val TABS = listOf("Overview", "Skills", "Gear", "Ware", "Life", "Qualities")

/** The character sheet: calculated numbers on three tabs. Read-only. */
@Composable
fun SheetScreen(open: OpenCharacter, vm: AppViewModel) {
    val sheet = open.sheet
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        when {
            sheet == null && open.sheetError == null -> Text("Preparing game data. This takes several seconds the first time, and again whenever custom data changes.", Modifier.padding(24.dp))
            sheet == null -> Text(
                "Could not calculate this character: ${open.sheetError}",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error,
            )
            else -> {
                Header(sheet, open.warning)
                                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> Overview(sheet, vm)
                    1 -> Skills(sheet, vm)
                    2 -> Gear(sheet.sections)
                    3 -> Ware(sheet.sections, sheet.essence)
                    4 -> Life(sheet.sections)
                    else -> Qualities(sheet, vm)
                }
            }
        }
    }
}

@Composable
private fun Header(s: Sheet, warning: String?) {
    var showWarning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    s.metatype.takeIf { it.isNotBlank() }, if (s.isCareer) "Career" else "Creation",
                    s.nuyen.takeIf { it.isNotBlank() }?.let { "¥$it" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            val l = s.karmaLedger
            if (l != null) {
                Text(
                    "${l.remaining} karma left", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = if (l.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            } else if (s.karma.isNotBlank()) {
                Text("Karma ${s.karma}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
        s.karmaLedger?.problems?.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        if (warning != null) {
            // One line until tapped, so a long explanation does not push the sheet down.
            Text(
                if (showWarning) warning else "⚠ $warning",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                maxLines = if (showWarning) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { showWarning = !showWarning }.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Overview(s: Sheet, vm: AppViewModel) {
    val creation = s.karmaLedger != null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        s.karmaLedger?.let { KarmaSection(it) }

        // The numbers needed in play, together: initiative, condition, armor.
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Initiative", "${s.initiativeValue} + ${s.initiativeDice}d6", Modifier.weight(1.4f))
                    Stat("Physical", s.physicalCM.toString(), Modifier.weight(1f))
                    Stat("Stun", s.stunCM.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Armor", s.armor.general.toString(), Modifier.weight(1.4f))
                    Stat("Soak", s.soak.toString(), Modifier.weight(1f))
                    Stat("Essence", formatEssence(s.essence), Modifier.weight(1f))
                }
                val extras = buildList {
                    add("Overflow ${s.cmOverflow}")
                    if (s.armor.encumbrance != 0) add("Encumbrance ${s.armor.encumbrance}")
                    s.armor.byType.filter { it.second != s.armor.general }.forEach { add("${it.first} ${it.second}") }
                }
                Text(extras.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section("Attributes") {
            if (creation) {
                // Two to a row, each with its own minus and plus, so the buttons are big enough to hit.
                s.attributes.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { a -> AttributeTile(a, a.name in BUYABLE, vm, Modifier.weight(1f)) }
                        repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                // Four to a row reads well on a phone held upright.
                s.attributes.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a -> Stat(a.name, a.total.toString(), Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        Section("Limits") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Physical", s.limits.physical.toString(), Modifier.weight(1f))
                Stat("Mental", s.limits.mental.toString(), Modifier.weight(1f))
                Stat("Social", s.limits.social.toString(), Modifier.weight(1f))
                Stat("Astral", s.limits.astral.toString(), Modifier.weight(1f))
            }
        }

        Section("Dice pools", collapsible = true, startExpanded = false) {
            s.pools.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label)
                    Text(value.toString(), fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lift / carry")
                Text("${s.liftWeight} kg / ${s.carryWeight} kg", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** One attribute in creation: its name, the value, and buttons to buy or give back a point. */
@Composable
private fun AttributeTile(a: Sheet.AttributeLine, buyable: Boolean, vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(a.name, Modifier.width(44.dp), fontWeight = FontWeight.Bold)
        if (buyable) StepButton("−") { vm.changeAttribute(a.name, -1) } else Spacer(Modifier.size(40.dp))
        Text(a.total.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (buyable) StepButton("+") { vm.changeAttribute(a.name, 1) } else Spacer(Modifier.size(40.dp))
    }
}

private fun formatEssence(e: Double) = String.format(java.util.Locale.ROOT, "%.2f", e)

@Composable
private fun Section(
    title: String, collapsible: Boolean = false, startExpanded: Boolean = true, summary: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (summary != null) Text(summary, style = MaterialTheme.typography.titleSmall)
                if (collapsible) Text(if (expanded) "  ▾" else "  ▸", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) content()
        }
    }
}

/** A labelled number, centered. */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Skills(s: Sheet, vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var addingKnowledge by remember { mutableStateOf(false) }
    var groupsOpen by rememberSaveable { mutableStateOf(false) }
    val q = query.trim()
    fun matches(r: SkillResult) = q.isEmpty() || r.name.contains(q, ignoreCase = true)
    val buy = if (s.karmaLedger != null) vm else null
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            OutlinedTextField(
                query, { query = it }, label = { Text("Search skills") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        // Skill groups: folded away until wanted, since there are eleven of them.
        if (buy != null && q.isEmpty() && s.skillGroups.isNotEmpty()) {
            item {
                val bought = s.skillGroups.count { it.karma > 0 }
                Row(Modifier.fillMaxWidth().clickable { groupsOpen = !groupsOpen }.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Skill groups", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (bought > 0) Text("$bought bought", style = MaterialTheme.typography.labelSmall)
                    Text(if (groupsOpen) "  ▾" else "  ▸", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (groupsOpen) items(s.skillGroups, key = { "g-" + it.name }) { g ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, Modifier.weight(1f))
                    StepButton("−", 36) { vm.changeSkillGroup(g.name, -1) }
                    Text("${g.karma}", Modifier.width(24.dp), textAlign = TextAlign.Center)
                    StepButton("+", 36) { vm.changeSkillGroup(g.name, 1) }
                }
            }
        }
        // Knowledge skills and languages come before the long list of active skills.
        val knowledge = s.knowledge.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        if (knowledge.isNotEmpty() || buy != null) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Knowledge and languages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    s.karmaLedger?.let { Text("free points ${it.knowledgePointsLeft} of ${it.knowledgePoints}", style = MaterialTheme.typography.labelSmall) }
                    if (buy != null) TextButton(onClick = { addingKnowledge = true }) { Text("Add") }
                }
            }
            items(knowledge, key = { "k-" + it.guid.ifEmpty { it.name } }) { k -> KnowledgeRow(k, buy) }
        }
        // Skills the character has come first; the rest are listed after, as Chummer does.
        val (rated, unrated) = s.activeSkills.filter(::matches).partition { it.rating > 0 }
        if (rated.isNotEmpty()) item { SubHeader("Active skills") }
        items(rated, key = { "a-" + it.name }) { SkillRow(it, buy) }
        if (unrated.isNotEmpty()) item { SubHeader(if (rated.isEmpty()) "Active skills" else "Unrated") }
        items(unrated, key = { "u-" + it.name }) { SkillRow(it, buy) }
    }
    if (addingKnowledge) AddKnowledgeDialog(vm) { addingKnowledge = false }
}

@Composable
private fun KnowledgeRow(k: Sheet.KnowledgeLine, buy: AppViewModel?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(k.name)
            Text(
                listOfNotNull(k.type, if (k.native) "native" else "${k.points} points, ${k.karma} karma").joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (buy != null && !k.native) {
            StepButton("−", 36) { buy.changeKnowledge(k.guid, -1) }
            Text("${k.points + k.karma}", Modifier.width(20.dp), textAlign = TextAlign.Center)
            StepButton("+", 36) { buy.changeKnowledge(k.guid, 1) }
        } else {
            Text(if (k.native) "N" else "${k.points + k.karma}", Modifier.padding(horizontal = 12.dp))
        }
        if (buy != null) StepButton("×", 36) { buy.removeKnowledge(k.guid) }
        Text(if (k.native) "N" else "${k.pool}", Modifier.width(36.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
    }
}

/** Pick a knowledge skill or language from the game data (typing your own name comes later). */
@Composable
private fun AddKnowledgeDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember { vm.knowledgeOptions() }
    var query by remember { mutableStateOf("") }
    var native by remember { mutableStateOf(false) }
    val shown = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add knowledge skill") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().clickable { native = !native }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = native, onCheckedChange = { native = it })
                    Text("Native language (free; for a language)")
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shown, key = { it.id }) { o ->
                        Column(Modifier.fillMaxWidth().clickable { vm.addKnowledge(o.id, native && o.category == "Language"); onDismiss() }.padding(vertical = 6.dp)) {
                            Text(o.name)
                            Text(o.category, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SkillRow(r: SkillResult, buy: AppViewModel?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.name)
            Text(
                listOf(r.attribute, if (r.rating == 0 && r.canDefault) "default" else if (r.rating == 0) "cannot default" else "")
                    .filter { it.isNotBlank() }.joinToString("  "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (buy != null) {
            StepButton("−", 36) { buy.changeSkill(r.name, -1) }
            Text("${r.rating}", Modifier.width(20.dp), textAlign = TextAlign.Center)
            StepButton("+", 36) { buy.changeSkill(r.name, 1) }
        } else {
            Text("${r.rating}", Modifier.padding(horizontal = 12.dp))
        }
        Text("${r.pool}", Modifier.width(36.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun Qualities(s: Sheet, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Sheet.QualityLine?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Button(onClick = { adding = true }) {
                Icon(Icons.Default.Add, null)
                Text("  Add quality")
            }
        }
        val positive = s.qualities.filter { it.positive }
        val negative = s.qualities.filter { !it.positive }
        s.karmaLedger?.let { l -> item { Text("Positive ${l.positiveQualities}/${l.qualityLimit} karma    Negative ${l.negativeQualities}/${l.qualityLimit} karma", style = MaterialTheme.typography.bodySmall) } }
        item { SubHeader("Positive") }
        items(positive, key = { it.guid }) { QualityRow(it) { removing = it } }
        item { SubHeader("Negative") }
        items(negative, key = { it.guid }) { QualityRow(it) { removing = it } }
    }
    if (adding) AddQualityDialog(vm, onDismiss = { adding = false })
    removing?.let { q ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${q.name}?") },
            text = { Text("The improvements it gave this character are removed too. A copy of the file is kept in the app's backups folder.") },
            confirmButton = { TextButton(onClick = { removing = null; vm.removeQuality(q.guid) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QualityRow(q: Sheet.QualityLine, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (q.extra.isBlank()) q.name else "${q.name} (${q.extra})", Modifier.weight(1f))
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove ${q.name}") }
    }
}

/** Pick a quality from the game data. Prerequisites and karma limits are not checked yet. */
@Composable
private fun AddQualityDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember { vm.qualityOptions() }
    var query by remember { mutableStateOf("") }
    var needsText by remember { mutableStateOf<QualityOption?>(null) }
    var text by remember { mutableStateOf("") }

    val shown = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add quality") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Prerequisites and karma limits are not checked yet.",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp),
                )
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shown, key = { it.id }) { o ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                if (o.needsText) { needsText = o; text = "" } else { vm.addQuality(o.id); onDismiss() }
                            }.padding(vertical = 6.dp),
                        ) {
                            Text(o.name)
                            Text(
                                listOfNotNull(o.category, "${o.karma} karma", if (o.unsupported.isNotEmpty()) "some bonuses not applied yet: " + o.unsupported.joinToString() else null).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    needsText?.let { o ->
        AlertDialog(
            onDismissRequest = { needsText = null },
            title = { Text(o.name) },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Enter text (a name, a group...)") }, singleLine = true) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.addQuality(o.id, text.trim()); needsText = null; onDismiss() }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { needsText = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Row2(left: String, right: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null)
        if (right.isNotBlank()) Text(right, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Small(text: String) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Gear(g: chummer.core.engine.SheetSections) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (g.armorSets.isNotEmpty()) item { SubHeader("Armor") }
        items(g.armorSets) { set ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(set.name.ifBlank { "Not in a set" }, style = MaterialTheme.typography.titleSmall)
                    set.items.forEach { a ->
                        Row2(a.name, a.value.let { v -> if (a.equipped) v else "$v (off)" })
                        Small(a.mods.joinToString(", "))
                    }
                }
            }
        }
        if (g.weapons.isNotEmpty()) {
            item { SubHeader("Weapons") }
        }
        items(g.weapons) { w ->
            Column {
                Row2(w.name, w.damage + if (w.ap.isNotBlank() && w.ap != "-") "  AP ${w.ap}" else "", bold = true)
                Small(listOf(w.category, w.mode.takeIf { it.isNotBlank() && it != "0" }, w.ammo.takeIf { it.isNotBlank() && it != "0" }?.let { "ammo $it" },
                    w.reach.takeIf { w.type.equals("Melee", true) && it.isNotBlank() }?.let { "reach $it" }, w.accuracy.takeIf { it.isNotBlank() }?.let { "acc $it" },
                    "RC ${w.rc}", w.conceal.takeIf { it != "0" }?.let { "conceal $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "))
                Small(w.accessories.groupingBy { it }.eachCount().entries.joinToString(", ") { (n, c) -> if (c > 1) "$n x$c" else n })
            }
        }
        if (g.vehicles.isNotEmpty()) item { SubHeader("Vehicles") }
        items(g.vehicles) { v -> Column { Row2(v.name, v.category, bold = true); Small(v.stats) } }
        if (g.gear.isNotEmpty()) item { SubHeader("Gear") }
        items(g.gear) { it ->
            Column {
                Row2(it.name + if (it.rating > 0) " (rating ${it.rating})" else "", if (it.quantity != "1") "x${it.quantity}" else "")
                Small(listOf(it.category, it.children.joinToString(", ")).filter { x -> x.isNotBlank() }.joinToString("  ·  "))
            }
        }
    }
}

@Composable
private fun Ware(g: chummer.core.engine.SheetSections, essence: Double) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Row2("Essence", formatEssence(essence), bold = true) }
        val (bio, cyber) = g.ware.partition { it.isBioware }
        if (cyber.isNotEmpty()) item { SubHeader("Cyberware") }
        items(cyber) { WareRow(it, 0) }
        if (bio.isNotEmpty()) item { SubHeader("Bioware") }
        items(bio) { WareRow(it, 0) }
        if (g.ware.isEmpty()) item { Text("No augmentations.") }
    }
}

@Composable
private fun WareRow(w: chummer.core.engine.SheetSections.WareLine, depth: Int) {
    Column(Modifier.padding(start = (depth * 16).dp)) {
        Row2(w.name + if (w.rating > 0) " (rating ${w.rating})" else "", if (w.essence != 0.0) formatEssence(w.essence) else "", bold = depth == 0)
        Small(w.grade.takeIf { !it.equals("Standard", true) }.orEmpty())
        w.children.forEach { WareRow(it, depth + 1) }
    }
}

@Composable
private fun Life(g: chummer.core.engine.SheetSections) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (g.contacts.isNotEmpty()) item { SubHeader("Contacts   (connection / loyalty)") }
        items(g.contacts) { c ->
            Column {
                Row2(c.name, "${c.connection} / ${c.loyalty}", bold = true)
                Small(listOf(c.type, c.notes).filter { it.isNotBlank() }.joinToString("  ·  "))
            }
        }
        if (g.lifestyles.isNotEmpty()) item { SubHeader("Lifestyle") }
        items(g.lifestyles) { l ->
            Column { Row2(l.name, "${l.type}  ¥${l.monthly}/mo"); Small(l.notes) }
        }
        if (g.martialArts.isNotEmpty()) item { SubHeader("Martial arts") }
        items(g.martialArts) { Text(it) }
    }
}

/** Where the karma of a character in creation has gone. */
@Composable
private fun KarmaSection(l: chummer.core.engine.KarmaLedger) {
    Section("Karma", collapsible = true, startExpanded = false, summary = "${l.remaining} of ${l.budget}") {
        Row2("Metatype", (-l.metatype).toString())
        Row2("Attributes", (-l.attributes).toString())
        Row2("Skills", (-l.skills).toString())
        Row2("Qualities", (-l.qualities).toString())
        Row2("  positive / negative", "${l.positiveQualities}/${l.qualityLimit}  ${l.negativeQualities}/${l.qualityLimit}")
        if (l.contacts != 0) Row2("Contacts", (-l.contacts).toString())
        if (l.notCounted.isNotEmpty()) {
            Text(
                "Not counted yet: ${l.notCounted.joinToString()}. The remaining karma is higher than Chummer would show.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The attributes that can be bought with karma in creation. */
private val BUYABLE = setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG")

/** A round-ish text button for buying (+) or giving back (-) a point. */
@Composable
private fun StepButton(label: String, size: Int = 40, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) { Text(label, style = MaterialTheme.typography.titleLarge) }
}
