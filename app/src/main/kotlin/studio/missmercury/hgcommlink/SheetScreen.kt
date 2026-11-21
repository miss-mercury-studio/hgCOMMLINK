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
import androidx.compose.material3.TabRow
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
import chummer.core.engine.Sheet
import chummer.core.engine.SkillResult

private val TABS = listOf("Overview", "Skills", "Qualities")

/** The character sheet: calculated numbers on three tabs. Read-only. */
@Composable
fun SheetScreen(open: OpenCharacter) {
    val sheet = open.sheet
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        when {
            sheet == null && open.sheetError == null -> Text("Calculating...", Modifier.padding(24.dp))
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
                TabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> Overview(sheet)
                    1 -> Skills(sheet)
                    else -> Qualities(sheet)
                }
            }
        }
    }
}

@Composable
private fun Header(s: Sheet) {
    val line = listOfNotNull(
        s.metatype.takeIf { it.isNotBlank() }, if (s.isCareer) "Career" else "Creation",
        s.karma.takeIf { it.isNotBlank() }?.let { "Karma $it" }, s.nuyen.takeIf { it.isNotBlank() }?.let { "¥$it" },
    ).joinToString("  ·  ")
    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun Overview(s: Sheet) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
private fun Qualities(s: Sheet) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val positive = s.qualities.filter { it.positive }
        val negative = s.qualities.filter { !it.positive }
        item { SubHeader("Positive") }
        items(positive) { QualityRow(it) }
        item { SubHeader("Negative") }
        items(negative) { QualityRow(it) }
    }
}

@Composable
private fun QualityRow(q: Sheet.QualityLine) {
    Text(if (q.extra.isBlank()) q.name else "${q.name} (${q.extra})")
}
