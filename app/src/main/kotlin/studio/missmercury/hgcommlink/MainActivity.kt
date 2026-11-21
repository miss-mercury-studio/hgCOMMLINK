package studio.missmercury.hgcommlink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chummer.core.CharacterFile
import studio.missmercury.hgcommlink.storage.StoredFile

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** "Open with" and "Share to" from other apps: copy the file(s) into storage and open the result. */
    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importFrom(uris, thenOpen = uris.size == 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val open = vm.open

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFrom(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseFolder(uri)
    }

    BackHandler(enabled = open != null) { vm.close() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (open == null) {
                var menu by remember { mutableStateOf(false) }
                TopAppBar(
                    title = {
                        Column {
                            Text("Characters")
                            Text(vm.storageLabel, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Choose a shared folder") }, onClick = { menu = false; folderPicker.launch(null) })
                            if (vm.isSharedFolder) {
                                DropdownMenuItem(text = { Text("Use app storage") }, onClick = { menu = false; vm.useAppFolder() })
                            }
                            DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; vm.refresh() })
                        }
                    },
                )
            } else {
                var confirm by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text(open.character.summary().name.ifBlank { open.file.name }) },
                    navigationIcon = {
                        IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = {
                            vm.shareIntent(open.file)?.let { ctx.startActivity(Intent.createChooser(it, "Share ${open.file.name}")) }
                        }) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete") }
                    },
                )
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Delete character?") },
                        text = { Text("${open.file.name} will be removed from ${vm.storageLabel}. This cannot be undone.") },
                        confirmButton = { TextButton(onClick = { confirm = false; vm.delete(open.file) }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                }
            }
        },
        floatingActionButton = {
            if (open == null) {
                FloatingActionButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Import character") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (open != null) CharacterDetail(open) else CharacterList(vm)
        }
    }
}

@Composable
private fun CharacterList(vm: AppViewModel) {
    when {
        vm.loading && vm.entries.isEmpty() -> Text("Loading...", Modifier.padding(24.dp))
        vm.entries.isEmpty() -> Text(
            "No characters in ${vm.storageLabel} yet.\n\nTap + to import a .chum5 file, or open one from another app (Files, email, chat).",
            Modifier.padding(24.dp),
        )
        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.entries, key = { it.file.id }) { entry ->
                Card(Modifier.fillMaxWidth().clickable(enabled = entry.summary != null) { vm.openFile(entry.file) }) {
                    Column(Modifier.padding(16.dp)) {
                        val s = entry.summary
                        if (s == null) {
                            Text(entry.file.name, fontWeight = FontWeight.Bold)
                            Text("Could not read: ${entry.error}", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(s.name.ifBlank { entry.file.name }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (s.alias.isNotBlank()) Text("\"${s.alias}\"")
                            Text(listOf(s.metatype, if (s.isCareer) "Career" else "Creation", s.buildMethod).filter { it.isNotBlank() }.joinToString(" - "))
                            Text(
                                listOfNotNull(s.karma.takeIf { it.isNotBlank() }?.let { "Karma $it" }, s.nuyen.takeIf { it.isNotBlank() }?.let { "¥$it" })
                                    .joinToString("   "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val ATTRIBUTE_ORDER = listOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG", "MAG", "RES")

/** Read-only for now; the full sheet comes with the calculation engine (M2 and M3). */
@Composable
private fun CharacterDetail(open: OpenCharacter) {
    val s = open.character.summary()
    val attrs = open.character.attributes().associateBy { it.name }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(listOf(s.metatype, if (s.isCareer) "Career" else "Creation", s.buildMethod, s.edition).filter { it.isNotBlank() }.joinToString(" - "))
        Text("Karma ${s.karma}   ¥${s.nuyen}")
        Text("Saved with Chummer ${s.appVersion}", style = MaterialTheme.typography.bodySmall)
        Text("Attributes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        for (name in ATTRIBUTE_ORDER) {
            val a = attrs[name] ?: continue
            if (a.storedTotal == 0 && name in setOf("MAG", "RES")) continue
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(name)
                Text(a.storedTotal.toString(), fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Full character sheet and editing are coming in later milestones. Your file is stored unchanged.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp),
        )
    }
}
