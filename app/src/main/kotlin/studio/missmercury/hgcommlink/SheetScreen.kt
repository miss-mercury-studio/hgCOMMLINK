package studio.missmercury.hgcommlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
                Header(sheet)
                if (open.warning != null) {
                    Text(
                        open.warning,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> Overview(sheet)
                    1 -> Skills(sheet)
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
private fun Header(s: Sheet) {
    val line = listOfNotNull(
        s.metatype.takeIf { it.isNotBlank() }, if (s.isCareer) "Career" else "Creation",
        s.karmaLedger?.let { "Karma left ${it.remaining}" } ?: s.karma.takeIf { it.isNotBlank() }?.let { "Karma $it" },
        s.nuyen.takeIf { it.isNotBlank() }?.let { "¥$it" },
    ).joinToString("  ·  ")
    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun Overview(s: Sheet) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        s.karmaLedger?.let { KarmaSection(it) }
        Section("Attributes") {
            // Four to a row reads well on a phone held upright.
            s.attributes.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a -> Stat(a.name, a.total.toString(), Modifier.weight(1f)) }
                    repeat(4 - row.size) { Column(Modifier.weight(1f)) {} }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("ESS", formatEssence(s.essence), Modifier.weight(1f))
                Stat("Initiative", "${s.initiativeValue} + ${s.initiativeDice}d6", Modifier.weight(2f))
                Column(Modifier.weight(1f)) {}
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
        Section("Condition") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Physical", s.physicalCM.toString(), Modifier.weight(1f))
                Stat("Stun", s.stunCM.toString(), Modifier.weight(1f))
                Stat("Overflow", s.cmOverflow.toString(), Modifier.weight(1f))
            }
        }
        Section("Armor") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Armor", s.armor.general.toString(), Modifier.weight(1f))
                Stat("Soak", s.soak.toString(), Modifier.weight(1f))
                Stat("Encumbrance", if (s.armor.encumbrance == 0) "none" else s.armor.encumbrance.toString(), Modifier.weight(1f))
            }
            val special = s.armor.byType.filter { it.second != s.armor.general }
            if (special.isNotEmpty()) {
                Text(
                    special.joinToString("   ") { "${it.first} ${it.second}" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Section("Dice pools") {
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

private fun formatEssence(e: Double) = String.format(java.util.Locale.ROOT, "%.2f", e)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
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
private fun Skills(s: Sheet) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Skills the character has come first; the rest are listed after, as Chummer does.
        val (rated, unrated) = s.activeSkills.partition { it.rating > 0 }
        item { SubHeader("Active skills") }
        items(rated, key = { "a-" + it.name }) { SkillRow(it) }
        if (unrated.isNotEmpty()) item { SubHeader("Unrated") }
        items(unrated, key = { "u-" + it.name }) { SkillRow(it) }
        if (s.knowledgeSkills.isNotEmpty()) {
            item { SubHeader("Knowledge and languages") }
            items(s.knowledgeSkills.sortedByDescending { it.rating }, key = { "k-" + it.name }) { SkillRow(it) }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SkillRow(r: SkillResult) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.name)
            Text(
                listOf(r.attribute, if (r.rating == 0 && r.canDefault) "default" else if (r.rating == 0) "cannot default" else "").filter { it.isNotBlank() }.joinToString("  "),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text("${r.rating}", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Text("${r.pool}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
    Section("Karma") {
        Row2("Budget", l.budget.toString())
        Row2("Metatype", (-l.metatype).toString())
        Row2("Attributes", (-l.attributes).toString())
        Row2("Skills", (-l.skills).toString())
        Row2("Qualities", (-l.qualities).toString())
        if (l.contacts != 0) Row2("Contacts", (-l.contacts).toString())
        HorizontalDivider()
        Row2("Remaining", l.remaining.toString(), bold = true)
        if (l.notCounted.isNotEmpty()) {
            Text(
                "Not counted yet: ${l.notCounted.joinToString()}. The remaining karma is higher than Chummer would show.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
