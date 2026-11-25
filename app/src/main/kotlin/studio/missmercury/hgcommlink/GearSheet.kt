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
 * A gear item with plug-ins or included items, in a bottom sheet: what is in it (each part can be sold or taken out), adding a plug-in, and
 * selling or removing the whole item. The sheet follows [g], so each change shows at once.
 */
@Composable
internal fun GearSheet(
    g: SheetSections.GearLine, sell: (String, String) -> (() -> Unit)?, remove: (String) -> (() -> Unit)?,
    onPlugIn: (() -> Unit)?, onDismiss: () -> Unit,
) {
    TerminalSheet(if (g.extra.isBlank()) g.name else "${g.name} (${g.extra})", onDismiss, accent = Quicksilver, tall = true) { close ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryStrip(listOfNotNull(
                g.category.takeIf { it.isNotBlank() }?.let { "TYPE" to it },
                "QTY" to g.quantity,
                g.rating.takeIf { it > 0 }?.let { "RATING" to it.toString() },
            ), accent = Quicksilver)

            GroupHeader("Inside it", g.parts.size, Quicksilver)
            if (g.parts.isEmpty()) CardNote("Nothing plugged in.")
            g.parts.forEach { p ->
                ItemCard(Quicksilver, compact = true) {
                    CardTitle(if (p.extra.isBlank()) p.name else "${p.name} (${p.extra})", trailing = if (p.quantity != "1") "x${p.quantity}" else null, bold = true)
                    CardTags(buildList { if (p.rating > 0) add("Rating ${p.rating}" to MutedSilver) })
                    val sold = sell(p.guid, p.name)
                    val gone = remove(p.guid)
                    if (p.guid.isNotEmpty() && (sold != null || gone != null)) CardActions {
                        sold?.let { CardAction("Sell", { it() }, accent = DrainAmber) }
                        gone?.let { CardAction("Remove", { it() }, accent = CriticalRed) }
                    }
                }
            }
            onPlugIn?.let { add -> CardActions { CardAction("+ Plug-in", { add() }, accent = PhosphorGreen) } }

            val sellIt = sell(g.guid, g.name)
            val removeIt = remove(g.guid)
            if (sellIt != null || removeIt != null) {
                GroupHeader("Get rid of it", 1, DrainAmber)
                CardActions {
                    sellIt?.let { CardAction("Sell", { it() }, accent = DrainAmber) }
                    removeIt?.let { CardAction("Remove", { it() }, accent = CriticalRed) }
                }
            }
        }
        TerminalButton("Done", close, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
