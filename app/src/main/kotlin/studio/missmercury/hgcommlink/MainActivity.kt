package studio.missmercury.hgcommlink

import chummer.core.engine.PriorityChoice
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
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

    private var started = false

    /** Coming back to the app (from a file manager or a sync app): pick up files added to the folder meanwhile. The list stays on screen while it reloads, so the scroll position holds. */
    override fun onStart() {
        super.onStart()
        if (started) vm.refresh()
        started = true
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
    var showCritter by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            // A change that can be undone shows UNDO on its confirmation. The message is cleared after it is shown:
            // clearing it first would restart this effect and cancel the snackbar.
            val offer = vm.undoOffer
            // Six seconds to undo: an indefinite snackbar that this gives up on (cancelling it removes it).
            val result = if (offer != null) kotlinx.coroutines.withTimeoutOrNull(6_000) {
                snackbar.showSnackbar(it, actionLabel = "UNDO", duration = androidx.compose.material3.SnackbarDuration.Indefinite)
            } ?: androidx.compose.material3.SnackbarResult.Dismissed
            else { snackbar.showSnackbar(it); androidx.compose.material3.SnackbarResult.Dismissed }
            vm.undoOffer = null
            vm.message = null
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed && offer != null) vm.undoExpense(offer)
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFrom(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseFolder(uri)
    }

    BackHandler(enabled = open != null) { vm.close() }
    var whatsNew by remember { mutableStateOf(false) }
    if (whatsNew) WhatsNewSheet { whatsNew = false }
    // After a crash, the next launch offers to report it (the report was saved by CrashReporter).
    val appContext = LocalContext.current
    var crash by remember { mutableStateOf(CrashReporter.pending(appContext)) }
    crash?.let { report -> CrashReportSheet(report) { CrashReporter.clear(appContext); crash = null } }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = Quicksilver,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 64.dp)) },
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
                        TerminalMenu(menu, { menu = false }) {
                            TerminalMenuItem("Choose a shared folder", onClick = { menu = false; folderPicker.launch(null) })
                            if (vm.isSharedFolder) {
                                TerminalMenuItem("Use app storage", onClick = { menu = false; vm.useAppFolder() })
                            }
                            TerminalMenuItem("Refresh", onClick = { menu = false; vm.refresh() })
                            TerminalMenuItem("What's new", onClick = { menu = false; whatsNew = true })
                            TerminalMenuItem("Report a problem", onClick = { menu = false; CrashReporter.open(appContext, CrashReporter.bugUrl(appContext, null)) })
                            TerminalMenuItem("Send feedback", onClick = { menu = false; CrashReporter.open(appContext, CrashReporter.feedbackUrl(appContext)) })
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
                        // Share and delete live in a menu: delete next to share was an easy mis-tap.
                        var sheetMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { sheetMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        TerminalMenu(sheetMenu, { sheetMenu = false }) {
                            vm.lastUndoable()?.let { last ->
                                TerminalMenuItem("Undo: " + last.reason.take(28), onClick = { sheetMenu = false; vm.undoExpense(last.guid) })
                            }
                            TerminalMenuItem("Share", onClick = {
                                sheetMenu = false
                                vm.shareIntent(open.file)?.let { ctx.startActivity(Intent.createChooser(it, "Share ${open.file.name}")) }
                            })
                            TerminalMenuItem("Delete…", danger = true, onClick = { sheetMenu = false; confirm = true })
                        }
                    },
                )
                if (confirm) {
                    ConfirmSheet(
                        "Delete character?", "${open.file.name} will be removed from ${vm.storageLabel}. This cannot be undone.",
                        "Delete", onConfirm = { vm.delete(open.file) }, onDismiss = { confirm = false }, danger = true,
                    )
                }
            }
        },
        bottomBar = {
            // The two things done most on this screen, under the thumb.
            if (open == null) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("New", { showNew = true }, Modifier.weight(1f), filled = true)
                    TerminalButton("Critter", { showCritter = true }, Modifier.weight(1f))
                    TerminalButton("Import", { importPicker.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNew) NewCharacterDialog(vm) { showNew = false }
            if (showCritter) NewCritterDialog(vm) { showCritter = false }
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

/** Start a new Karma-build character: a name, a metatype, and (if there are several) a settings profile. A tall sheet; Create sits at the bottom. */
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
    // Priority and Sum-to-Ten: a letter for each category (the defaults are worth 10 in Sum-to-Ten and use each letter once).
    var heritage by remember { mutableStateOf("E") }
    var attributes by remember { mutableStateOf("A") }
    var talentLetter by remember { mutableStateOf("B") }
    var skillsLetter by remember { mutableStateOf("C") }
    var resources by remember { mutableStateOf("D") }
    var talent by remember { mutableStateOf<String?>(null) }
    var talentSkills by remember { mutableStateOf(listOf<String>()) }
    var pickingSkill by remember { mutableStateOf<Int?>(null) }

    TerminalSheet("New character", onDismiss, tall = true) { close ->
        when {
            options == null -> TerminalLine("LOADING_GAME_DATA", Modifier.padding(vertical = 20.dp))
            options.profiles.isEmpty() -> Text(
                "No settings profile with the Karma build method was found. Put your profile's .xml file in your characters folder.",
                Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error,
            )
            else -> {
                val chosenProfile = profile ?: 0
                val metatypes by produceState(options.metatypes, chosenProfile) { value = try { vm.metatypesFor(options.profiles[chosenProfile]) } catch (e: Throwable) { options.metatypes } }
                val chosenMetatype = (metatype?.takeIf { id -> metatypes.any { it.id == id } }) ?: metatypes.firstOrNull { it.name == "Human" }?.id
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TerminalField(name, { name = it }, "name")
                    TerminalField(alias, { alias = it }, "street name")
                    ChoiceChips("Metatype", metatypes.map { it.id to it.name }, chosenMetatype, { metatype = it; variant = null })
                    val variants = metatypes.firstOrNull { it.id == chosenMetatype }?.variants.orEmpty()
                    if (variants.isNotEmpty()) {
                        ChoiceChips("Variant", listOf("" to "None") + variants.map { (id, label) -> id to label }, variant ?: "", { variant = it.ifEmpty { null } })
                    }
                    if (options.profiles.size > 1) {
                        ChoiceChips("Settings profile", options.profiles.mapIndexed { i, p -> i.toString() to p.name }, chosenProfile.toString(), { profile = it.toInt() })
                    } else {
                        Text("Settings: ${options.profiles[0].name}", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                    }
                    val method = options.profiles[chosenProfile].buildMethod
                    val points = method == "Priority" || method == "SumtoTen"
                    if (points) {
                        val letters = listOf("A", "B", "C", "D", "E").map { it to it }
                        ChoiceChips("Metatype priority", letters, heritage, { heritage = it })
                        ChoiceChips("Attributes priority", letters, attributes, { attributes = it })
                        ChoiceChips("Magic or resonance priority", letters, talentLetter, { talentLetter = it; talent = null; talentSkills = emptyList() })
                        ChoiceChips("Skills priority", letters, skillsLetter, { skillsLetter = it })
                        ChoiceChips("Resources priority", letters, resources, { resources = it })
                        val talents = vm.priorityTalents(talentLetter)
                        if (talents.isNotEmpty()) ChoiceChips("Talent", talents.map { it.value to it.value }, talent ?: talents.first().value, { talent = it; talentSkills = emptyList() })
                        // The talent's free skills or skill groups: one button for each, opening a list.
                        val chosenTalent = talents.firstOrNull { it.value == (talent ?: talents.first().value) }
                        if (chosenTalent != null && chosenTalent.pickCount > 0) {
                            Text(
                                "Free ${if (chosenTalent.picksGroups) "skill groups" else "skills"} at rating ${chosenTalent.pickValue}",
                                style = MaterialTheme.typography.labelSmall, color = MutedSilver,
                            )
                            repeat(chosenTalent.pickCount) { slot ->
                                TerminalButton(talentSkills.getOrNull(slot) ?: "Choose…", { pickingSkill = slot }, Modifier.fillMaxWidth())
                            }
                            pickingSkill?.let { slot ->
                                val offered = vm.talentSkillChoices(chosenTalent).filter { it !in talentSkills || it == talentSkills.getOrNull(slot) }
                                PickerSheet(
                                    if (chosenTalent.picksGroups) "Skill group" else "Skill", offered.map { PickItem(it, it, "") },
                                    onPick = { picked -> talentSkills = (talentSkills.toMutableList().also { while (it.size <= slot) it.add("") }.also { it[slot] = picked }); pickingSkill = null },
                                    onDismiss = { pickingSkill = null },
                                )
                            }
                        }
                    }
                    Text(
                        if (points) (if (method == "SumtoTen") "Sum-to-Ten: the letters must be worth 10 in all (A 4, B 3, C 2, D 1, E 0). Attribute and skill points are spent on the sheet."
                        else "Priority: each letter A to E once. Attribute and skill points are spent on the sheet.")
                        else "Karma build. Attributes and skills start at the metatype minimum; buy up with karma on the sheet.",
                        style = MaterialTheme.typography.labelSmall, color = MutedSilver,
                    )
                    if (points) {
                        val meta = metatypes.firstOrNull { it.id == chosenMetatype }
                        val variantName = meta?.variants?.firstOrNull { it.first == variant }?.second.orEmpty()
                        val problem = PriorityChoice(heritage, attributes, talentLetter, skillsLetter, resources, "x", method == "SumtoTen").problem()
                            ?: if (!vm.heritageOffered(heritage, meta?.name.orEmpty(), variantName)) "${meta?.name.orEmpty()} is not offered at metatype priority $heritage" else null
                        // What still blocks Create, so a disabled button is never a mystery (the last free-skill button can sit below the fold).
                        val need = vm.priorityTalents(talentLetter).firstOrNull { it.value == (talent ?: vm.priorityTalents(talentLetter).firstOrNull()?.value.orEmpty()) }?.pickCount ?: 0
                        val missing = need - talentSkills.count { it.isNotEmpty() }
                        val hint = problem ?: if (missing > 0) "Choose ${if (missing == 1) "1 more free skill" else "$missing more free skills"} for the talent" else null
                        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DrainAmber) }
                    }
                }
                Row(Modifier.padding(top = 10.dp).imePadding(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    val method = options.profiles[chosenProfile].buildMethod
                    val points = method == "Priority" || method == "SumtoTen"
                    val meta = metatypes.firstOrNull { it.id == chosenMetatype }
                    val talentValue = talent ?: vm.priorityTalents(talentLetter).firstOrNull()?.value.orEmpty()
                    val chosenTalent = vm.priorityTalents(talentLetter).firstOrNull { it.value == talentValue }
                    val picks = talentSkills.filter { it.isNotEmpty() }
                    val choice = if (points) PriorityChoice(heritage, attributes, talentLetter, skillsLetter, resources, talentValue, method == "SumtoTen", picks) else null
                    val variantName = meta?.variants?.firstOrNull { it.first == variant }?.second.orEmpty()
                    val ok = choice == null || (choice.problem() == null && talentValue.isNotEmpty() && picks.size == (chosenTalent?.pickCount ?: 0) && vm.heritageOffered(heritage, meta?.name.orEmpty(), variantName))
                    TerminalButton("Create", {
                        val id = chosenMetatype ?: metatypes.first().id
                        vm.createCharacter(options.profiles[chosenProfile], name.trim(), alias.trim(), id, variant, choice)
                        close()
                    }, Modifier.weight(1f), filled = true, enabled = ok)
                }
            }
        }
    }
}

