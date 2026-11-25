package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chummer.core.data.DataStatus
import chummer.core.data.GameDataVersion
import chummer.core.data.SupportedDataSet

/**
 * The menu's "Game data": which Chummer data is in use, whether the app is checked with it, and how to use another Chummer version
 * (the release zip or folder) instead of the one that came with the app.
 */
@Composable
fun GameDataSheet(
    version: GameDataVersion?, recommended: SupportedDataSet?, importing: Boolean, progress: String?,
    onGet: () -> Unit, onZip: () -> Unit, onFolder: () -> Unit, onUseBundled: () -> Unit, onDismiss: () -> Unit,
) {
    TerminalSheet("Game data", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (importing) {
                TerminalLine("ADDING_GAME_DATA")
                Text(progress ?: "This takes a few seconds.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (version != null) {
                Text(version.label, style = MaterialTheme.typography.titleMedium, color = if (version.status == DataStatus.SUPPORTED) PhosphorGreen else DrainAmber)
                Text(
                    if (version.status == DataStatus.SUPPORTED) "The app is checked with this data: its numbers are tested against Chummer's."
                    else "The app has not been checked with this data, so some numbers may differ from Chummer's.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val where = when (version.source) { "bundled" -> "Included in the app"; "zip" -> "Added from a zip"; "folder" -> "Added from a folder"; else -> version.source }
                TelemetryStrip(listOfNotNull("SOURCE" to where, version.importedAt?.let { "ADDED" to it }, version.build?.let { "BUILD" to it }, "ID" to version.short))
            }
            Text(
                "To use a Chummer version you know, download its release zip from Chummer's page and choose it here. The files are copied into the app " +
                    "and stay on your phone; characters are not touched.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (version?.status != DataStatus.SUPPORTED && recommended != null) {
                Text("The app is checked with ${recommended.name}.", style = MaterialTheme.typography.bodyMedium)
            }
            TerminalButton("Get Chummer", onGet, Modifier.fillMaxWidth())
            TerminalButton("Choose zip", onZip, Modifier.fillMaxWidth(), filled = true)
            TerminalButton("Choose folder", onFolder, Modifier.fillMaxWidth())
            if (version != null && version.source != "bundled") TerminalButton("Use the version that came with the app", onUseBundled, Modifier.fillMaxWidth())
        }
    }
}
