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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import studio.missmercury.hgcommlink.ui.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.animateFloat
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
            HgTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    GridBackdrop()
                    App(vm)
                    ScanlineOverlay()
                    // Shown once per launch; kept across rotation.
                    var booted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                    if (!booted) BootScreen { booted = true }
                }
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
    var showNew by remember { mutableStateOf(false) }

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
        containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = Quicksilver,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (open == null) {
                var menu by remember { mutableStateOf(false) }
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    title = {
                        Column {
                            AppTitle("CHARACTERS")
                            Text(vm.storageLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Choose a shared folder") }, onClick = { menu = false; folderPicker.launch(null) })
                            if (vm.isSharedFolder) {
                                DropdownMenuItem(text = { Text("Use app storage") }, onClick = { menu = false; vm.useAppFolder() })
                            }
                            DropdownMenuItem(text = { Text("New character") }, onClick = { menu = false; showNew = true })
                            DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; vm.refresh() })
                        }
                    },
                )
            } else {
                var confirm by remember { mutableStateOf(false) }
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    title = {
                        val summary = open.character.summary()
                        Column {
                            AppTitle(summary.alias.ifBlank { summary.name.ifBlank { open.file.name } })
                            if (summary.alias.isNotBlank() && summary.name.isNotBlank()) Text(summary.name, style = MaterialTheme.typography.labelSmall)
                        }
                    },
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
                FloatingActionButton(onClick = { importPicker.launch(arrayOf("*/*")) }, shape = CardShape) { Icon(Icons.Default.Add, "Import character") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNew) NewCharacterDialog(vm) { showNew = false }
            if (open != null) SheetScreen(open, vm) else CharacterList(vm)
        }
    }
}

@Composable
private fun CharacterList(vm: AppViewModel) {
    when {
        vm.loading && vm.entries.isEmpty() -> TerminalLine("SCANNING_STORAGE", Modifier.padding(24.dp))
        vm.entries.isEmpty() -> Text(
            "No characters in ${vm.storageLabel} yet.\n\nTap + to import a .chum5 file, or open one from another app (Files, email, chat).",
            Modifier.padding(24.dp),
        )
        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.entries, key = { it.file.id }) { entry ->
                val s = entry.summary
                TerminalCard(
                    title = if (s == null) entry.file.name else s.name.ifBlank { entry.file.name },
                    trailing = s?.let { if (it.isCareer) "CAREER" else "CREATION" },
                    accent = if (s == null) CriticalRed else if (s.isCareer) PhosphorGreen else DrainAmber,
                    modifier = Modifier.clickable(enabled = s != null) { vm.openFile(entry.file) },
                ) {
                    if (s == null) {
                        Text("Could not read: ${entry.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (s.alias.isNotBlank()) Text("\"${s.alias}\"", style = MaterialTheme.typography.bodyLarge)
                        Text(listOf(s.metatype, s.buildMethod).filter { it.isNotBlank() }.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium)
                        // A character in creation stores no karma (the amount left is calculated), so only careers show it here.
                        val money = listOfNotNull(s.karma.takeIf { s.isCareer && it.isNotBlank() }?.let { "KARMA" to it }, s.nuyen.takeIf { s.isCareer && it.isNotBlank() }?.let { "NUYEN" to "¥$it" })
                        if (money.isNotEmpty()) TelemetryStrip(money)
                    }
                }
            }
        }
    }
}

/** The top bar as a shell prompt: hg@commlink:~/viper$ with a blinking cursor. */
@Composable
private fun AppTitle(text: String) {
    val blink by androidx.compose.animation.core.rememberInfiniteTransition(label = "prompt").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(550), androidx.compose.animation.core.RepeatMode.Reverse), label = "prompt",
    )
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = PhosphorGreen)) { append("hg@commlink") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = MutedSilver)) { append(":") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = AstralMagenta)) { append("~/" + text.lowercase().replace(' ', '_')) }
            withStyle(androidx.compose.ui.text.SpanStyle(color = MutedSilver)) { append("$ ") }
            withStyle(androidx.compose.ui.text.SpanStyle(color = PhosphorGreen.copy(alpha = if (blink > 0.5f) 1f else 0f))) { append("█") }
        },
        style = MaterialTheme.typography.titleMedium.copy(shadow = androidx.compose.ui.graphics.Shadow(PhosphorGreen.copy(alpha = 0.45f), blurRadius = 16f)),
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** Start a new Karma-build character: a name, a metatype, and (if there are several) a settings profile. */
@Composable
private fun NewCharacterDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = produceState<GameDataStore.NewCharacterOptions?>(null) {
        value = try { vm.newCharacterOptions() } catch (e: Throwable) { null }
    }.value
    var name by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var metatype by remember { mutableStateOf<String?>(null) }
    var variant by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New character") },
        text = {
            if (options == null) {
                Text("Loading game data. This takes several seconds the first time.")
            } else if (options.profiles.isEmpty()) {
                Text("No settings profile with the Karma build method was found. Put your profile's .xml file in your characters folder.")
            } else {
                val chosenProfile = profile ?: 0
                val chosenMetatype = metatype ?: options.metatypes.firstOrNull { it.name == "Human" }?.id
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(alias, { alias = it }, label = { Text("Street name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Metatype", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    options.metatypes.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { metatype = m.id; variant = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = m.id == chosenMetatype, onClick = { metatype = m.id; variant = null })
                            Text(m.name)
                        }
                    }
                    val variants = options.metatypes.firstOrNull { it.id == chosenMetatype }?.variants.orEmpty()
                    if (variants.isNotEmpty()) {
                        Text("Variant", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                        Row(Modifier.fillMaxWidth().clickable { variant = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = variant == null, onClick = { variant = null })
                            Text("None")
                        }
                        variants.forEach { (id, label) ->
                            Row(Modifier.fillMaxWidth().clickable { variant = id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = variant == id, onClick = { variant = id })
                                Text(label)
                            }
                        }
                    }
                    if (options.profiles.size > 1) {
                        Text("Settings profile", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                        options.profiles.forEachIndexed { i, p ->
                            Row(Modifier.fillMaxWidth().clickable { profile = i }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = i == chosenProfile, onClick = { profile = i })
                                Text(p.name)
                            }
                        }
                    } else {
                        Text("Settings: ${options.profiles[0].name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    }
                    Text(
                        "Karma build. Attributes and skills start at the metatype minimum; buying with karma comes next.",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            val ready = options != null && options.profiles.isNotEmpty()
            TextButton(enabled = ready, onClick = {
                val o = options!!
                val id = metatype ?: o.metatypes.firstOrNull { it.name == "Human" }?.id ?: o.metatypes.first().id
                vm.createCharacter(o.profiles[profile ?: 0], name.trim(), alias.trim(), id, variant)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
