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

/**
 * First run of the Google Play edition, which ships without Chummer's game data: explains what to pick and asks for the
 * player's own Chummer folder. Nothing is downloaded by the app; the player brings the files.
 */
@Composable
fun GameDataSetup(importing: Boolean, onChoose: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("GAME DATA", style = MaterialTheme.typography.titleLarge)
        if (importing) {
            TerminalLine("COPYING_GAME_DATA")
            Text("This takes a few seconds.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            "hgCOMMLINK is an unofficial fan tool and does not include Shadowrun's rules data. It uses the data files of Chummer 5a, " +
                "which you add once from your own copy.",
            style = MaterialTheme.typography.bodyLarge,
        )
        TerminalCard(title = "GET CHUMMER'S FILES") {
            Text(
                "1. Download the latest Chummer 5a release zip (about 44 MB) from github.com/chummer5a/chummer5a, under Releases.\n" +
                    "2. Unzip it on your phone, or copy the folder over from your computer. If you already use Chummer on a PC, copy that Chummer folder.\n" +
                    "3. Tap the button below and choose the Chummer folder: the one that contains \"data\", \"lang\" and \"customdata\".",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "The files are copied into the app and stay on your phone. Nothing is sent anywhere, and your characters are not touched.",
            style = MaterialTheme.typography.bodySmall,
        )
        TerminalButton("Choose Chummer folder", onChoose, Modifier.fillMaxWidth(), filled = true)
    }
}
