package studio.missmercury.hgcommlink.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chummer.core.data.GameDataVersion
import studio.missmercury.hgcommlink.CrashReporter

/** Where players can find the project. Keep these current: shipped builds carry them. */
object AboutLinks {
    const val GITHUB = "https://github.com/${CrashReporter.REPO}"
    const val ISSUES = "$GITHUB/issues"
    const val DISCORD = "https://discord.gg/uqFU66eq22"
    const val CHUMMER = "https://github.com/chummer5a/chummer5a"
}

/** What the app is, which build this is, where to find the project, and the credits and licence notes. */
@Composable
fun AboutSheet(dataInUse: GameDataVersion?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = CrashReporter.appVersion(context)
    // "5.226" and the pinned commit, written into the APK at build time; an unreadable file just leaves the line out.
    val chummer = remember { runCatching { context.assets.open("chummer-version.txt").bufferedReader().readLines().filter { it.isNotBlank() } }.getOrNull()?.takeIf { it.size >= 2 } }
    fun open(url: String) { if (!CrashReporter.open(context, url)) Toast.makeText(context, "No browser found to open $url", Toast.LENGTH_LONG).show() }
    TerminalSheet("About", onDismiss, tall = true) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("hgCOMMLINK", style = MaterialTheme.typography.titleLarge, color = PhosphorGreen, modifier = Modifier.padding(top = 8.dp))
            Text("MISS MERCURY STUDIO // CHARACTER TERMINAL", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
            Text("Version $version", style = MaterialTheme.typography.labelMedium)
            if (dataInUse?.isCustom == true) {
                Text("Chummer data in use: ${dataInUse.label}", style = MaterialTheme.typography.labelMedium, color = DrainAmber)
                Text("Added from a ${dataInUse.source}${dataInUse.importedAt?.let { " on $it" }.orEmpty()}, ID ${dataInUse.short}. Menu, Game data, switches back.", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                chummer?.let { Text("Included with the app: Chummer 5a data ${it[0]}", style = MaterialTheme.typography.labelSmall, color = MutedSilver) }
            } else {
                chummer?.let { Text("Chummer 5a data ${it[0]}  (commit ${it[1]})", style = MaterialTheme.typography.labelMedium, color = MutedSilver) }
            }
            Text(
                "A Shadowrun 5th Edition character app for Android. It makes and manages characters on your phone and aims to match the numbers Chummer 5a shows on the desktop.",
                style = MaterialTheme.typography.bodySmall,
            )

            Section("COMMUNITY")
            TerminalButton("Join the Discord", { open(AboutLinks.DISCORD) }, Modifier.fillMaxWidth())
            TerminalButton("Source code on GitHub", { open(AboutLinks.GITHUB) }, Modifier.fillMaxWidth())
            TerminalButton("Report a problem", { open(CrashReporter.bugUrl(context, null)) }, Modifier.fillMaxWidth())
            TerminalButton("Send feedback", { open(CrashReporter.feedbackUrl(context)) }, Modifier.fillMaxWidth())

            Section("CREDITS")
            Text(
                "Rules data and behavior come from Chummer 5a, by its contributors, which is licensed under the GNU GPL v3. This app is GPL-3 as well, and Chummer's copyright notices apply to the game data it ships.",
                style = MaterialTheme.typography.bodySmall,
            )
            TerminalButton("Chummer 5a on GitHub", { open(AboutLinks.CHUMMER) }, Modifier.fillMaxWidth())
            Text(
                "Shadowrun is a trademark of The Topps Company, Inc. This is an unofficial fan project, not affiliated with or endorsed by Chummer, Catalyst Game Labs or Topps.",
                style = MaterialTheme.typography.bodySmall, color = MutedSilver, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = MutedSilver, modifier = Modifier.padding(top = 8.dp))
}
