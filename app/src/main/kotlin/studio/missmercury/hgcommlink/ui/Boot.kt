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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val BOOT_LINES = listOf(
    "MOUNTING STORAGE" to "OK",
    "READING GAME DATA" to "OK",
    "CALIBRATING DICE POOLS" to "OK",
    "OPENING LINK" to "OK",
)

/** The start-up screen: the logo glitches in, boot lines type out, a meter fills, then the screen fades. Tap to skip. */
@Composable
fun BootScreen(onDone: () -> Unit) {
    val version = LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" }
    var glitch by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }        // boot lines fully written
    var typed by remember { mutableIntStateOf(0) }        // characters of the line being written
    var progress by remember { mutableStateOf(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Logo flicker: a few frames of colour-split ghosting.
        repeat(7) { glitch = if (it % 2 == 0) (it % 3) + 1 else 0; delay(45) }
        glitch = 0
        BOOT_LINES.forEachIndexed { i, (text, _) ->
            for (c in 1..text.length) { typed = c; progress = (i + c / text.length.toFloat()) / BOOT_LINES.size; delay(14) }
            shown = i + 1; typed = 0; delay(110)
        }
        progress = 1f
        delay(250)
        fade.animateTo(0f, tween(260))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().alpha(fade.value).background(Obsidian)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
    ) {
        GridBackdrop()
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
            // Logo, with ghost copies offset sideways while glitching.
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
            Text("MERCURY STUDIO // CHARACTER TERMINAL  $version", style = MaterialTheme.typography.labelSmall, color = MutedSilver, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))

            BOOT_LINES.forEachIndexed { i, (text, status) ->
                if (i < shown) BootRow(text, status)
                else if (i == shown) BootRow(text.take(typed), "")
            }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val total = 24
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
