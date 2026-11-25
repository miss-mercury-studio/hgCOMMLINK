package studio.missmercury.hgcommlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.missmercury.hgcommlink.ui.*

/**
 * The open character's file was deleted or moved from outside the app while it was open. Everything shown is still in the app's memory, so it can
 * be written out again as a new file, or the character can be closed (which forgets it). Nothing is changed until one is chosen, and no edit is
 * accepted while the file is missing. [character] is the character's name as the title bar shows it, not its file name.
 */
@Composable
internal fun MissingFileSheet(character: String, folder: String, onSaveAgain: () -> Unit, onClose: () -> Unit, onDismiss: () -> Unit) {
    TerminalSheet("File missing", onDismiss, accent = DrainAmber) { close ->
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "The file for $character is no longer in $folder. It was deleted or moved outside the app.\n\n" +
                    "The character is still open here. You can save it again as a new file, or close it and let it go. " +
                    "Changes are paused until you choose.",
                style = MaterialTheme.typography.bodyMedium, color = Quicksilver,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TerminalButton("Close it", { close(); onClose() }, Modifier.weight(1f), accent = CriticalRed)
                TerminalButton("Save it again", { close(); onSaveAgain() }, Modifier.weight(1f), filled = true)
            }
        }
    }
}
