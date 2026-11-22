package studio.missmercury.hgcommlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import chummer.core.engine.QualityOption
import studio.missmercury.hgcommlink.ui.*
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import chummer.core.engine.Sheet
import chummer.core.engine.SkillResult


/** The character sheet: calculated numbers on three tabs. Read-only. */
@Composable
fun SheetScreen(open: OpenCharacter, vm: AppViewModel) {
    val sheet = open.sheet
    // The tab is remembered by name: tabs can appear (Magic) and the numbers would then point at the wrong one.
    var tabName by rememberSaveable { mutableStateOf("Overview") }
    Column(Modifier.fillMaxSize()) {
        when {
            sheet == null && open.sheetError == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TerminalLine("JACKING_IN // PREPARING_GAME_DATA")
                Text("This takes several seconds the first time, and again whenever custom data changes.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
            }
            sheet == null -> Text(
                "Could not calculate this character: ${open.sheetError}",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error,
            )
            else -> {
                Header(sheet, open.warning)
                // The Magic tab appears once the character has magic switched on (for example with the Magician quality).
                val tabs = buildList {
                    add("Overview" to "STATS"); add("Skills" to "SKILL")
                    if (sheet.magic != null || sheet.powers != null) add("Magic" to "MAGIC")
                    if (sheet.complexForms != null) add("Resonance" to "MATRIX")
                    add("Gear" to "GEAR"); add("Ware" to "WARE"); add("Life" to "LIFE"); add("Qualities" to "QUAL")
                }
                val current = if (tabs.any { it.first == tabName }) tabName else "Overview"
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (current) {
                        "Overview" -> Overview(sheet, vm)
                        "Skills" -> Skills(sheet, vm)
                        "Magic" -> Magic(sheet, vm)
                        "Resonance" -> Resonance(sheet, vm)
                        "Gear" -> Gear(sheet.sections, sheet, vm)
                        "Ware" -> Ware(sheet.sections, sheet.essence, sheet, vm)
                        "Life" -> Life(sheet.sections, sheet, vm)
                        else -> Qualities(sheet, vm)
                    }
                    // Soft edges where the scrolling content passes under the header and the tab bar, instead of a hard cut.
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Obsidian, Color.Transparent))))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Obsidian))))
                }
                BottomNav(tabs, current) { tabName = it }
            }
        }
    }
}

/** A small "remove" link under an item bought in creation (career uses undo from the log). */
@Composable
private fun RemoveLink(onClick: () -> Unit) {
    Text("× remove", Modifier.clickable(onClick = onClick).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = CriticalRed.copy(alpha = 0.85f))
}

/** Section tabs along the bottom edge, where the thumb rests; the chosen one in inverse video. */
@Composable
private fun BottomNav(tabs: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MercurySlate).border(1.dp, TerminalGrid)) {
        tabs.forEach { (key, label) ->
            val on = key == current
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).background(if (on) PhosphorGreen else Color.Transparent).clickable { onPick(key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (on) Obsidian else MutedSilver)
            }
        }
    }
}

