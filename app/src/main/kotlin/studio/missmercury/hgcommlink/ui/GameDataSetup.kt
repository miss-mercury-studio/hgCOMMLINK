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

/** What the "Get Chummer" step says: the release this app is checked with, or generic words if the list could not be read. */
private fun releaseName(recommended: SupportedDataSet?) = recommended?.name ?: "the latest Chummer 5a release"

/**
 * First run of the Google Play edition, which ships without Chummer's game data: the player gets Chummer's release zip (one button
 * opens the page), then points the app at it. Nothing is downloaded by the app; the player brings the file.
 */
@Composable
fun GameDataSetup(
    importing: Boolean, progress: String?, recommended: SupportedDataSet?,
    onGet: () -> Unit, onZip: () -> Unit, onFolder: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("GAME DATA", style = MaterialTheme.typography.titleLarge)
        if (importing) {
            TerminalLine("ADDING_GAME_DATA")
            Text(progress ?: "This takes a few seconds.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            "hgCOMMLINK is an unofficial fan tool and does not include Shadowrun's rules data. It uses the data files of Chummer 5a, " +
                "which you add once, from the free download.",
            style = MaterialTheme.typography.bodyLarge,
        )
        TerminalCard(title = "1. GET CHUMMER") {
            Text(
                "Download ${releaseName(recommended)}: on its page, under Assets, tap the file ending in .zip (about 45 MB). " +
                    "This is the version the app is checked with.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TerminalButton("Get Chummer", onGet, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        TerminalCard(title = "2. ADD IT") {
            Text("Choose the zip you downloaded. You do not need to unzip it.", style = MaterialTheme.typography.bodyMedium)
            TerminalButton("Choose zip", onZip, Modifier.fillMaxWidth().padding(top = 12.dp), filled = true)
            Text("Already have Chummer unpacked on this phone?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            TerminalButton("Choose folder", onFolder, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Text(
            "The files are copied into the app and stay on your phone. Nothing is sent anywhere, and your characters are not touched.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The menu's "Game data": which Chummer data is in use, whether the app is checked with it, and (in the Play edition, where the player
 * brings the data) how to change it.
 */
@Composable
fun GameDataSheet(
    version: GameDataVersion?, recommended: SupportedDataSet?, canChange: Boolean, importing: Boolean, progress: String?,
    onGet: () -> Unit, onZip: () -> Unit, onFolder: () -> Unit, onDismiss: () -> Unit,
) {
    TerminalSheet("Game data", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (importing) {
                TerminalLine("ADDING_GAME_DATA")
                Text(progress ?: "This takes a few seconds.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (version == null) {
                Text("No game data yet.", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(version.label, style = MaterialTheme.typography.titleMedium, color = if (version.status == DataStatus.SUPPORTED) PhosphorGreen else DrainAmber)
                Text(
                    if (version.status == DataStatus.SUPPORTED) "The app is checked with this data: its numbers are tested against Chummer's."
                    else "The app has not been checked with this data, so some numbers may differ from Chummer's.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val where = when (version.source) { "bundled" -> "Included in the app"; "zip" -> "Added from a zip"; "folder" -> "Added from a folder"; else -> version.source }
                TelemetryStrip(listOfNotNull("SOURCE" to where, version.importedAt?.let { "ADDED" to it }, version.build?.let { "BUILD" to it }, "ID" to version.short))
            }
            if (canChange) {
                if (version?.status != DataStatus.SUPPORTED && recommended != null) {
                    Text("To match the app, use ${recommended.name}.", style = MaterialTheme.typography.bodyMedium)
                }
                TerminalButton("Get Chummer", onGet, Modifier.fillMaxWidth())
                TerminalButton("Choose zip", onZip, Modifier.fillMaxWidth(), filled = true)
                TerminalButton("Choose folder", onFolder, Modifier.fillMaxWidth())
            }
        }
    }
}
