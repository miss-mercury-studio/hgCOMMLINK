package studio.missmercury.hgcommlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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

/**
 * The menu's "Game data": which Chummer data is in use, whether the app is checked with it, and how to use another Chummer version
 * (the release zip or folder) instead of the one that came with the app.
 */
@Composable
fun GameDataSheet(
    version: GameDataVersion?, bundledName: String?, importing: Boolean, progress: String?,
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(version.label, style = MaterialTheme.typography.titleMedium, color = if (version.status == DataStatus.SUPPORTED) PhosphorGreen else DrainAmber)
                    if (version.isCustom) Text("[ ${version.channel ?: "CUSTOM"} ]", style = MaterialTheme.typography.labelMedium, color = dataTagColor(version))
                }
                val where = when (version.source) { "bundled" -> "Included in the app"; "zip" -> "Added from a zip"; "folder" -> "Added from a folder"; else -> version.source }
                TelemetryStrip(listOfNotNull("STATUS" to if (version.status == DataStatus.SUPPORTED) "VERIFIED" else "UNVERIFIED", "SOURCE" to where, version.importedAt?.let { "ADDED" to it }, (if (version.commit != null) "COMMIT" to version.commit!! else "ID" to version.short)), stacked = true,
                    valueColors = mapOf("STATUS" to if (version.status == DataStatus.SUPPORTED) PhosphorGreen else DrainAmber))
            }
            Text(
                "To use a Chummer version you know, download its release zip from Chummer's page and choose it here. Your characters are not touched.",
                style = MaterialTheme.typography.bodySmall,
            )
            TerminalButton("Get Chummer", onGet, Modifier.fillMaxWidth())
            TerminalButton("Choose zip", onZip, Modifier.fillMaxWidth(), filled = true)
            TerminalButton("Choose folder", onFolder, Modifier.fillMaxWidth())
            if (version != null && version.source != "bundled") TerminalButton(bundledName?.removePrefix("Chummer ")?.let { "Revert to $it" } ?: "Revert to the included version", onUseBundled, Modifier.fillMaxWidth(), outlineTint = CriticalRed)
        }
    }
}

/** The colour of the NIGHTLY / STABLE / CUSTOM tag: red for a nightly, blue for a version that could not be read, amber for an imported release. */
fun dataTagColor(version: GameDataVersion): Color = when (version.channel) {
    "NIGHTLY" -> CriticalRed
    null -> DataBlue
    else -> DrainAmber
}