/** A button pinned under a scrolling list, within thumb reach, instead of the first row of the list. */
@Composable
private fun PinnedAction(label: String, onClick: () -> Unit) {
    TerminalButton(label, onClick, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun Header(s: Sheet, warning: String?) {
    var showWarning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val l = s.karmaLedger
            TelemetryStrip(
                listOfNotNull(
                    "" to (s.metatype.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty() + if (s.isCareer) "CAREER" else "CREATION"),
                    s.nuyen.takeIf { it.isNotBlank() }?.let { "¥" to it },
                    if (l != null) "KARMA" to "${l.remaining}" else s.karma.takeIf { it.isNotBlank() }?.let { "KARMA" to it },
                ),
                accent = if (l != null && l.remaining < 0) CriticalRed else PhosphorGreen,
            )
        }
        s.review?.problems?.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CareerCard(s, vm)
        s.points?.let { PointsSection(it) }
        s.karmaLedger?.let { KarmaSection(it, s.review) }
        s.creationBuy?.let { NuyenCard(it.ledger, vm) }
        if (s.review != null) FinishCard(s.review!!, vm)

        // The numbers needed in play, together: initiative, condition, armor.
        TerminalCard("Vitals", tag = "01") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Initiative", "${s.initiativeValue} + ${s.initiativeDice}d6", Modifier.weight(1.4f))
                    Stat("Physical", s.physicalCM.toString(), Modifier.weight(1f), DrainAmber)
                    Stat("Stun", s.stunCM.toString(), Modifier.weight(1f), DrainAmber)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Armor", s.armor.general.toString(), Modifier.weight(1.4f))
                    Stat("Soak", s.soak.toString(), Modifier.weight(1f))
                    Stat("Essence", formatEssence(s.essence), Modifier.weight(1f), if (s.essence < 6.0) DrainAmber else Quicksilver)
                }
                val extras = buildList {
                    if (s.movement.isNotEmpty()) add("Move ${s.movement}")
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
            } else if (s.career != null) {
                // Career: two to a row, each with the karma price of its next point.
                val karma = s.karma.toIntOrNull() ?: 0
                s.attributes.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { a ->
                            val cost = if (a.name in BUYABLE) s.career!!.attributeCosts[a.name] else null
                            Row(Modifier.weight(1f).background(SlateHigh).border(1.dp, TerminalGrid), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(a.total.toString(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono), color = Quicksilver)
                                    Text(a.name + if (a.maximum > 0) " /${a.maximum}" else "", style = MaterialTheme.typography.labelSmall, color = MutedSilver, maxLines = 1)
                                }
                                if (a.name in BUYABLE) UpgradeButton(cost, karma) { vm.improveAttribute(a.name) }
                            }
                        }
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
            // Modifiers that only apply under a condition: listed, and not part of the numbers above.
            listOf("Physical", "Mental", "Social").forEach { limit ->
                val notes = s.limitNotes.filter { it.limit == limit }
                if (notes.isNotEmpty()) {
                    Text(limit.uppercase(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                    notes.forEach { Text(it.fullName, style = MaterialTheme.typography.bodySmall) }
                }
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

/** One attribute in creation: a cell with the value large between a minus and a plus, the name and its maximum under it. */
@Composable
private fun AttributeTile(a: Sheet.AttributeLine, buyable: Boolean, vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(modifier.background(SlateHigh).border(1.dp, TerminalGrid), verticalAlignment = Alignment.CenterVertically) {
        if (buyable) StepButton("−", 48) { vm.changeAttribute(a.name, -1) } else Spacer(Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                a.total.toString(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono),
                color = if (buyable) PhosphorGreen else Quicksilver,
            )
            Text(
                a.name + if (a.maximum > 0) " /${a.maximum}" else "",
                style = MaterialTheme.typography.labelSmall, color = MutedSilver, maxLines = 1,
            )
        }
        if (buyable) StepButton("+", 48) { vm.changeAttribute(a.name, 1) } else Spacer(Modifier.size(48.dp))
    }
}


private fun formatEssence(e: Double) = String.format(java.util.Locale.ROOT, "%.2f", e)

@Composable
private fun Section(
    title: String, collapsible: Boolean = false, startExpanded: Boolean = true, summary: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    TerminalCard(
        title, trailing = summary,
        collapsed = if (collapsible) !expanded else null,
        onToggle = if (collapsible) ({ expanded = !expanded }) else null,
    ) { content() }
}

/** A labelled number in a cell. */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, accent: androidx.compose.ui.graphics.Color = Quicksilver) =
    StatCell(label, value, modifier, accent)

@Composable
private fun Skills(s: Sheet, vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var addingKnowledge by remember { mutableStateOf(false) }
    var groupsOpen by rememberSaveable { mutableStateOf(false) }
    val q = query.trim()
    fun matches(r: SkillResult) = q.isEmpty() || r.name.contains(q, ignoreCase = true)
    val buy = if (s.karmaLedger != null) vm else null
    val career = s.career
    val karma = s.karma.toIntOrNull() ?: 0
    Column(Modifier.fillMaxSize()) {
        TerminalField(query, { query = it }, "search skills", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            // Skill groups: folded away until wanted, since there are eleven of them.
            if ((buy != null || career != null) && q.isEmpty() && s.skillGroups.isNotEmpty()) {
                item {
                    val bought = s.skillGroups.count { it.karma > 0 }
                    CollapsibleHeader("Skill groups", groupsOpen, { groupsOpen = !groupsOpen }, summary = if (bought > 0) "$bought bought" else null)
                }
                if (groupsOpen) items(s.skillGroups, key = { "g-" + it.name }) { g ->
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name, Modifier.weight(1f), color = if (g.karma > 0) Quicksilver else MutedSilver)
                            if (career != null) {
                                Text("${g.karma}", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
                                UpgradeButton(career.groupCosts[g.name], karma) { vm.improveGroup(g.name) }
                            } else {
                                StepButton("−", 44) { vm.changeSkillGroup(g.name, -1) }
                                Text("${g.karma}", Modifier.width(24.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                StepButton("+", 44) { vm.changeSkillGroup(g.name, 1) }
                            }
                        }
                        Hairline(color = TerminalGrid.copy(alpha = 0.6f))
                    }
                }
            }
            // Knowledge skills and languages come before the long list of active skills, in one panel.
            val knowledge = s.knowledge.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            if (knowledge.isNotEmpty() || (buy != null && q.isEmpty())) item(key = "knowledge") {
                val l = s.karmaLedger
                TerminalCard(
                    "Knowledge", Modifier.padding(top = 8.dp, bottom = 4.dp),
                    trailing = l?.let { "FREE ${it.knowledgePointsLeft}/${it.knowledgePoints}" },
                    accent = if (l != null && l.knowledgePointsLeft < 0) CriticalRed else PhosphorGreen,
                ) {
                    if (knowledge.isEmpty()) Text("None yet. Add one with the button below.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
                    // Languages first, then the other kinds, each under its own small label.
                    knowledge.groupBy { it.type.ifBlank { "Other" } }.toList()
                        .sortedWith(compareBy({ it.first != "Language" }, { it.first })).forEach { (type, list) ->
                            Text(type.uppercase(), style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                            list.forEach { k -> KnowledgeRow(k, buy, career?.skillCosts?.get(k.guid), career != null, karma) { vm.improveSkill(k.guid) } }
                        }
                }
            }
            // Skills the character has come first; the rest are listed after, as Chummer does.
            val (rated, unrated) = s.activeSkills.filter(::matches).partition { it.rating > 0 }
            if (rated.isNotEmpty()) item { SubHeader("Active skills") }
            items(rated, key = { "a-" + it.name }) { SkillRow(it, buy, career, karma, vm) }
            if (unrated.isNotEmpty()) item { SubHeader(if (rated.isEmpty()) "Active skills" else "Unrated") }
            items(unrated, key = { "u-" + it.name }) { SkillRow(it, buy, career, karma, vm) }
        }
        if (buy != null) PinnedAction("+ Knowledge skill") { addingKnowledge = true }
    }
    if (addingKnowledge) AddKnowledgeSheet(vm) { addingKnowledge = false }
}

/** The dice pool in a small box at the end of a row: lit when there is a rating behind it. */
@Composable
private fun PoolBox(text: String, lit: Boolean) {
    Box(
        Modifier.padding(start = 6.dp).size(width = 48.dp, height = 38.dp).background(SlateHigh)
            .border(1.dp, if (lit) PhosphorGreen.copy(alpha = 0.55f) else TerminalGrid),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono, shadow = if (lit) androidx.compose.ui.graphics.Shadow(PhosphorGreen.copy(alpha = 0.4f), blurRadius = 10f) else null),
            color = if (lit) PhosphorGreen else MutedSilver,
        )
    }
}

/** A rating as a row of small squares, filled up to the rating. */
@Composable
private fun Pips(rating: Int, max: Int) {
    val n = maxOf(rating, minOf(max, 12)).coerceAtLeast(1)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(n) { i -> Box(Modifier.size(6.dp).background(if (i < rating) PhosphorGreen else TerminalGrid)) }
    }
}

@Composable
private fun KnowledgeRow(k: Sheet.KnowledgeLine, buy: AppViewModel?, upgradeCost: Int? = null, inCareer: Boolean = false, karma: Int = 0, onUpgrade: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (buy != null) StepButton("×", 36, CriticalRed.copy(alpha = 0.85f)) { buy.removeKnowledge(k.guid) }
        Column(Modifier.weight(1f)) {
            Text(k.name)
            if (k.native) Tag("native", PhosphorGreen)
            else Text("${k.points} pts · ${k.karma} karma", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
        }
        if (buy != null && !k.native) {
            StepButton("−", 44) { buy.changeKnowledge(k.guid, -1) }
            Text("${k.points + k.karma}", Modifier.width(22.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            StepButton("+", 44) { buy.changeKnowledge(k.guid, 1) }
        } else if (!k.native) {
            Text("${k.points + k.karma}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
            if (inCareer) UpgradeButton(upgradeCost, karma, onUpgrade)
        }
        PoolBox(if (k.native) "N" else "${k.pool}", lit = true)
    }
}

/** Pick a knowledge skill or language from the game data (typing your own name comes later). */
@Composable
private fun AddKnowledgeSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val items = remember { vm.knowledgeOptions().map { PickItem(it.id, it.name, it.category, it.category) } }
    var native by remember { mutableStateOf(false) }
    PickerSheet(
        "Add knowledge skill", items, onPick = { id -> vm.addKnowledge(id, native && items.first { it.id == id }.group == "Language") },
        header = {
            Row(
                Modifier.fillMaxWidth().clickable { native = !native }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (native) "[■]" else "[ ]", style = MaterialTheme.typography.labelLarge, color = PhosphorGreen)
                Text("  NATIVE LANGUAGE", style = MaterialTheme.typography.labelMedium, color = if (native) PhosphorGreen else Quicksilver)
                Text("  free, for a language", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SubHeader(text: String) {
    Text("// ${text.uppercase().replace(' ', '_')}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SkillRow(r: SkillResult, buy: AppViewModel?, career: Sheet.CareerLines? = null, karma: Int = 0, vm: AppViewModel? = null) {
    val unrated = r.rating == 0
    var speccing by remember { mutableStateOf(false) }
    val guid = career?.skillIds?.get(r.name)
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, color = if (unrated) MutedSilver else Quicksilver, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.attribute, style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                    if (!unrated) Pips(r.rating, r.ratingMaximum)
                    else Text(if (r.canDefault) "default" else "no default", style = MaterialTheme.typography.labelSmall, color = if (r.canDefault) MutedSilver else DrainAmber)
                    val specCost = guid?.let { career?.specCosts?.get(it) }
                    if (specCost != null) Text("+spec $specCost", Modifier.clickable { speccing = true }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = if (specCost <= karma) PhosphorGreen else MutedSilver)
                }
            }
            if (buy != null) {
                StepButton("−", 44) { buy.changeSkill(r.name, -1) }
                Text("${r.rating}", Modifier.width(22.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                StepButton("+", 44) { buy.changeSkill(r.name, 1) }
            } else if (career != null && vm != null) {
                if (guid != null) UpgradeButton(career.skillCosts[guid], karma) { vm.improveSkill(guid) }
            }
            PoolBox(if (unrated && !r.canDefault) "–" else "${r.pool}", lit = !unrated)
        }
        Hairline(color = TerminalGrid.copy(alpha = 0.6f))
    }
    if (speccing && guid != null && career != null && vm != null) {
        SpecializationSheet(r.name, career.specCosts[guid] ?: 0, career.specChoices[guid].orEmpty(), karma, { vm.addSpecialization(guid, it) }, { speccing = false })
    }
}


@Composable
private fun Qualities(s: Sheet, vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Sheet.QualityLine?>(null) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val positive = s.qualities.filter { it.positive }
        val negative = s.qualities.filter { !it.positive }
        s.karmaLedger?.let { l -> item { Text("Positive ${l.positiveQualities}/${l.qualityLimit} karma    Negative ${l.negativeQualities}/${l.qualityLimit} karma", style = MaterialTheme.typography.bodySmall) } }
        item { SubHeader("Positive") }
        items(positive, key = { it.guid }) { QualityRow(it) { removing = it } }
        item { SubHeader("Negative") }
        items(negative, key = { it.guid }) { QualityRow(it) { removing = it } }
    }
    PinnedAction("+ Add quality") { adding = true }
    }
    if (adding) AddQualityDialog(vm, onDismiss = { adding = false })
    removing?.let { q ->
        ConfirmSheet(
            "Remove ${q.name}?", "The improvements it gave this character are removed too." + (s.career?.qualityRemoval?.get(q.guid)?.takeIf { it > 0 }?.let { " Buying it off costs $it karma." } ?: "") + " A copy of the file is kept in the app's backups folder.",
            "Remove", onConfirm = { vm.removeQuality(q.guid) }, onDismiss = { removing = null }, danger = true,
        )
    }
}

@Composable
private fun QualityRow(q: Sheet.QualityLine, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (q.extra.isBlank()) q.name else "${q.name} (${q.extra})", Modifier.weight(1f).padding(vertical = 8.dp))
        StepButton("×", 44, CriticalRed.copy(alpha = 0.85f), onRemove)
    }
}

/** Pick a quality from the game data. Prerequisites and karma limits are not checked yet. */
@Composable
private fun AddQualityDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = remember { vm.qualityOptions() }
    val items = remember {
        options.map { o ->
            val careerCost = vm.careerQualityCost(o.id)
            PickItem(
                o.id, o.name,
                listOfNotNull(o.category, if (careerCost != null) "costs $careerCost karma" else "${o.karma} karma", if (o.unsupported.isNotEmpty()) "some bonuses not applied yet: " + o.unsupported.joinToString() else null).joinToString("  ·  "),
                o.category, warn = o.unsupported.isNotEmpty(),
            )
        }
    }
    var needsText by remember { mutableStateOf<QualityOption?>(null) }
    var text by remember { mutableStateOf("") }
    PickerSheet(
        "Add quality", items,
        onPick = { id ->
            val o = options.first { it.id == id }
            if (o.needsText) { needsText = o; text = "" } else vm.addQuality(id)
        },
        note = if (vm.open?.sheet?.isCareer == true) "Costs karma now. Prerequisites are not checked yet." else "Prerequisites and karma limits are not checked yet.",
        keepOpenFor = { id -> options.first { it.id == id }.needsText },
        onDismiss = onDismiss,
    )
    needsText?.takeIf { it.choices.isNotEmpty() }?.let { o ->
        // The data knows what can be chosen: pick from the list.
        PickerSheet(o.name, o.choices.map { PickItem(it, it, "") }, onPick = { vm.addQuality(o.id, it); needsText = null; onDismiss() }, note = "Choose one.", onDismiss = { needsText = null })
    }
    needsText?.takeIf { it.choices.isEmpty() }?.let { o ->
        TerminalSheet(o.name, onDismiss = { needsText = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This quality asks for a name or a group.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
                TerminalField(text, { text = it }, "name, group...")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Add", { vm.addQuality(o.id, text.trim()); needsText = null; onDismiss() }, Modifier.weight(1f), filled = true, enabled = text.isNotBlank())
                }
            }
        }
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
private fun Gear(g: chummer.core.engine.SheetSections, s: Sheet, vm: AppViewModel) {
    val canBuy = s.career != null || s.creationBuy != null
    val creating = s.creationBuy != null
    var buying by remember { mutableStateOf<String?>(null) }
    var attaching by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<Pair<String, Double?>?>(null) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (g.armorSets.isNotEmpty()) item { SubHeader("Armor") }
        items(g.armorSets) { set ->
            TerminalCard(set.name.ifBlank { "Not in a set" }) {
                Column(Modifier.fillMaxWidth()) {
                    set.items.forEach { a ->
                        Row2(a.name, a.value.let { v -> if (a.equipped) v else "$v (off)" })
                        Small(a.mods.joinToString(", "))
                        if (a.gear.isNotEmpty()) Small("Gear: " + a.gear.joinToString(", "))
                        a.capacityLeft?.let { Small("Capacity left %.0f".format(it)) }
                        if (creating && a.guid.isNotEmpty()) RemoveLink { vm.removePurchase(a.guid) }
                        if (canBuy && a.guid.isNotEmpty()) Text("+ mod", Modifier.clickable { attaching = "mod" to a.guid }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
                        if (canBuy && a.guid.isNotEmpty() && (a.capacityLeft ?: 0.0) > 0.0) Text("+ gear", Modifier.clickable { installing = a.guid to a.capacityLeft }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
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
                if (creating && w.guid.isNotEmpty() && w.removable) RemoveLink { vm.removePurchase(w.guid) }
                if (canBuy && w.guid.isNotEmpty() && w.canLoad) Text("load ammo", Modifier.clickable { loading = w.guid }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
                if (canBuy && w.guid.isNotEmpty() && w.loadedClips > 0) Text("unload ammo", Modifier.clickable { vm.unloadAmmo(w.guid) }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
                if (canBuy && w.guid.isNotEmpty() && w.allowAccessory) Text("+ accessory", Modifier.clickable { attaching = "accessory" to w.guid }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
            }
        }
        if (g.vehicles.isNotEmpty()) item { SubHeader("Vehicles") }
        items(g.vehicles) { v -> Column { Row2(v.name, v.category, bold = true); Small(v.stats) } }
        if (g.gear.isNotEmpty()) item { SubHeader("Gear") }
        items(g.gear) { it ->
            Column {
                Row2(it.name + if (it.rating > 0) " (rating ${it.rating})" else "", if (it.quantity != "1") "x${it.quantity}" else "")
                Small(listOf(it.category, it.children.joinToString(", ")).filter { x -> x.isNotBlank() }.joinToString("  ·  "))
                if (creating && it.guid.isNotEmpty()) RemoveLink { vm.removePurchase(it.guid) }
            }
        }
    }
    if (canBuy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalButton("Gear", { buying = "gear" }, Modifier.weight(1f))
        TerminalButton("Weapon", { buying = "weapon" }, Modifier.weight(1f))
        TerminalButton("Armor", { buying = "armor" }, Modifier.weight(1f))
    }
    }
    buying?.let { BuyGearDialog(it, vm, s.nuyen) { buying = null } }
    attaching?.let { (kind, guid) -> AttachPartDialog(kind, guid, vm, s.nuyen) { attaching = null } }
    installing?.let { (armor, room) -> ArmorGearDialog(armor, room ?: 0.0, vm, s.nuyen) { installing = null } }
    loading?.let { weapon ->
        val ammo = g.gear.filter { it.category == "Ammunition" && !it.loaded && it.guid.isNotEmpty() }
        PickerSheet("Load ammo", ammo.map { PickItem(it.guid, it.name, "x${it.quantity}") }, onPick = { vm.loadAmmo(weapon, it) }, note = if (ammo.isEmpty()) "No loose ammunition." else "Fills the first empty clip.", onDismiss = { loading = null })
    }
}

@Composable
private fun Ware(g: chummer.core.engine.SheetSections, essence: Double, s: Sheet, vm: AppViewModel) {
    val canBuy = s.career != null || s.creationBuy != null
    val pluginRoom = s.career?.pluginCapacity ?: s.creationBuy?.pluginCapacity
    var buying by remember { mutableStateOf<Boolean?>(null) }
    var plugging by remember { mutableStateOf<chummer.core.engine.SheetSections.WareLine?>(null) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Row2("Essence", formatEssence(essence), bold = true) }
        val (bio, cyber) = g.ware.partition { it.isBioware }
        if (cyber.isNotEmpty()) item { SubHeader("Cyberware") }
        items(cyber) { WareRow(it, 0, pluginRoom?.get(it.guid), if (s.creationBuy != null) ({ vm.removePurchase(it.guid) }) else null) { plugging = it } }
        if (bio.isNotEmpty()) item { SubHeader("Bioware") }
        items(bio) { WareRow(it, 0, pluginRoom?.get(it.guid), if (s.creationBuy != null) ({ vm.removePurchase(it.guid) }) else null) { plugging = it } }
        if (g.ware.isEmpty()) item { Text("No augmentations.") }
    }
    if (canBuy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalButton("+ Cyberware", { buying = false }, Modifier.weight(1f))
        TerminalButton("+ Bioware", { buying = true }, Modifier.weight(1f))
    }
    }
    buying?.let { BuyWareDialog(it, vm, s.nuyen, essence) { buying = null } }
    plugging?.let { w -> BuyWareDialog(w.isBioware, vm, s.nuyen, essence, parent = w) { plugging = null } }
}

@Composable
private fun WareRow(w: chummer.core.engine.SheetSections.WareLine, depth: Int, capacityLeft: Double? = null, onRemove: (() -> Unit)? = null, onPlug: () -> Unit = {}) {
    Column(Modifier.padding(start = (depth * 16).dp)) {
        Row2(w.name + if (w.rating > 0) " (rating ${w.rating})" else "", if (w.essence != 0.0) formatEssence(w.essence) else "", bold = depth == 0)
        Small(w.grade.takeIf { !it.equals("Standard", true) }.orEmpty())
        w.children.forEach { WareRow(it, depth + 1) }
        if (onRemove != null && depth == 0) RemoveLink(onRemove)
        if (capacityLeft != null) Text("+ plug-in  (capacity left ${"%.0f".format(java.util.Locale.ROOT, capacityLeft)})", Modifier.clickable(onClick = onPlug).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
    }
}

@Composable
private fun Life(g: chummer.core.engine.SheetSections, s: Sheet, vm: AppViewModel) {
    val canBuy = s.career != null || s.creationBuy != null
    val creating = s.creationBuy != null
    var addingArt by remember { mutableStateOf(false) }
    var addingTechnique by remember { mutableStateOf<chummer.core.engine.SheetSections.MartialArtLine?>(null) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        items(g.martialArts) { art ->
            Column {
                Row2(art.name, "", bold = true)
                art.techniques.forEach { (tg, tn) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("· $tn", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (creating) Text("×", Modifier.clickable { vm.removePurchase(tg) }.padding(horizontal = 12.dp, vertical = 6.dp), color = CriticalRed)
                    }
                }
                if (canBuy) Text("+ technique", Modifier.clickable { addingTechnique = art }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
                if (creating) RemoveLink { vm.removePurchase(art.guid) }
            }
        }
    }
    if (canBuy) PinnedAction("+ Martial art") { addingArt = true }
    }
    if (addingArt) {
        val options = remember { vm.martialArtOptions() }
        val known = g.martialArts.map { it.name }.toSet()
        PickerSheet(
            "Learn martial art",
            options.filter { it.name !in known }.map { o ->
                PickItem(o.id, o.name, listOfNotNull(vm.martialArtCost(o.id)?.let { "$it karma" }, o.source.takeIf { it.isNotBlank() }?.let { it + " " + o.page }, if (o.unsupported.isNotEmpty()) "some effects not applied yet" else null).joinToString("  ·  "), warn = o.unsupported.isNotEmpty())
            },
            onPick = { vm.buyMartialArt(it) }, note = if (creating) "Costs karma; the karma card counts it." else "Costs karma now.", onDismiss = { addingArt = false },
        )
    }
    addingTechnique?.let { art ->
        val options = remember(art.name) { vm.techniqueOptions(art.name) }
        val known = art.techniques.map { it.second }.toSet()
        val price = vm.techniqueCost(art.guid) ?: 0
        PickerSheet(
            "Technique: ${art.name}", options.filter { it.name !in known }.map { PickItem(it.id, it.name, listOfNotNull(it.source.takeIf { s -> s.isNotBlank() }?.let { s -> s + " " + it.page }, if (it.unsupported.isNotEmpty()) "some effects not applied yet" else null).joinToString("  ·  "), warn = it.unsupported.isNotEmpty()) },
            onPick = { vm.buyTechnique(art.guid, it) }, note = if (price == 0) "The first technique is free." else "Costs $price karma.", onDismiss = { addingTechnique = null },
        )
    }
}

/** The free points of a Priority or Sum-to-Ten character: what is left to spend in each pool. */
@Composable
private fun PointsSection(p: chummer.core.engine.PointsLedger) {
    Section("Points", summary = "attributes ${p.attributes.left}  skills ${p.skills.left}") {
        listOf(
            "Attributes" to p.attributes, "Special attributes" to p.special, "Skills" to p.skills, "Skill groups" to p.groups,
        ).forEach { (label, pool) ->
            if (pool.total > 0 || pool.used > 0) Row2(label, "${pool.left} left of ${pool.total}")
        }
        Text("Raising a rating spends a free point while there is one, then karma.", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
    }
}

/** Where the karma of a character in creation has gone. */
@Composable
private fun KarmaSection(l: chummer.core.engine.KarmaLedger, review: chummer.core.engine.CreationReview?) {
    Section("Karma", collapsible = true, startExpanded = false, summary = "${l.remaining} of ${l.budget}" + if (review?.warnings?.isNotEmpty() == true) " ⚠" else "") {
        Row2("Metatype", (-l.metatype).toString())
        Row2("Attributes", (-l.attributes).toString())
        Row2("Skills", (-l.skills).toString())
        Row2("Qualities", (-l.qualities).toString())
        if (l.nuyen != 0) Row2("Nuyen", (-l.nuyen).toString())
        Row2("  positive / negative", "${l.positiveQualities}/${l.qualityLimit}  ${l.negativeQualities}/${l.qualityLimit}")
        if (l.contacts != 0) Row2("Contacts", (-l.contacts).toString())
        review?.warnings?.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        if (review != null) {
            Text(
                if (review.canFinish) "Nothing stops finishing, as far as the checks ported so far go." else "Not ready to finish.",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Chummer's other creation checks are not ported yet: ${review.notChecked.joinToString()}.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The attributes that can be bought with karma in creation. */
private val BUYABLE = setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG", "MAG")

/** Tradition and spells of a character with magic. */
@Composable
private fun Magic(s: Sheet, vm: AppViewModel) {
    val creation = s.karmaLedger != null
    // An adept has powers and no tradition; a mystic adept has both.
    val m = s.magic?.takeIf { s.isMagician || s.powers == null } ?: chummer.core.engine.Sheet.MagicLines("", emptyList())
    val showMagician = s.isMagician || s.powers == null
    var choosingTradition by remember { mutableStateOf(false) }
    var addingSpell by remember { mutableStateOf(false) }
    var addingPower by remember { mutableStateOf(false) }
    var initiating by remember { mutableStateOf(false) }
    var pickingMeta by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.powers?.let { pp -> adeptPowerItems(pp, vm) { addingPower = true } }
        s.initiation?.let { initiationItems(it, vm, { initiating = true }, { pickingMeta = true }) }
        if (showMagician) item {
            Section("Tradition") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.tradition.ifEmpty { "None chosen yet" }, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    if (creation) TextButton(onClick = { choosingTradition = true }) { Text(if (m.tradition.isEmpty()) "Choose" else "Change") }
                }
            }
        }
        if (showMagician) item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("// SPELLS", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            }
        }
        if (showMagician && m.spells.isEmpty()) item { Text("No spells.", style = MaterialTheme.typography.bodySmall) }
        items(m.spells, key = { it.guid }) { sp ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sp.name, fontWeight = FontWeight.Bold)
                    Small(listOf(sp.category, "type ${sp.type}", sp.range, "duration ${sp.duration}", "drain ${sp.dv}").filter { it.isNotBlank() }.joinToString("  ·  "))
                    Small(sp.descriptors)
                }
                if (creation) StepButton("×", 36) { vm.removeSpell(sp.guid) }
            }
        }
    }
    if (creation && showMagician) PinnedAction("+ Add spell") { addingSpell = true }
    }
    if (addingPower) AddPowerDialog(vm) { addingPower = false }
    s.initiation?.let { sec ->
        if (initiating) InitiateSheet(sec, vm) { initiating = false }
        if (pickingMeta) MetamagicPicker(sec, vm) { pickingMeta = false }
    }
    if (choosingTradition) {
        val options = remember { vm.traditionOptions().map { PickItem(it.id, it.name, "Drain ${it.drain}") } }
        PickerSheet("Choose a tradition", options, { vm.setTradition(it) }) { choosingTradition = false }
    }
    if (addingSpell) {
        val options = remember { vm.spellOptions().map { PickItem(it.id, it.name, listOf(it.category, "type ${it.type}", it.range, "drain ${it.dv}", vm.careerSpellCost(it.id)?.let { c -> "costs $c karma" }.orEmpty()).filter { x -> x.isNotBlank() }.joinToString("  ·  ")) } }
        PickerSheet("Add a spell", options, { vm.addSpell(it) }) { addingSpell = false }
    }
}

class PickItem(val id: String, val title: String, val subtitle: String, val group: String = "", val warn: Boolean = false)

/**
 * A searchable list in a tall bottom sheet. Category filters on top (when the items have groups), the list in the middle,
 * the search prompt at the bottom where the thumb is. Picking closes the sheet unless [keepOpenFor] says otherwise.
 */
@Composable
internal fun PickerSheet(
    title: String, items: List<PickItem>, onPick: (String) -> Unit,
    note: String? = null, header: (@Composable ColumnScope.() -> Unit)? = null, keepOpenFor: (String) -> Boolean = { false },
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    val groups = remember(items) { listOf("All") + items.map { it.group }.filter { it.isNotBlank() }.distinct() }
    val shown = remember(query, group, items) {
        val q = query.trim().lowercase()
        items.filter { (group == "All" || it.group == group) && (q.isEmpty() || it.title.lowercase().contains(q)) }
    }
    TerminalSheet(title, onDismiss, tall = true) { close ->
        header?.invoke(this)
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MutedSilver, modifier = Modifier.padding(vertical = 6.dp)) }
        if (groups.size > 2) ChipRow(groups, group, { group = it }, Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { it ->
                Column(Modifier.fillMaxWidth().clickable { onPick(it.id); if (!keepOpenFor(it.id)) close() }) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                        Text(it.title)
                        Text(it.subtitle, style = MaterialTheme.typography.labelSmall, color = if (it.warn) DrainAmber else MutedSilver)
                    }
                    Hairline(color = TerminalGrid.copy(alpha = 0.6f))
                }
            }
            if (shown.isEmpty()) item { Text("No match.", Modifier.padding(vertical = 16.dp), color = MutedSilver) }
        }
        TerminalField(query, { query = it }, "search (${shown.size})", Modifier.padding(top = 8.dp).imePadding())
    }
}