/** Start a critter (a spirit, a free spirit, an infected...): pick one from the critter data and its Force. It starts as a finished character. */
@Composable
private fun NewCritterDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val options = produceState<List<chummer.core.engine.CritterOption>?>(null) { value = try { vm.critterOptions() } catch (e: Throwable) { emptyList() } }.value
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<chummer.core.engine.CritterOption?>(null) }
    var force by remember { mutableStateOf(3) }
    var picking by remember { mutableStateOf(false) }
    TerminalSheet("New critter", onDismiss) { close ->
        when {
            options == null -> TerminalLine("LOADING_GAME_DATA", Modifier.padding(vertical = 20.dp))
            else -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TerminalField(name, { name = it }, "name")
                TerminalButton(chosen?.name ?: "Choose a critter…", { picking = true }, Modifier.fillMaxWidth())
                if (chosen?.forceBased != false) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Force $force", Modifier.weight(1f))
                    StepButton("−", 44) { if (force > 1) force-- }
                    StepButton("+", 44) { if (force < 12) force++ }
                }
                Text("A critter starts as a finished character: its attributes follow its Force, its powers and skills come with it.", style = MaterialTheme.typography.labelSmall, color = MutedSilver)
                Row(Modifier.padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalButton("Cancel", close, Modifier.weight(1f))
                    TerminalButton("Create", { chosen?.let { vm.createCritter(name.trim(), it.id, if (it.forceBased) force else 0) }; close() }, Modifier.weight(1f), filled = true, enabled = chosen != null)
                }
            }
        }
    }
    if (picking && options != null) {
        PickerSheet("Choose a critter", options.map { PickItem(it.id, it.name, listOf(it.category, if (it.forceBased) "Force" else "").filter { x -> x.isNotBlank() }.joinToString("  ·  "), it.category) }, { id -> chosen = options.first { it.id == id } }) { picking = false }
    }
}
