package studio.missmercury.hgcommlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.input.KeyboardType
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
    // It is also kept on disk, so a launch after the app was dropped in the background comes back to the same tab.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val last = remember { LastScreen(appContext) }
    var tabName by rememberSaveable(open.file.id) { mutableStateOf(last.tab(open.file.id)) }
    Column(Modifier.fillMaxSize()) {
        when {
            sheet == null && open.sheetError == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This takes several seconds the first time, and again whenever custom data changes.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
                TerminalLoader(LoadLines.character, real = LoadLog.data.lines)
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
                    add("Gear" to "GEAR"); if (!sheet.isAI || sheet.sections.ware.isNotEmpty()) add("Ware" to "WARE"); add("Life" to "LIFE"); add("Qualities" to "QUAL")
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
                BottomNav(tabs, current) { tabName = it; last.setTab(open.file.id, it) }
            }
        }
    }
}

/** A small "sell" link under an item in career. */
@Composable
private fun SellLink(onClick: () -> Unit) {
    Text("sell", Modifier.clickable(onClick = onClick).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = DrainAmber)
}

/** The share of an item's price to take when selling it. */
@Composable
internal fun SellSheet(name: String, onSell: (Double) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    // Chummer's Sell Item box starts at 50 percent and takes anything from 0 to 9999.99, so this does too.
    var text by remember { mutableStateOf("50") }
    val percent = text.toDoubleOrNull()?.takeIf { it in 0.0..chummer.core.engine.CareerAdvancement.MAX_SELL_PERCENT }
    fun step(by: Double) {
        val now = (text.toDoubleOrNull() ?: 0.0) + by
        val clamped = now.coerceIn(0.0, chummer.core.engine.CareerAdvancement.MAX_SELL_PERCENT)
        text = if (clamped == Math.rint(clamped)) clamped.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, clamped).trimEnd('0').trimEnd('.')
    }
    TerminalSheet("Sell $name", onDismiss) { close ->
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Take a share of what it is worth, with whatever is fitted to it, or remove it for nothing (free or included items). Neither can be undone from the log.", style = MaterialTheme.typography.bodySmall, color = MutedSilver)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("// PERCENT", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
                StepButton("−", 48) { step(-5.0) }
                TerminalField(text, { v -> text = v.filter { c -> c.isDigit() || c == '.' }.take(7) }, "percent", modifier = Modifier.width(110.dp), keyboardType = KeyboardType.Number)
                StepButton("+", 48) { step(5.0) }
            }
            TerminalButton(
                if (percent == null) "Sell" else "Sell at ${text.trimEnd('.')}%", { close(); onSell(percent!!) }, Modifier.fillMaxWidth(),
                accent = DrainAmber, filled = true, enabled = percent != null,
            )
            TerminalButton("Remove, no nuyen", { close(); onRemove() }, Modifier.fillMaxWidth(), accent = CriticalRed)
            TerminalButton("Cancel", close, Modifier.fillMaxWidth())
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
    // Each tab gets an equal share of the width, but never less than its label needs: with many tabs, a narrow phone or a large
    // font the bar scrolls sideways instead of cutting labels off.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().background(MercurySlate).border(1.dp, TerminalGrid)) {
        val share = maxWidth / tabs.size
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            tabs.forEach { (key, label) ->
                val on = key == current
                Box(
                    Modifier.widthIn(min = share).heightIn(min = 52.dp).background(if (on) PhosphorGreen else Color.Transparent).clickable { onPick(key) }
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (on) Obsidian else MutedSilver)
                }
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
        // Every warning (desktop Chummer problems, unverified game data) sits in one block: a line each until tapped, then all in full.
        val warnings = s.desktopProblems + listOfNotNull(warning)
        if (warnings.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clickable { showWarning = !showWarning }.padding(vertical = 2.dp)) {
                warnings.forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Text(
                            it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                            maxLines = if (showWarning) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
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

        // Damage taken and its wound modifier (career only; the numbers below and the skill pools already include it).
        if (s.isCareer) DamageCard(s, vm)

        // The numbers needed in play, together: initiative, condition, armor.
        TerminalCard("Vitals", tag = if (s.isCareer) "02" else "01") {
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
                    if (s.damage.wound != 0) add("Wounds ${s.damage.wound}")
                    s.astralInitiative?.let { add("Astral ${it.value} + ${it.dice}d6") }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.weight(1f))
                    Text(value.toString(), fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Lift / carry", Modifier.weight(1f))
                Text("${s.liftWeight} kg / ${s.carryWeight} kg", fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
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
    val ratedAtOpen = remember { s.activeSkills.filter { it.rating > 0 }.map { it.name }.toSet() }
    Column(Modifier.fillMaxSize()) {
        TerminalField(query, { query = it }, "search skills", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            // Skill groups: folded away until wanted, since there are eleven of them.
            if ((buy != null || career != null) && q.isEmpty() && s.skillGroups.isNotEmpty()) {
                item {
                    val bought = s.skillGroups.count { it.rank > 0 }
                    CollapsibleHeader("Skill groups", groupsOpen, { groupsOpen = !groupsOpen }, summary = if (bought > 0) "$bought bought" else null)
                }
                if (groupsOpen) itemsIndexed(s.skillGroups, key = { i, it -> "g-$i-" + it.name }) { _, g ->
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, color = if (g.rank > 0) Quicksilver else MutedSilver)
                                // Chummer locks a group once its member skills differ; say so instead of leaving dead buttons.
                                if (g.locked) Text("locked: its skills differ", style = MaterialTheme.typography.labelSmall, color = DrainAmber)
                            }
                            if (career != null) {
                                Text("${g.rank}", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
                                if (!g.locked) UpgradeButton(career.groupCosts[g.name], karma) { vm.improveGroup(g.name) }
                            } else {
                                if (!g.locked) StepButton("−", 44) { vm.changeSkillGroup(g.name, -1) }
                                Text("${g.rank}", Modifier.width(24.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                if (!g.locked) StepButton("+", 44) { vm.changeSkillGroup(g.name, 1) }
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
            // The split is taken when the tab opens, so buying a first rank does not move the row out from under the finger (issue #4).
            val (rated, unrated) = s.activeSkills.filter(::matches).partition { it.name in ratedAtOpen }
            if (rated.isNotEmpty()) item { SubHeader("Active skills") }
            // Keys carry the position: a character file can hold a blank or repeated skill name, and a repeated key crashes the list.
            itemsIndexed(rated, key = { i, it -> "a-$i-" + it.name }) { _, it -> SkillRow(it, buy, career, karma, vm, s.skillSpecs[it.name]) }
            if (unrated.isNotEmpty()) item { SubHeader(if (rated.isEmpty()) "Active skills" else "Other skills") }
            itemsIndexed(unrated, key = { i, it -> "u-$i-" + it.name }) { _, it -> SkillRow(it, buy, career, karma, vm, s.skillSpecs[it.name]) }
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
private fun SkillRow(r: SkillResult, buy: AppViewModel?, career: Sheet.CareerLines? = null, karma: Int = 0, vm: AppViewModel? = null, spec: chummer.core.engine.SkillSpecInfo? = null) {
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
                    // Creation: the skill's specialization (tap to take it off), or a way to add one once the skill has a rating.
                    if (buy != null && spec != null) {
                        if (spec.spec.isNotEmpty()) Text("spec: ${spec.spec} ×", Modifier.clickable { buy.removeCreationSpec(spec.specGuid) }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
                        else if (spec.canAdd) Text("+spec", Modifier.clickable { speccing = true }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = PhosphorGreen)
                    }
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
    if (speccing && buy != null && spec != null) {
        SpecializationSheet(r.name, 0, spec.choices, 0, { buy.addCreationSpec(spec.skillGuid, it, r.name) }, { speccing = false }, note = "Costs a skill point (karma where the skill has to pay with karma).")
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
        if (s.mentors.isNotEmpty()) {
            item { SubHeader("Mentor spirit") }
            items(s.mentors, key = { it.guid }) { m ->
                Column(Modifier.fillMaxWidth()) {
                    Text(m.name, fontWeight = FontWeight.Bold)
                    Small(m.advantage)
                    Small("Disadvantage: " + m.disadvantage)
                    for (set in m.sets) ChoiceChips(if (set.set == 1) "Advantage choice" else "Second choice", listOf("" to "None") + set.options.map { it to it }, set.chosen, { vm.chooseMentor(m.guid, set.set, it) })
                }
            }
        }
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
    val coster = remember { vm.careerQualityCoster() }
    val items = remember {
        options.map { o ->
            val careerCost = coster(o.id)
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
        note = if (vm.open?.sheet?.isCareer == true) "Costs karma now. Common prerequisites are checked when you pick." else "Common prerequisites and the karma limits are checked when you pick.",
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
    // The name takes the spare width and wraps; the value keeps its own width and always has a gap before it.
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(left, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null)
        if (right.isNotBlank()) Text(right, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
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
    var installing by remember { mutableStateOf<Pair<String, Double?>?>(null) }
    var selling by remember { mutableStateOf<Pair<String, String>?>(null) }
    var managing by remember { mutableStateOf<String?>(null) }
    val career = s.career != null
    selling?.let { (guid, name) -> SellSheet(name, { pct -> vm.sellPurchase(guid, pct) }, { vm.discardPurchase(guid) }) { selling = null } }
    // What a card offers for taking an item away: creation takes it back for free, career sells it.
    fun remove(guid: String): (() -> Unit)? = if (creating && guid.isNotEmpty()) ({ vm.removePurchase(guid) }) else null
    fun sell(guid: String, name: String): (() -> Unit)? = if (career && guid.isNotEmpty()) ({ selling = guid to name }) else null
    // A weapon's own sheet: clips, ammunition, accessories, selling. It follows the weapon as it changes.
    managing?.let { guid ->
        val w = g.weapons.firstOrNull { it.guid == guid }
        if (w == null) managing = null
        else WeaponSheet(
            w, vm, s.nuyen, career = career, creating = creating, onAccessory = { attaching = "accessory" to guid },
            onSell = if (w.removable) sell(guid, w.name) else null, onRemove = if (w.removable) remove(guid) else null, onDismiss = { managing = null },
        )
    }
    val armorCount = g.armorSets.sumOf { it.items.size }
    // Naming a set (an empty id is a new one), and the armor whose sheet is open.
    var namingSet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var managingArmor by remember { mutableStateOf<String?>(null) }
    var managingSet by remember { mutableStateOf<String?>(null) }
    var managingGear by remember { mutableStateOf<String?>(null) }
    var pluggingInto by remember { mutableStateOf<Pair<String, String>?>(null) }
    namingSet?.let { (guid, current) ->
        var text by remember(guid) { mutableStateOf(current) }
        TerminalSheet(if (guid.isEmpty()) "New armor set" else "Rename set", { namingSet = null }) { close ->
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalField(text, { text = it }, "name (Runner Armor, Social, ...)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Save", { close(); if (guid.isEmpty()) vm.addArmorSet(text) else vm.renameArmorSet(guid, text) }, Modifier.weight(1f), filled = true, enabled = text.isNotBlank())
                }
            }
        }
    }
    // A gear item's own sheet: what is inside it, adding a plug-in, selling.
    managingGear?.let { guid ->
        val line = g.gear.firstOrNull { it.guid == guid }
        if (line == null) managingGear = null
        else GearSheet(
            line, { id, name -> sell(id, name) }, { id -> remove(id) },
            onPlugIn = if (line.pluggable) ({ pluggingInto = guid to line.name }) else null,
            onToggle = { id, on -> vm.setGearEquipped(id, on) }, onDismiss = { managingGear = null },
        )
    }
    pluggingInto?.let { (guid, name) -> GearPluginDialog(guid, name, vm, s.nuyen) { pluggingInto = null } }
    // A set's own sheet: wear it all, what is in it, adding and taking out armor.
    managingSet?.let { guid ->
        val set = g.armorSets.firstOrNull { it.guid == guid }
        if (set == null) managingSet = null
        else ArmorSetSheet(
            set, g.armorSets.filter { it.guid != guid }.flatMap { other -> other.items.map { it to other.name } }, vm,
            onRename = { namingSet = guid to set.name }, onOpenArmor = { managingArmor = it }, onDismiss = { managingSet = null },
        )
    }
    // An armor's own sheet: wear it, its set, its mods and gear, selling. It follows the armor as it changes.
    managingArmor?.let { guid ->
        val found = g.armorSets.firstNotNullOfOrNull { set -> set.items.firstOrNull { it.guid == guid }?.let { set to it } }
        if (found == null) managingArmor = null
        else ArmorSheet(
            found.second, found.first.guid, g.armorSets, vm,
            onAddMod = { attaching = "mod" to guid },
            onAddGear = if ((found.second.capacityLeft ?: 0.0) > 0.0) ({ installing = guid to found.second.capacityLeft }) else null,
            onSell = sell(guid, found.second.name), onRemove = remove(guid),
            sell = { id, n -> sell(id, n) }, remove = { id -> remove(id) }, onDismiss = { managingArmor = null },
        )
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TelemetryStrip(listOf("ARMOR" to armorCount.toString(), "WEAPONS" to g.weapons.size.toString(), "VEHICLES" to g.vehicles.size.toString(), "GEAR" to g.gear.size.toString())) }
        if (armorCount > 0 || g.armorSets.isNotEmpty()) item { GroupHeader("Armor", armorCount, PhosphorGreen) }
        if (canBuy && armorCount > 0) item { CardActions { CardAction("+ New armor set", { namingSet = "" to "" }, accent = Quicksilver) } }
        for (set in g.armorSets) {
            if (set.guid.isNotEmpty()) item(key = "set-${set.guid}") {
                ItemCard(Quicksilver, compact = true) {
                    Column(Modifier.fillMaxWidth().then(if (canBuy) Modifier.clickable { managingSet = set.guid } else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardTitle(set.name, trailing = "SET", hint = if (canBuy) "MANAGE ›" else null, bold = true)
                        CardTags(buildList {
                            add("${set.items.count { it.equipped }} of ${set.items.size} worn" to if (set.items.any { it.equipped }) PhosphorGreen else MutedSilver)
                        })
                    }
                }
            }
            items(set.items) { a ->
                // The card says what the armor is and what is on it; tapping it (when you can change things) opens its sheet.
                val manage = canBuy && a.guid.isNotEmpty()
                ItemCard(if (a.equipped) PhosphorGreen else MutedSilver) {
                    Column(Modifier.fillMaxWidth().then(if (manage) Modifier.clickable { managingArmor = a.guid } else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardTitle(a.name, trailing = "ARMOR ${a.value}", hint = if (manage) "MANAGE ›" else null)
                        CardTags(buildList {
                            add((if (a.equipped) "Worn" else "Off") to if (a.equipped) PhosphorGreen else DrainAmber)
                            set.name.takeIf { it.isNotBlank() }?.let { add(it to Quicksilver) }
                            a.parts.count { it.isMod }.takeIf { it > 0 }?.let { add("$it mod${if (it > 1) "s" else ""}" to MutedSilver) }
                            a.parts.count { !it.isMod }.takeIf { it > 0 }?.let { add("$it gear" to MutedSilver) }
                            a.parts.filter { !it.equipped }.forEach { add("${it.name} off" to DrainAmber) }
                            a.capacityLeft?.let { add("capacity %.0f".format(java.util.Locale.ROOT, it) to MutedSilver) }
                        })
                        CardNote(a.mods.joinToString(", ").let { if (it.isBlank()) "" else "Mods: $it" })
                        if (a.gear.isNotEmpty()) CardNote("Gear: " + a.gear.joinToString(", "))
                    }
                }
            }
        }
        if (g.weapons.isNotEmpty()) item { GroupHeader("Weapons", g.weapons.size, CriticalRed) }
        items(g.weapons) { w ->
            // The card says what the weapon is and what is in it; tapping it (when you can change things) opens its sheet with the clip, ammunition and accessory controls.
            val manage = canBuy && w.guid.isNotEmpty() && (w.clips.isNotEmpty() || w.allowAccessory || w.removable)
            ItemCard(CriticalRed) {
                Column(Modifier.fillMaxWidth().then(if (manage) Modifier.clickable { managing = w.guid } else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardTitle(w.name, trailing = w.damage + if (w.ap.isNotBlank() && w.ap != "-") "  AP ${w.ap}" else "", hint = if (manage) "MANAGE ›" else null)
                    CardTags(buildList {
                        // What is in the gun comes first, since it decides the damage and AP shown: "Loaded: APDS ×42".
                        if (w.loadedAmmo.isNotBlank()) add("Loaded: ${ammoLabel(w.loadedAmmo)}${if (w.loadedRounds > 0) " ×${w.loadedRounds}" else ""}" to PhosphorGreen)
                        else if (w.canLoad) add("Empty" to DrainAmber)
                        if (w.clips.size > 1) add("Clip ${w.clips.indexOfFirst { it.active } + 1} of ${w.clips.size}" to MutedSilver)
                        listOfNotNull(
                            w.category.takeIf { it.isNotBlank() }, w.mode.takeIf { it.isNotBlank() && it != "0" }, w.ammo.takeIf { it.isNotBlank() && it != "0" }?.let { "ammo $it" },
                            w.reach.takeIf { w.type.equals("Melee", true) && it.isNotBlank() }?.let { "reach $it" }, w.accuracy.takeIf { it.isNotBlank() }?.let { "acc $it" },
                            "RC ${w.rc}", w.conceal.takeIf { it != "0" }?.let { "conceal $it" },
                        ).filter { it.isNotBlank() }.forEach { add(it to MutedSilver) }
                    })
                    // The spares: how many clips are loaded and how many rounds that is, without opening the sheet.
                    if (w.clips.size > 1) CardNote("${w.clips.count { it.rounds > 0 }} of ${w.clips.size} clips loaded  ·  ${w.clips.sumOf { it.rounds }} rounds")
                    w.accessories.groupingBy { it }.eachCount().entries.joinToString(", ") { (n, c) -> if (c > 1) "$n x$c" else n }.let { if (it.isNotBlank()) CardNote("Accessories: $it") }
                }
            }
        }
        if (g.vehicles.isNotEmpty()) item { GroupHeader("Vehicles", g.vehicles.size, DrainAmber) }
        items(g.vehicles) { v ->
            ItemCard(DrainAmber) {
                CardTitle(v.name, trailing = v.category)
                // The figures are one line of "Handling 4  Speed 5 ..."; each becomes its own tag so they wrap cleanly.
                CardTags(v.stats.split("  ").map { it.trim() }.filter { it.isNotEmpty() }.map { it to MutedSilver })
                if (v.mods.isNotEmpty()) CardNote("Mods: " + v.mods.joinToString(", "))
                if (remove(v.guid) != null || sell(v.guid, v.name) != null) CardActions {
                    sell(v.guid, v.name)?.let { CardAction("Sell", it, accent = DrainAmber) }
                    remove(v.guid)?.let { CardAction("Remove", it, accent = CriticalRed) }
                }
            }
        }
        if (g.gear.isNotEmpty()) item { GroupHeader("Gear", g.gear.size, Quicksilver) }
        items(g.gear) { it ->
            // Gear comes in dozens, so its cards are compact: the tags and the one action (sell in career, remove in creation) share a line.
            // Gear with something plugged in, or that takes plug-ins, opens its own sheet when you can change things.
            val manage = canBuy && it.guid.isNotEmpty() && (it.parts.isNotEmpty() || it.pluggable)
            ItemCard(Quicksilver, compact = true) {
                Column(Modifier.fillMaxWidth().then(if (manage) Modifier.clickable { managingGear = it.guid } else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardTitle(if (it.extra.isBlank()) it.name else "${it.name} (${it.extra})", trailing = if (it.quantity != "1") "x${it.quantity}" else null, hint = if (manage) "MANAGE ›" else null)
                val tags = buildList {
                    it.category.takeIf { c -> c.isNotBlank() }?.let { c -> add(c to MutedSilver) }
                    if (it.rating > 0) add("Rating ${it.rating}" to MutedSilver)
                    if (it.loaded) add("Loaded" to PhosphorGreen)
                }
                val gone: Pair<String, () -> Unit>? = sell(it.guid, it.name)?.let { x -> "Sell" to x } ?: remove(it.guid)?.let { x -> "Remove" to x }
                val merge = it.mergeable && canBuy && it.guid.isNotEmpty()
                if (tags.isNotEmpty() || gone != null || merge) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { CardTags(tags) }
                    if (merge) CardAction("Merge", { vm.mergeGear(it.guid) }, accent = Quicksilver)
                    if (gone != null) CardAction(gone.first, gone.second, accent = if (gone.first == "Sell") DrainAmber else CriticalRed)
                }
                if (it.children.isNotEmpty()) CardNote("Includes: " + it.children.joinToString(", "))
                }
            }
        }
        if (armorCount + g.weapons.size + g.vehicles.size + g.gear.size == 0) item { Text("Nothing carried.", color = MutedSilver) }
    }
    if (canBuy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalButton("Gear", { buying = "gear" }, Modifier.weight(1f))
        TerminalButton("Weapon", { buying = "weapon" }, Modifier.weight(1f))
        TerminalButton("Armor", { buying = "armor" }, Modifier.weight(1f))
    }
    if (canBuy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
        TerminalButton("+ Vehicle or drone", { buying = "vehicle" }, Modifier.fillMaxWidth())
    }
    }
    buying?.let { BuyGearDialog(it, vm, s.nuyen) { buying = null } }
    attaching?.let { (kind, guid) -> AttachPartDialog(kind, guid, vm, s.nuyen) { attaching = null } }
    installing?.let { (armor, room) -> ArmorGearDialog(armor, room ?: 0.0, vm, s.nuyen) { installing = null } }
}

@Composable
private fun Ware(g: chummer.core.engine.SheetSections, essence: Double, s: Sheet, vm: AppViewModel) {
    val canBuy = s.career != null || s.creationBuy != null
    val pluginRoom = s.career?.pluginCapacity ?: s.creationBuy?.pluginCapacity
    var buying by remember { mutableStateOf<Boolean?>(null) }
    var plugging by remember { mutableStateOf<chummer.core.engine.SheetSections.WareLine?>(null) }
    var selling by remember { mutableStateOf<Pair<String, String>?>(null) }
    var upgrading by remember { mutableStateOf<Pair<chummer.core.engine.SheetSections.WareLine, chummer.core.engine.WareUpgrade>?>(null) }
    selling?.let { (guid, name) -> SellSheet(name, { pct -> vm.sellPurchase(guid, pct) }, { vm.discardPurchase(guid) }) { selling = null } }
    upgrading?.let { (w, up) ->
        ConfirmSheet(
            "Upgrade ${w.name}",
            "Move it from ${up.from} to ${up.to} for ¥${formatNuyen(up.cost)}? Only the difference in price is charged (plug-ins in it are upgraded too), and you can undo it from the log.",
            "Upgrade", { vm.upgradeWare(w.guid) }, { upgrading = null },
        )
    }
    val upgrades = s.career?.wareUpgrades.orEmpty()
    // In creation an item can be edited in place (grade and rating): tapping its card opens the editor.
    val edits = s.creationBuy?.wareEdits.orEmpty()
    var editing by remember { mutableStateOf<String?>(null) }
    fun find(guid: String): chummer.core.engine.SheetSections.WareLine? {
        fun walk(list: List<chummer.core.engine.SheetSections.WareLine>): chummer.core.engine.SheetSections.WareLine? =
            list.firstNotNullOfOrNull { if (it.guid == guid) it else walk(it.children) }
        return walk(g.ware)
    }
    editing?.let { guid ->
        val item = find(guid)
        val info = edits[guid]
        if (item != null && info != null) WareEditSheet(item, info, vm, s.nuyen) { editing = null } else editing = null
    }
    val (bio, cyber) = g.ware.partition { it.isBioware }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TelemetryStrip(listOf("ESSENCE" to formatEssence(essence), "CYBERWARE" to cyber.size.toString(), "BIOWARE" to bio.size.toString())) }
        for ((title, list, accent) in listOf(Triple("Cyberware", cyber, PhosphorGreen), Triple("Bioware", bio, AstralMagenta))) {
            if (list.isEmpty()) continue
            item { GroupHeader(title, list.size, accent) }
            items(list, key = { it.guid.ifEmpty { it.name } }) { w ->
                WareCard(
                    w, accent, pluginRoom?.get(w.guid), upgrades[w.guid], edits,
                    onEdit = { editing = it }, onUpgrade = { upgrading = w to upgrades.getValue(w.guid) },
                    onRemove = if (s.creationBuy != null) ({ vm.removePurchase(w.guid) }) else null,
                    onSell = if (s.career != null && w.guid.isNotEmpty()) ({ selling = w.guid to w.name }) else null,
                    onPlug = { plugging = w },
                )
            }
        }
        if (g.ware.isEmpty()) item { Text("No augmentations.", color = MutedSilver) }
    }
    if (canBuy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalButton("+ Cyberware", { buying = false }, Modifier.weight(1f))
        TerminalButton("+ Bioware", { buying = true }, Modifier.weight(1f))
    }
    }
    buying?.let { BuyWareDialog(it, vm, s.nuyen, essence) { buying = null } }
    plugging?.let { w -> BuyWareDialog(w.isBioware, vm, s.nuyen, essence, parent = w) { plugging = null } }
}

/**
 * One installed ware item as a card. In creation the card is tappable (when it can be edited) and says so; plug-ins sit inside it behind a rule.
 */
@Composable
private fun WareCard(
    w: chummer.core.engine.SheetSections.WareLine, accent: Color, capacityLeft: Double?, upgrade: chummer.core.engine.WareUpgrade?,
    edits: Map<String, chummer.core.engine.WareEditInfo>, onEdit: (String) -> Unit, onUpgrade: () -> Unit, onRemove: (() -> Unit)?, onSell: (() -> Unit)?, onPlug: () -> Unit,
) {
    val editable = w.guid.isNotEmpty() && w.guid in edits
    ItemCard(accent) {
        Column(Modifier.fillMaxWidth().then(if (editable) Modifier.clickable { onEdit(w.guid) } else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle(w.name, trailing = wareEssence(w), hint = if (editable) "EDIT ›" else null)
            CardTags(wareTags(w))
        }
        if (w.children.isNotEmpty()) {
            // Plug-ins sit inside the card behind a rule, so it is clear what belongs to what.
            Box(Modifier.fillMaxWidth().padding(start = 4.dp).drawBehind { drawRect(accent.copy(alpha = 0.35f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }) {
                Column(Modifier.fillMaxWidth().padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.children.forEach { c ->
                        val childEditable = c.guid.isNotEmpty() && c.guid in edits
                        Column(Modifier.fillMaxWidth().then(if (childEditable) Modifier.clickable { onEdit(c.guid) } else Modifier)) {
                            CardTitle(c.name, trailing = wareEssence(c), hint = if (childEditable) "EDIT ›" else null, bold = false)
                            CardTags(wareTags(c))
                        }
                    }
                }
            }
        }
        if (upgrade != null || onRemove != null || onSell != null || capacityLeft != null) CardActions {
            // Career: one grade up for the difference in price. Dimmed and inert when the character cannot pay it.
            if (upgrade != null) CardAction("Upgrade to ${upgrade.to}  ¥${formatNuyen(upgrade.cost)}", onUpgrade, accent = PhosphorGreen, enabled = upgrade.affordable)
            if (capacityLeft != null) CardAction(
                if (capacityLeft > 0) "+ Plug-in  (${"%.0f".format(java.util.Locale.ROOT, capacityLeft)} left)" else "Plug-ins full", onPlug, accent = Quicksilver, enabled = capacityLeft > 0,
            )
            if (onSell != null) CardAction("Sell", onSell, accent = DrainAmber)
            if (onRemove != null) CardAction("Remove", onRemove, accent = CriticalRed)
        }
    }
}

private fun wareEssence(w: chummer.core.engine.SheetSections.WareLine) = if (w.essence != 0.0) "${formatEssence(w.essence)} ESS" else null

/** The grade (when it is not Standard) and rating of an item as tags. */
private fun wareTags(w: chummer.core.engine.SheetSections.WareLine): List<Pair<String, Color>> = buildList {
    w.grade.takeIf { it.isNotBlank() && !it.equals("Standard", true) }?.let { add(it to DrainAmber) }
    if (w.rating > 0) add("Rating ${w.rating}" to MutedSilver)
}

/**
 * Change the grade and rating of an installed item in creation. Shows what it would cost and the essence it would use before and after, and
 * what you have; Apply is dimmed when the change is not allowed or cannot be paid.
 */
@Composable
private fun WareEditSheet(w: chummer.core.engine.SheetSections.WareLine, info: chummer.core.engine.WareEditInfo, vm: AppViewModel, nuyen: String, onDismiss: () -> Unit) {
    val grades = remember(w.isBioware) { vm.wareGrades(w.isBioware) }
    var grade by remember(w.guid) { mutableStateOf(info.grade) }
    var rating by remember(w.guid) { mutableStateOf(info.rating) }
    val rated = info.maxRating > 0
    val preview = vm.wareEditPreview(w.guid, grade, if (rated) rating else null)
    val changed = grade != info.grade || (rated && rating != info.rating)
    TerminalSheet("Edit ${w.name}", onDismiss) { close ->
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (info.gradeEditable && grades.size > 1) ChoiceChips("Grade", grades.map { it to it }, grade, { grade = it })
            if (rated) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("// RATING", style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
                    Text("${info.minRating} to ${info.maxRating}", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                }
                StepButton("−", 48) { rating = (rating - 1).coerceAtLeast(info.minRating) }
                Text(rating.toString(), Modifier.width(40.dp), style = MaterialTheme.typography.titleLarge, color = Quicksilver, textAlign = TextAlign.Center)
                StepButton("+", 48) { rating = (rating + 1).coerceAtMost(info.maxRating) }
            }
            if (preview != null) {
                TelemetryStrip(
                    listOf("COST" to "¥${formatNuyen(preview.cost)} → ¥${formatNuyen(preview.newCost)}", "ESSENCE" to "${formatEssence(preview.essence)} → ${formatEssence(preview.newEssence)}"),
                    accent = if (preview.problem != null) DrainAmber else PhosphorGreen,
                )
                Text(
                    preview.problem?.replaceFirstChar { it.uppercase() } ?: "You have ¥$nuyen. Only the difference is spent; plug-ins in the item are re-priced with it.",
                    style = MaterialTheme.typography.labelSmall, color = if (preview.problem != null) DrainAmber else MutedSilver,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalButton("Cancel", close, Modifier.weight(1f))
                TerminalButton("Apply", { close(); vm.editWare(w.guid, grade, if (rated) rating else null) }, Modifier.weight(1f), filled = true, enabled = changed && preview != null && preview.problem == null)
            }
        }
    }
}

@Composable
private fun Life(g: chummer.core.engine.SheetSections, s: Sheet, vm: AppViewModel) {
    val canBuy = s.career != null || s.creationBuy != null
    val creating = s.creationBuy != null
    var addingArt by remember { mutableStateOf(false) }
    var addingLifestyle by remember { mutableStateOf(false) }
    var lifestyleFor by remember { mutableStateOf<chummer.core.engine.LifestyleOption?>(null) }
    var addingCritter by remember { mutableStateOf(false) }
    var addingProgram by remember { mutableStateOf(false) }
    var ratingFor by remember { mutableStateOf<chummer.core.engine.CritterPowerOption?>(null) }
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
            Column {
                Row2(l.name.ifBlank { l.type }, "¥${l.monthly}/mo", bold = true)
                Small(listOf(l.type.takeIf { l.name.isNotBlank() }, l.months.takeIf { it.isNotBlank() }?.let { "$it months paid" }, l.notes).filter { !it.isNullOrBlank() }.joinToString("  ·  "))
                if (canBuy && l.guid.isNotEmpty()) RemoveLink { if (creating) vm.removePurchase(l.guid) else vm.discardPurchase(l.guid) }
            }
        }
        s.aiPrograms?.let { progs ->
            item { SubHeader("Programs") }
            if (progs.isEmpty()) item { Text("No programs.", style = MaterialTheme.typography.bodySmall) }
            items(progs.size) { i ->
                val p = progs[i]
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.extra.isNotBlank()) " (${p.extra})" else "", fontWeight = FontWeight.Bold)
                        Small(if (p.advanced) "advanced program" else "program")
                    }
                    StepButton("×", 36) { vm.removeAiProgram(p.guid) }
                }
            }
            item { TerminalButton("+ Program", { addingProgram = true }, Modifier.fillMaxWidth().padding(vertical = 6.dp)) }
        }
        s.critterPowers?.let { cp ->
            item { SubHeader("Critter powers") }
            if (cp.isEmpty()) item { Text("No critter powers.", style = MaterialTheme.typography.bodySmall) }
            items(cp.size) { i ->
                val p = cp[i]
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.extra.isNotBlank()) " (${p.extra})" else "", fontWeight = FontWeight.Bold)
                        Small(listOf(p.category, if (p.karma > 0) "${p.karma} karma" else "").filter { it.isNotBlank() }.joinToString("  ·  "))
                    }
                    if (creating) StepButton("×", 36) { vm.removeCritterPower(p.guid) }
                }
            }
            item { TerminalButton("+ Critter power", { addingCritter = true }, Modifier.fillMaxWidth().padding(vertical = 6.dp)) }
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
    if (canBuy) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalButton("+ Lifestyle", { addingLifestyle = true }, Modifier.weight(1f))
        TerminalButton("+ Martial art", { addingArt = true }, Modifier.weight(1f))
    }
    }
    if (addingLifestyle) {
        val options = remember { vm.lifestyleOptions() }
        PickerSheet(
            "Add a lifestyle",
            options.map { PickItem(it.id, it.name, listOf("¥${"%,.0f".format(java.util.Locale.ROOT, it.monthly)}/mo", it.source.takeIf { s -> s.isNotBlank() }?.let { s -> s + " " + it.page }).filterNotNull().joinToString("  ·  ")) },
            { id -> lifestyleFor = options.firstOrNull { it.id == id } },
            note = if (creating) "Paid from your nuyen." else "Paid from your nuyen; the log can undo it.",
        ) { addingLifestyle = false }
    }
    lifestyleFor?.let { o ->
        var months by remember(o.id) { mutableStateOf(1) }
        TerminalSheet(o.name, onDismiss = { lifestyleFor = null }) { close ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$months month${if (months == 1) "" else "s"}", Modifier.weight(1f))
                StepButton("-", 44) { if (months > 1) months-- }
                StepButton("+", 44) { if (months < 120) months++ }
            }
            TerminalButton("Add", { vm.buyLifestyle(o.id, months); close(); addingLifestyle = false }, Modifier.fillMaxWidth().padding(vertical = 12.dp), filled = true)
        }
    }
    if (addingProgram) {
        val options = remember { vm.aiProgramOptions() }
        PickerSheet(
            "Add a program",
            options.map { PickItem(it.id, it.name, listOf(if (it.advanced) "advanced" else it.category, it.needs.takeIf { n -> n.isNotBlank() }).filterNotNull().joinToString("  ·  "), if (it.advanced) "Advanced Programs" else "Programs", warn = it.needs.isNotBlank()) },
            { id -> if (options.firstOrNull { it.id == id }?.needs.isNullOrBlank()) vm.addAiProgram(id) },
            note = "A program costs ${vm.aiProgramCost(false)} karma, an advanced one ${vm.aiProgramCost(true)}.",
            keepOpenFor = { id -> options.firstOrNull { it.id == id }?.needs?.isNotBlank() == true },
        ) { addingProgram = false }
    }
    if (addingCritter) {
        val options = remember { vm.critterPowerOptions() }
        PickerSheet(
            "Add a critter power",
            options.map { PickItem(it.id, it.name, listOf(if (it.karma > 0) "${it.karma} karma" else "free", it.type.takeIf { t -> t.isNotBlank() }?.let { t -> "type $t" }, it.range.takeIf { r -> r.isNotBlank() }, it.duration.takeIf { d -> d.isNotBlank() }?.let { d -> "duration $d" }, if (it.unsupported.isNotEmpty()) "some effects not applied yet" else null).filterNotNull().joinToString("  ·  "), it.category, warn = it.unsupported.isNotEmpty()) },
            { id -> val o = options.first { it.id == id }; if (o.maxRating > 0) ratingFor = o else vm.addCritterPower(id, 0) },
            note = if (s.isCareer) "Costs karma." else "Counts against your karma.",
        ) { addingCritter = false }
    }
    ratingFor?.let { o ->
        var rating by remember(o.id) { mutableStateOf(1) }
        TerminalSheet(o.name, onDismiss = { ratingFor = null }) { close ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Rating $rating of ${o.maxRating}", Modifier.weight(1f))
                StepButton("-", 44) { if (rating > 1) rating-- }
                StepButton("+", 44) { if (rating < o.maxRating) rating++ }
            }
            TerminalButton("Add", { vm.addCritterPower(o.id, rating); close(); addingCritter = false }, Modifier.fillMaxWidth().padding(vertical = 12.dp), filled = true)
        }
    }
    if (addingArt) {
        val options = remember { vm.martialArtOptions() }
        val coster = remember { vm.martialArtCoster() }
        val known = g.martialArts.map { it.name }.toSet()
        PickerSheet(
            "Learn martial art",
            options.filter { it.name !in known }.map { o ->
                PickItem(o.id, o.name, listOfNotNull(coster(o.id)?.let { "$it karma" }, o.source.takeIf { it.isNotBlank() }?.let { it + " " + o.page }, if (o.unsupported.isNotEmpty()) "some effects not applied yet" else null).joinToString("  ·  "), warn = o.unsupported.isNotEmpty())
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
private val BUYABLE = setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG", "MAG", "RES", "DEP")

/** Tradition and spells of a character with magic. */
@Composable
private fun Magic(s: Sheet, vm: AppViewModel) {
    val creation = s.karmaLedger != null
    // An adept has powers and no tradition; a mystic adept has both.
    val m = s.magic?.takeIf { s.isMagician || s.powers == null } ?: chummer.core.engine.Sheet.MagicLines("", emptyList())
    val showMagician = s.isMagician || s.powers == null
    var choosingTradition by remember { mutableStateOf(false) }
    var addingSpell by remember { mutableStateOf(false) }
    var addingPreparation by remember { mutableStateOf(false) }
    var addingPower by remember { mutableStateOf(false) }
    var initiating by remember { mutableStateOf(false) }
    var pickingMeta by remember { mutableStateOf(false) }
    var pickingArt by remember { mutableStateOf(false) }
    var addingSpirit by remember { mutableStateOf(false) }
    var editingSpirit by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.powers?.let { pp -> adeptPowerItems(pp, vm) { addingPower = true } }
        s.initiation?.let { initiationItems(it, vm, { initiating = true }, { pickingMeta = true }, { pickingArt = true }, { more = it }) }
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
                    Small(listOfNotNull(if (sp.alchemical) "PREPARATION" else null, sp.category, "type ${sp.type}", sp.range, "duration ${sp.duration}", "drain ${sp.dv}").filter { it.isNotBlank() }.joinToString("  ·  "))
                    Small(sp.descriptors)
                }
                if (creation) StepButton("×", 36) { vm.removeSpell(sp.guid) }
            }
        }
        s.spirits?.takeIf { showMagician }?.let { sec ->
            item {
                Column(Modifier.padding(top = 10.dp)) {
                    Text("// SPIRITS", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Small("${sec.bound} of ${sec.boundLimit} bound  ·  force up to ${sec.maxForce}")
                }
            }
            if (sec.spirits.isEmpty()) item { Text("No spirits.", style = MaterialTheme.typography.bodySmall) }
            items(sec.spirits, key = { it.guid }) { sp ->
                Row(Modifier.fillMaxWidth().clickable { editingSpirit = sp.guid }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sp.name + if (sp.critterName.isNotBlank()) " \"${sp.critterName}\"" else "", fontWeight = FontWeight.Bold)
                        Small("force ${sp.force}  ·  ${sp.services} services  ·  ${if (sp.bound) "bound" else "not bound"}")
                    }
                    StepButton("×", 36) { vm.removeSpirit(sp.guid) }
                }
            }
            item { TerminalButton("+ Spirit", { addingSpirit = true }, Modifier.fillMaxWidth().padding(vertical = 8.dp), enabled = sec.maxForce > 0) }
        }
    }
    if (creation && showMagician) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalButton("+ Spell", { addingSpell = true }, Modifier.weight(1f))
        TerminalButton("+ Prep", { addingPreparation = true }, Modifier.weight(1f))
    }
    }
    if (addingPower) AddPowerDialog(vm) { addingPower = false }
    s.spirits?.let { sec ->
        sec.spirits.firstOrNull { it.guid == editingSpirit }?.let { SpriteSheet(it, sec.maxForce, true, vm, spirit = true) { editingSpirit = null } }
        if (addingSpirit) {
            val options = remember { vm.spiritOptions().map { PickItem(it.id, it.name, "force ${sec.maxForce}  ·  ${it.source}") } }
            PickerSheet("Add a spirit", options, { vm.addSpirit(it) }, note = "Starts at the highest force you can summon; change it afterwards.") { addingSpirit = false }
        }
    }
    s.initiation?.let { sec ->
        if (initiating) InitiateSheet(sec, vm) { initiating = false }
        if (pickingMeta) MetamagicPicker(sec, vm) { pickingMeta = false }
        if (pickingArt) ArtPicker(vm) { pickingArt = false }
        more?.let { GradeItemPicker(it, sec, vm) { more = null } }
    }
    if (choosingTradition) {
        val options = remember { vm.traditionOptions().map { PickItem(it.id, it.name, "Drain ${it.drain}") } }
        PickerSheet("Choose a tradition", options, { vm.setTradition(it) }) { choosingTradition = false }
    }
    if (addingSpell || addingPreparation) {
        val prep = addingPreparation
        val spellCoster = remember { vm.careerSpellCoster() }
        val options = remember(prep) { vm.spellOptions().map { PickItem(it.id, it.name, listOf(it.category, "type ${it.type}", it.range, "drain ${it.dv}", spellCoster(it.id, prep)?.let { c -> "costs $c karma" }.orEmpty()).filter { x -> x.isNotBlank() }.joinToString("  ·  ")) } }
        PickerSheet(if (prep) "Add a preparation" else "Add a spell", options, { vm.addSpell(it, prep) }) { addingSpell = false; addingPreparation = false }
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
    var editingSprite by remember { mutableStateOf<String?>(null) }
    var initiating by remember { mutableStateOf(false) }
    var pickingMeta by remember { mutableStateOf(false) }
    var pickingArt by remember { mutableStateOf(false) }
    var addingSpirit by remember { mutableStateOf(false) }
    var editingSpirit by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf<String?>(null) }
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
                Row(Modifier.fillMaxWidth().clickable { editingSprite = sp.guid }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sp.name + if (sp.critterName.isNotBlank()) " \"${sp.critterName}\"" else "", fontWeight = FontWeight.Bold)
                        Small("level ${sp.force}  ·  ${sp.services} tasks  ·  ${if (sp.bound) "registered" else "not registered"}")
                    }
                    StepButton("×", 36) { vm.removeSprite(sp.guid) }
                }
            }
            s.initiation?.let { initiationItems(it, vm, { initiating = true }, { pickingMeta = true }, { pickingArt = true }, { more = it }) }
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
        if (pickingArt) ArtPicker(vm) { pickingArt = false }
        more?.let { GradeItemPicker(it, sec, vm) { more = null } }
    }
    section.sprites.firstOrNull { it.guid == editingSprite }?.let { SpriteSheet(it, section.spriteMax, career, vm) { editingSprite = null } }
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

