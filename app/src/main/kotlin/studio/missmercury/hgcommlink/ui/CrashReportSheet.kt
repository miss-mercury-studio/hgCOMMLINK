package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import studio.missmercury.hgcommlink.CrashReporter

/**
 * Shown on the launch after a crash. The player sees exactly what would be sent, then chooses: open a pre-filled GitHub issue, share the
 * text anywhere, or dismiss it. [onDone] clears the saved report whichever way they go.
 */
@Composable
fun CrashReportSheet(report: String, onDone: () -> Unit) {
    val context = LocalContext.current
    TerminalSheet("Crash report", onDone, tall = true, accent = DrainAmber) { close ->
        Text(
            "hgCOMMLINK closed unexpectedly last time. You can send the report below so it gets fixed. It has the app version, your phone and what the app was doing, and no characters or names.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(report, style = MaterialTheme.typography.labelSmall, color = MutedSilver)
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Report on GitHub", {
                if (!CrashReporter.open(context, CrashReporter.bugUrl(context, report))) CrashReporter.share(context, report)
                close()
            }, Modifier.fillMaxWidth().heightIn(min = 48.dp), filled = true)
            TerminalButton("Share…", { CrashReporter.share(context, report); close() }, Modifier.fillMaxWidth().heightIn(min = 48.dp))
            TerminalButton("Dismiss", close, Modifier.fillMaxWidth().heightIn(min = 48.dp), accent = MutedSilver)
        }
    }
}
