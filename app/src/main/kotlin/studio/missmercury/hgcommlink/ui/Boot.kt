package studio.missmercury.hgcommlink.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** What the boot log says: the real start-up chores of the app, in terminal voice. The status is what follows the line when it is done. */
private val BOOT_LINES = listOf(
    "mounting storage volumes" to "OK",
    "locating character folder" to "OK",
    "scanning for .chum5 files" to "OK",
    "reading character headers" to "OK",
    "resolving settings.xml profiles" to "OK",
    "checking custom data folders" to "OK",
    "verifying Chummer data pack" to "OK",
    "parsing metatypes.xml" to "OK",
    "parsing priorities.xml" to "OK",
    "parsing skills.xml" to "OK",
    "parsing qualities.xml" to "OK",
    "parsing spells.xml" to "OK",
    "parsing powers.xml" to "OK",
    "parsing martialarts.xml" to "OK",
    "parsing lifestyles.xml" to "OK",
    "parsing gear.xml" to "OK",
    "parsing weapons.xml" to "OK",
    "parsing armor.xml" to "OK",
    "parsing cyberware.xml" to "OK",
    "parsing bioware.xml" to "OK",
    "parsing vehicles.xml" to "OK",
    "indexing entries by id" to "OK",
    "registering bonus handlers" to "OK",
    "loading improvement tables" to "OK",
    "building picker catalogs" to "OK",
    "wiring karma ledger" to "OK",
    "wiring nuyen ledger" to "OK",
    "opening expense log" to "OK",
    "seeding availability rules" to "OK",
    "checking essence and magic limits" to "OK",
    "calibrating dice pools" to "OK",
    "weighing recoil against strength" to "OK",
    "polishing the chrome" to "OK",
    "waking the decker" to "OK",
    "paying the fixer" to "OK",
    "checking for black ICE" to "CLEAR",
    "tuning to the local grid" to "OK",
    "handshaking with the matrix" to "OK",
    "opening link" to "ONLINE",
)

/**
 * The start-up screen, a terminal window anchored to the top left: the logo glitches in, then log lines fill the screen from the
 * top and scroll as it fills, while a meter at the foot fills. Tap to skip.
 */
@Composable
fun BootScreen(onDone: () -> Unit) {
    val version = LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" }
    var glitch by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }        // log lines written
    val list = rememberLazyListState()
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Logo flicker: a few frames of colour-split ghosting.
        repeat(7) { glitch = if (it % 2 == 0) (it % 3) + 1 else 0; delay(45) }
        glitch = 0
        for (i in 1..BOOT_LINES.size) {
            shown = i
            list.scrollToItem(i + 1) // past the last row: the newest line sits at the foot, as in a terminal
            delay(if (i == BOOT_LINES.size) 120 else 32)
        }
        delay(250)
        fade.animateTo(0f, tween(260))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().alpha(fade.value).background(Obsidian)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
    ) {
        GridBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 20.dp)) {
            // The logo and version stay put; only the log below scrolls.
            // Logo, with ghost copies offset sideways while glitching.
            Column(Modifier.padding(bottom = 16.dp)) {
                Box {
                    if (glitch != 0) {
                        Text("hgCOMMLINK", Modifier.offset(x = (-5 * glitch).dp), style = MaterialTheme.typography.displaySmall, color = AstralMagenta.copy(alpha = 0.8f))
                        Text("hgCOMMLINK", Modifier.offset(x = (5 * glitch).dp), style = MaterialTheme.typography.displaySmall, color = PhosphorGreen.copy(alpha = 0.8f))
                    }
                    Text(
                        "hgCOMMLINK",
                        style = MaterialTheme.typography.displaySmall.copy(shadow = Shadow(PhosphorGreen.copy(alpha = 0.5f), blurRadius = 22f)),
                        color = Quicksilver, modifier = Modifier.alpha(if (glitch == 2) 0.6f else 1f),
                    )
                }
                Text("MISS MERCURY STUDIO // CHARACTER TERMINAL  $version", style = MaterialTheme.typography.labelSmall, color = MutedSilver, modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, userScrollEnabled = false) {
                items(shown) { i -> BootRow(BOOT_LINES[i].first, BOOT_LINES[i].second) }
                item { Spacer(Modifier.height(1.dp)) }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val total = 24
                val progress = shown / BOOT_LINES.size.toFloat()
                repeat(total) { i ->
                    val on = (i + 1) <= (progress * total)
                    Box(Modifier.weight(1f).height(10.dp).background(if (on) PhosphorGreen else TerminalGrid.copy(alpha = 0.5f), RoundedCornerShape(1.dp)))
                }
            }
        }
        ScanlineOverlay()
    }
}

@Composable
private fun BootRow(text: String, status: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("> ", style = MaterialTheme.typography.labelLarge, color = MutedSilver)
        Text(text, style = MaterialTheme.typography.labelMedium, color = Quicksilver, modifier = Modifier.weight(1f))
        if (status.isNotEmpty()) Text("[ $status ]", style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
    }
}