/** A sprite's name, level, tasks owed and registration. */
@Composable
private fun SpriteSheet(sp: chummer.core.engine.SpriteLine, maxLevel: Int, career: Boolean, vm: AppViewModel, spirit: Boolean = false, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(sp.critterName) }
    var level by remember { mutableStateOf(sp.force) }
    var tasks by remember { mutableStateOf(sp.services) }
    var registered by remember { mutableStateOf(if (sp.bound) "Yes" else "No") }
    TerminalSheet(sp.name, onDismiss) { close ->
        TerminalField(name, { name = it }, "name")
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Level $level of $maxLevel", Modifier.weight(1f))
            StepButton("-", 44) { if (level > 1) level-- }
            StepButton("+", 44) { if (level < maxLevel) level++ }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tasks owed $tasks", Modifier.weight(1f))
            StepButton("-", 44) { if (tasks > 0) tasks-- }
            StepButton("+", 44) { tasks++ }
        }
        if (career || spirit) ChoiceChips(if (spirit) "Bound" else "Registered", listOf("Yes" to "Yes", "No" to "No"), registered, { registered = it })
        TerminalButton("Save", { if (spirit) vm.updateSpirit(sp.guid, name.trim(), level, tasks, registered == "Yes") else vm.updateSprite(sp.guid, name.trim(), level, tasks, registered == "Yes"); close() }, Modifier.fillMaxWidth().padding(vertical = 12.dp), filled = true)
    }
}