/** The step that ends creation: shows what carries over, asks for the starting nuyen roll, and confirms. */
@Composable
private fun FinishCard(review: chummer.core.engine.CreationReview, vm: AppViewModel) {
    var asking by remember { mutableStateOf(false) }
    var roll by remember { mutableStateOf("") }
    TerminalButton(
        if (review.canFinish) "Finish creation" else "Cannot finish yet", { asking = true },
        Modifier.fillMaxWidth(), filled = review.canFinish, enabled = review.canFinish,
    )
    if (asking) {
        val plan = vm.finishPlan()
        val dice = roll.toIntOrNull()
        TerminalSheet("Finish creation", onDismiss = { asking = false }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (plan != null) {
                    TelemetryStrip(listOf("KARMA KEPT" to "${plan.karmaKept}", "LOST" to "${plan.karmaLost}"), accent = if (plan.karmaLost > 0) DrainAmber else PhosphorGreen)
                    if (plan.nuyenLost > 0) Text("Nuyen lost beyond the carry-over: ${plan.nuyenLost}", color = MaterialTheme.colorScheme.error)
                    if (plan.addsStreetLifestyle) Text("A Street lifestyle is added. Roll 1D6 for the starting nuyen (× 20 ¥).", style = MaterialTheme.typography.bodySmall)
                }
                review.warnings.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = DrainAmber) }
                TerminalField(roll, { roll = it.filter(Char::isDigit).take(1) }, "die roll (1-6)", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                Text("This cannot be undone from the app; a backup of the creation file is kept.", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Finish", { close(); vm.finishCreation(dice!! * 20) }, Modifier.weight(1f), filled = true, enabled = dice != null && dice in 1..6)
                }
            }
        }
    }
}

