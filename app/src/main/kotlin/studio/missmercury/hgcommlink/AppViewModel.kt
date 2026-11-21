package studio.missmercury.hgcommlink

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chummer.core.CharacterFile
import chummer.core.engine.Sheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.missmercury.hgcommlink.storage.StoredFile

/** The character opened in the detail screen. */
data class OpenCharacter(
    val file: StoredFile,
    val character: CharacterFile,
    /** Null while the sheet is being calculated or if that failed (see [sheetError]). */
    val sheet: Sheet? = null,
    /** Why the numbers may differ from Chummer's (for example a missing settings profile), or null. */
    val warning: String? = null,
    val sheetError: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CharacterRepository(app)
    private val gameData = GameDataStore(app)

    var entries by mutableStateOf<List<CharacterEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var storageLabel by mutableStateOf(repo.storage.label); private set
    var isSharedFolder by mutableStateOf(false); private set
    var open by mutableStateOf<OpenCharacter?>(null); private set

    /** One-shot message for a snackbar; the UI clears it after showing. */
    var message by mutableStateOf<String?>(null)

    init {
        refresh()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            try {
                entries = io { repo.list() }
            } catch (e: Exception) {
                message = "Could not read the folder: ${e.message}"
                entries = emptyList()
            }
            storageLabel = repo.storage.label
            isSharedFolder = repo.storage !is studio.missmercury.hgcommlink.storage.AppFolderStorage
            loading = false
        }
    }

    fun chooseFolder(tree: Uri) {
        repo.useFolder(tree)
        refresh()
    }

    fun useAppFolder() {
        repo.useAppFolder()
        refresh()
    }

    fun importFrom(uris: List<Uri>, thenOpen: Boolean = false) {
        viewModelScope.launch {
            var imported = 0
            var last: StoredFile? = null
            val problems = ArrayList<String>()
            for (uri in uris) {
                val name = displayName(uri)
                try {
                    last = io { repo.import(uri, name) }
                    imported++
                } catch (e: Exception) {
                    problems += "${name ?: "file"}: ${e.message}"
                }
            }
            message = buildString {
                if (imported > 0) append("Imported $imported character${if (imported == 1) "" else "s"}")
                if (problems.isNotEmpty()) append(if (isEmpty()) "" else ". ").append(problems.joinToString("; "))
            }
            refresh()
            if (thenOpen && last != null) openFile(last)
        }
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    fun openFile(file: StoredFile) {
        viewModelScope.launch {
            try {
                val character = io { repo.load(file.id) }
                open = OpenCharacter(file, character)
                // The sheet takes a moment the first time (the game data has to be unpacked and read).
                open = try {
                    val (sheet, warning) = io {
                        repo.syncSettings(gameData.userSettingsDir)
                        gameData.sheet(character)
                    }
                    OpenCharacter(file, character, sheet, warning)
                } catch (e: Throwable) {
                    OpenCharacter(file, character, sheetError = e.message ?: e.javaClass.simpleName)
                }
            } catch (e: Exception) {
                message = "Could not open ${file.name}: ${e.message}"
            }
        }
    }

    fun close() {
        open = null
    }

    fun delete(file: StoredFile) {
        viewModelScope.launch {
            val ok = io { repo.delete(file.id) }
            message = if (ok) "Deleted ${file.name}" else "Could not delete ${file.name}"
            open = null
            refresh()
        }
    }

    fun shareIntent(file: StoredFile) = try {
        repo.shareIntent(file)
    } catch (e: Exception) {
        message = "Could not share: ${e.message}"
        null
    }
}