/** The two condition monitors as rows of boxes with a minus and plus, the wound modifier they cause, and a button to clear it all. */
@Composable
private fun DamageCard(s: Sheet, vm: AppViewModel) {
    val d = s.damage
    TerminalCard("Damage", tag = "01") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DamageTrack("Physical", d.physical, d.physicalMax, d.overflowMax, d.threshold) { vm.changeDamage("physical", it) }
            if (d.stunMax > 0)             DamageTrack("Stun", d.stun, d.stunMax, 0, d.threshold) { vm.changeDamage("stun", it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (d.wound == 0) "No wound penalty" else "Wound modifier ${d.wound}\n(1 die per ${d.threshold} boxes)",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = if (d.wound == 0) MutedSilver else DrainAmber,
                )
                TerminalButton("Heal all", { vm.healAll() }, enabled = d.physical + d.stun > 0)
            }
            if (d.physicalRecovery != null && d.stunRecovery != null) Text(
                "Natural recovery pools:  physical ${d.physicalRecovery}  ·  stun ${d.stunRecovery}",
                style = MaterialTheme.typography.labelSmall, color = MutedSilver,
            )
        }
    }
}

@Composable
private fun DamageTrack(label: String, filled: Int, size: Int, overflow: Int, threshold: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", 48) { change(-1) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label.uppercase() + "  $filled / $size" + if (overflow > 0) "  (+$overflow overflow)" else "",
                style = MaterialTheme.typography.labelSmall, color = MutedSilver,
            )
            Row(Modifier.fillMaxWidth()) {
                val total = size + overflow
                repeat(total) { i ->
                    Box(
                        Modifier.weight(1f).height(16.dp)
                            .background(if (i < filled) DrainAmber else SlateHigh)
                            .border(1.dp, if (i >= size) MutedSilver else TerminalGrid),
                    )
                    if ((i + 1) % threshold == 0 && i != total - 1) Spacer(Modifier.width(4.dp)) else if (i != total - 1) Spacer(Modifier.width(1.dp))
                }
            }
        }
        StepButton("+", 48) { change(1) }
    }
}
