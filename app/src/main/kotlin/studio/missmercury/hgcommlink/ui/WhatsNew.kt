package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import chummer.core.Changelog
import chummer.core.ChangelogRelease

/** The installed app's version name, for the sheet header and bug reports. */
@Composable
fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
}

/** CHANGELOG.md is copied into the APK's assets at build time; an unreadable file shows an empty list, never a crash. */
@Composable
private fun rememberReleases(): List<ChangelogRelease> {
    val context = LocalContext.current
    return remember { runCatching { Changelog.parse(context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList()) }
}

/** The full change log, newest release first, back to the first build. */
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    val releases = rememberReleases()
    val installed = rememberVersionName()
    TerminalSheet("What's new", onDismiss, tall = true) {
        val context = LocalContext.current
        val data = studio.missmercury.hgcommlink.CrashReporter.gameDataBrief(context)
        Text(
            "v$installed" + (data?.let { "\n$it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = MutedSilver, modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            if (releases.isEmpty()) item { Text("The change log could not be read.", Modifier.padding(vertical = 16.dp), color = MutedSilver) }
            items(releases, key = { it.version }) { r ->
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text("// v${r.version}", style = MaterialTheme.typography.titleSmall, color = PhosphorGreen)
                        Text("  ${r.date}" + if (r.version == installed) "  (installed)" else "", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                    }
                    r.sections.forEach { sec ->
                        if (sec.title.isNotBlank()) Text(sec.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MutedSilver, modifier = Modifier.padding(top = 6.dp))
                        sec.items.forEach {
                            Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("•", style = MaterialTheme.typography.bodySmall, color = PhosphorGreen)
                                Text(bullet(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Hairline(color = TerminalGrid.copy(alpha = 0.6f), modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

/** The line with **bold** runs shown bold and the markers removed. */
private fun bullet(text: String): AnnotatedString = buildAnnotatedString {
    text.replace("`", "").split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}