/** A technomancer's complex forms: how many are free, how many fit, and what one more costs. */
@Composable
private fun Resonance(s: Sheet, vm: AppViewModel) {
    val section = s.complexForms ?: return
    val career = s.isCareer
    var adding by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var addingSprite by remember { mutableStateOf(false) }
    var initiating by remember { mutableStateOf(false) }
    var pickingMeta by remember { mutableStateOf(false) }
    val full = section.forms.size >= section.limit
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("// COMPLEX FORMS", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Small(
                    "${section.forms.size} of ${section.limit} known" + if (!career) "  ·  ${section.free} free, then ${section.price} karma each" else "  ·  ${section.price} karma each",
                )
            }
            if (section.forms.isEmpty()) item { Text("No complex forms.", style = MaterialTheme.typography.bodySmall) }
            items(section.forms, key = { it.guid }) { f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name + if (f.extra.isNotBlank()) " (${f.extra})" else "", fontWeight = FontWeight.Bold)
                        Small(listOf("target ${f.target}", "duration ${f.duration}", "fade ${f.fade}").joinToString("  ·  "))
                    }
                    if (!career) StepButton("×", 36) { vm.removeComplexForm(f.guid) }
                }
            }
            item {
                Text("// SPRITES", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Small("${section.sprites.count { it.bound }} of ${section.spriteLimit} registered  ·  level up to ${section.spriteMax}")
            }
            if (section.sprites.isEmpty()) item { Text("No sprites.", style = MaterialTheme.typography.bodySmall) }
            items(section.sprites, key = { it.guid }) { sp ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sp.name + if (sp.critterName.isNotBlank()) " \"${sp.critterName}\"" else "", fontWeight = FontWeight.Bold)
                        Small("level ${sp.force}  ·  ${sp.services} tasks  ·  ${if (sp.bound) "registered" else "not registered"}")
                    }
                    StepButton("×", 36) { vm.removeSprite(sp.guid) }
                }
            }
            s.initiation?.let { initiationItems(it, vm, { initiating = true }, { pickingMeta = true }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton(if (full) "Forms full" else "+ Form", { if (!full) adding = true }, Modifier.weight(1f))
            TerminalButton(if (section.sprites.count { it.bound } >= section.spriteLimit) "Sprites full" else "+ Sprite", { if (section.sprites.count { it.bound } < section.spriteLimit) addingSprite = true }, Modifier.weight(1f))
        }
    }
    if (adding) {
        val options = remember { vm.complexFormOptions().map { PickItem(it.id, it.name, listOf("target ${it.target}", "duration ${it.duration}", "fade ${it.fade}", it.source).filter { x -> x.isNotBlank() }.joinToString("  ·  ")) } }
        PickerSheet("Add a complex form", options, {
            val o = vm.complexFormOptions().first { x -> x.id == it }
            if (o.needsChoice) choosing = it else vm.addComplexForm(it)
        }) { adding = false }
    }
    s.initiation?.let { sec ->
        if (initiating) InitiateSheet(sec, vm) { initiating = false }
        if (pickingMeta) MetamagicPicker(sec, vm) { pickingMeta = false }
    }
    if (addingSprite) {
        val options = remember { vm.spriteOptions().map { PickItem(it.id, it.name, "level ${section.spriteMax}  ·  ${it.source}") } }
        PickerSheet("Add a sprite", options, { vm.addSprite(it, "") }) { addingSprite = false }
    }
    choosing?.let { id ->
        // Forms named "[Matrix Attribute]" are learned for one of the four attributes.
        val attrs = listOf("Attack", "Sleaze", "Data Processing", "Firewall").map { PickItem(it, it, "") }
        PickerSheet("Which Matrix attribute?", attrs, { vm.addComplexForm(id, it) }) { choosing = null }
    }
}
