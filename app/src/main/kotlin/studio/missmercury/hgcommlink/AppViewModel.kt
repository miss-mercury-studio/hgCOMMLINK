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
import chummer.core.data.DataStatus
import chummer.core.data.GameDataVersion
import chummer.core.data.SupportedData
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
    private val last = LastScreen(app)
    private var restoreTried = false

    /** True when the open character's file has been deleted from outside the app: the screen asks whether to save it again or close it. */
    var fileMissing by mutableStateOf(false); private set

    /** True from launch until the character the player had open has been put back (or it turns out there is none to put back): the screen shows "resuming", not the list. */
    var restoring by mutableStateOf(last.fileId() != null); private set

    var entries by mutableStateOf<List<CharacterEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var storageLabel by mutableStateOf(repo.storage.label); private set
    var isSharedFolder by mutableStateOf(false); private set
    var open by mutableStateOf<OpenCharacter?>(null); private set

    /** True while game data from another Chummer version is being copied in or removed. */
    var importingGameData by mutableStateOf(false); private set
    /** What the game data is and whether this app is checked against it; null until it is read. */
    var gameDataVersion by mutableStateOf<GameDataVersion?>(null); private set
    /** What the import is doing now ("Reading the zip", "Copied 50 files"). */
    var gameDataProgress by mutableStateOf<String?>(null); private set

    /** One-shot message for a snackbar; the UI clears it after showing. */
    var message by mutableStateOf<String?>(null)

    /** The log entry the last career change wrote, when it can be undone: the snackbar offers UNDO for it. */
    var undoOffer by mutableStateOf<String?>(null)

    init {
        refresh()
        // Hashing the data takes a moment; it is done once, off the main thread, so every screen can say which version is in use.
        viewModelScope.launch { gameDataVersion = runCatching { io { gameData.version() } }.getOrNull() }
    }

    /** The release the "Get Chummer" button sends the player to: the newest one this app is checked against. */
    /** The version bundled in the app, as the supported list names it ("Chummer 5.226.0"): the pinned data set the app is built with. */
    fun bundledDataName(): String? = GameDataInfo.supported(getApplication()).firstOrNull { it.source == "pin" }?.name

    fun recommendedData() = SupportedData.recommended(GameDataInfo.supported(getApplication()))

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Reload the list. [quiet] keeps the list on screen while it reloads (used when coming back from a character). */
    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) loading = true
            try {
                entries = io { repo.list() }
            } catch (e: Exception) {
                message = "Could not read the folder: ${e.message}"
                entries = emptyList()
            }
            storageLabel = repo.storage.label
            isSharedFolder = repo.storage !is studio.missmercury.hgcommlink.storage.AppFolderStorage
            loading = false
            restoreLastScreen()
        }
    }

    /**
     * Once per launch, once the list is read: open the character the player was on when the app was last closed or dropped. Nothing is done when
     * something else has opened a character already (a file sent to the app), and a character that no longer exists is forgotten.
     */
    private fun restoreLastScreen() {
        if (restoreTried) return
        restoreTried = true
        val id = last.fileId()
        val file = entries.firstOrNull { it.file.id == id }?.file
        if (open == null && file != null) openFile(file)
        else {
            restoring = false
            // Forget it only when the list was read and the character is not in it (a folder that could not be read this time says nothing).
            if (id != null && file == null && entries.isNotEmpty()) last.clear()
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

    /** Use another Chummer version: copy the game data from the Chummer release zip, or the folder, the player picked. Also used to update it later. */
    fun addGameData(uri: Uri, zip: Boolean) {
        if (importingGameData) return
        viewModelScope.launch {
            importingGameData = true
            gameDataProgress = "Reading"
            try {
                val v = io { gameData.import(uri, zip) { gameDataProgress = it } }
                gameDataVersion = v
                message = if (v.status == DataStatus.SUPPORTED) "Added ${v.label}."
                else "Added ${v.label}, unverified: numbers may differ from Chummer's."
            } catch (e: Exception) {
                message = e.message ?: "Could not add the game data."
            } finally {
                importingGameData = false
                gameDataProgress = null
            }
        }
    }

    /** Drop imported game data and go back to the version bundled in the app. */
    fun useBundledData() {
        if (importingGameData) return
        viewModelScope.launch {
            importingGameData = true
            try {
                gameDataVersion = io { gameData.useBundled() }
                message = "Using the game data that came with the app."
            } finally {
                importingGameData = false
            }
        }
    }

    fun openFile(file: StoredFile) {
        viewModelScope.launch {
            try {
                val character = io { repo.load(file.id) }
                last.opened(file.id)
                open = OpenCharacter(file, character)
                restoring = false
                // The sheet takes a moment the first time (the game data has to be unpacked and read).
                open = try {
                    val started = System.nanoTime()
                    val (sheet, warning) = io {
                        repo.syncSideFiles(gameData.userSettingsDir, gameData.userCustomDir)
                        gameData.sheet(character)
                    }
                    android.util.Log.i("hgcommlink", "sheet ready in ${(System.nanoTime() - started) / 1_000_000} ms")
                    OpenCharacter(file, character, sheet, warning)
                } catch (e: Throwable) {
                    OpenCharacter(file, character, sheetError = e.message ?: e.javaClass.simpleName)
                }
            } catch (e: Exception) {
                restoring = false
                last.clear()
                message = "Could not open ${file.name}: ${e.message}"
            }
        }
    }

    /** Add a quality from the game data to the open character, save the file and recalculate the sheet. */
    fun addQuality(entryId: String, extra: String = "") {
        val current = open ?: return
        viewModelScope.launch {
            val career = current.sheet?.isCareer == true
            val problem = try {
                io { gameData.editor(current.character).let { ed -> ed.qualityRequirementProblem(entryId) ?: if (career) null else ed.qualityLimitProblem(entryId) } }
            } catch (e: Throwable) { null }
            if (problem != null) message = problem
            else if (career) editCareer("Added quality", "Not enough karma for that quality") { adv, _ -> adv.addQuality(entryId, extra) != null }
            else edit("Added quality") { editor -> editor.addQuality(entryId, extra); true }
        }
    }

    /** Buy (+1) or give back (-1) a point of an attribute with karma, in creation. */
    fun changeAttribute(name: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "$name is at its maximum, or not enough karma left" else "No karma point in $name to give back") { ed -> if (delta > 0) ed.withinKarma({ ed.changeAttribute(name, 1) }, { ed.changeAttribute(name, -1) }) else ed.changeAttribute(name, delta) }

    /** Buy (+1) or give back (-1) a rating point of an active skill with karma, in creation. */
    fun changeSkill(name: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "$name is at the creation maximum, or not enough karma left" else "No karma point in $name to give back") { ed -> if (delta > 0) ed.withinKarma({ ed.changeSkill(name, 1) }, { ed.changeSkill(name, -1) }) else ed.changeSkill(name, delta) }

    /** Mark one more box (+1) or one fewer (-1) of damage on the "physical" or "stun" monitor. */
    fun changeDamage(track: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "The $track track is full" else "No $track damage to clear") { it.changeDamage(track, delta) }

    /** Clear all damage. */
    fun healAll() = edit("Damage cleared") { it.healAll(); true }

    /** Remove a quality (and the improvements it granted) from the open character. */
    fun removeQuality(guid: String) =
        if (open?.sheet?.isCareer == true) editCareer("Removed quality", "Not enough karma to remove that quality") { adv, _ -> adv.removeQuality(guid) }
        else edit("Removed quality") { editor -> editor.removeItem(guid) }

    /** Called when the app comes back to the front: if the open character's file was deleted meanwhile, say so now. */
    fun checkOpenFile() {
        val current = open ?: return
        viewModelScope.launch { if (!io { repo.exists(current.file.id) }) fileMissing = true }
    }

    fun dismissMissing() { fileMissing = false }

    /** The file is gone: write the character as it is now to a new file, and carry on with that one. */
    fun saveMissingAgain() {
        val current = open ?: return
        viewModelScope.launch {
            try {
                val file = io { repo.saveNew(current.character, current.file.name) }
                last.opened(file.id)
                open = current.copy(file = file)
                fileMissing = false
                message = "Saved ${current.character.summary().let { s -> s.alias.ifBlank { s.name } }.ifBlank { "the character" }} again (${file.name})"
                refresh(quiet = true)
            } catch (e: Throwable) {
                message = "Could not save it again: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** A change is not started on a character whose file is gone, so the copy in memory and the file cannot drift apart. */
    private suspend fun stillThere(file: StoredFile): Boolean {
        if (io { repo.exists(file.id) }) return true
        fileMissing = true
        return false
    }

    private fun edit(done: String?, blocked: String = "Nothing changed", change: (chummer.core.engine.CharacterEditor) -> Boolean) {
        val current = open ?: return
        viewModelScope.launch {
            if (!stillThere(current.file)) return@launch
            try {
                val (sheet, warning) = io {
                    val editor = gameData.editor(current.character)
                    if (!change(editor)) null
                    else {
                        repo.save(current.file, current.character)
                        gameData.sheet(current.character)
                    }
                } ?: run { message = blocked; return@launch }
                open = OpenCharacter(current.file, current.character, sheet, warning)
                if (done != null) message = done
            } catch (e: FileMissingException) {
                fileMissing = true
            } catch (e: Throwable) {
                message = "Could not change the character: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Like [edit], for career changes that work on the file directly rather than through the editor. */
    private fun editCareer(done: String?, blocked: String, change: (chummer.core.engine.CareerAdvancement, chummer.core.CharacterFile) -> Boolean) {
        val current = open ?: return
        viewModelScope.launch {
            if (!stillThere(current.file)) return@launch
            try {
                val result = io {
                    val log = chummer.core.engine.ExpenseLog(current.character)
                    val before = log.entries().size
                    if (!change(gameData.career(current.character), current.character)) null
                    else {
                        repo.save(current.file, current.character)
                        // A change that wrote a new, undoable entry can be taken back from the snackbar.
                        val entries = log.entries()
                        val offer = entries.lastOrNull()?.takeIf { entries.size > before && log.canUndo(it) }?.guid
                        Triple(gameData.sheet(current.character), offer, Unit)
                    }
                } ?: run { message = blocked; return@launch }
                val (sheetAndWarning, offer) = result.first to result.second
                open = OpenCharacter(current.file, current.character, sheetAndWarning.first, sheetAndWarning.second)
                undoOffer = offer
                if (done != null) message = done
            } catch (e: FileMissingException) {
                fileMissing = true
            } catch (e: Throwable) {
                message = "Could not change the character: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Spend karma on the next step of an attribute, a skill (by GUID) or a skill group, in career. */
    fun improveAttribute(name: String) = editCareer("$name improved", "Cannot improve $name") { adv, _ -> adv.improveAttribute(name) }
    fun improveSkill(guid: String) = editCareer("Skill improved", "Cannot improve this skill") { adv, _ -> adv.improveSkill(guid) }
    fun improveGroup(name: String) = editCareer("$name improved", "Cannot improve $name") { adv, _ -> adv.improveGroup(name) }

    /** A reward, a spend or a correction written by hand to the career log. */
    fun addExpense(karma: Boolean, amount: Double, reason: String) = editCareer("Entry added", "Nothing changed") { _, c ->
        val log = chummer.core.engine.ExpenseLog(c)
        val text = reason.ifBlank { if (amount >= 0) "Reward" else "Spent" }
        if (karma) log.manualKarma(amount.toInt(), text) else log.manualNuyen(amount, text)
        true
    }

    fun addSpecialization(guid: String, name: String) = editCareer("Specialization learned", "Cannot learn that specialization") { adv, _ -> adv.addSpecialization(guid, name) }

    fun gearOptions() = gameData.gearOptions()

    /** Nuyen price of gear for the open career character, or null when the price cannot be worked out. */
    fun gearCost(entryId: String, rating: Int?, qty: Double): Double? =
        open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).gearCost(entryId, rating, qty) }

    fun buyGear(entryId: String, rating: Int?, qty: Double) =
        editCareer("Gear bought", "Not enough nuyen") { adv, _ -> adv.buyGear(entryId, rating, qty) != null }

    fun armorOptions() = gameData.armorOptions()
    fun weaponOptions() = gameData.weaponOptions()
    fun vehicleOptions() = gameData.vehicleOptions()
    fun vehicleCost(id: String) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).vehicleCost(id) }
    fun buyVehicle(id: String) = editCareer("Vehicle bought", "Cannot buy that vehicle (nuyen, or over the availability limit)") { adv, _ -> adv.buyVehicle(id) != null }
    fun armorCost(id: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).armorCost(id, rating) }
    fun weaponCost(id: String) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).weaponCost(id) }
    fun buyArmor(id: String, rating: Int?) = editCareer("Armor bought", "Cannot buy that armor (nuyen, or it comes with parts that are not supported yet)") { adv, _ -> adv.buyArmor(id, rating) != null }
    fun buyWeapon(id: String) = editCareer("Weapon bought", "Cannot buy that weapon (nuyen, or it comes with accessories that are not supported yet)") { adv, _ -> adv.buyWeapon(id) != null }

    fun accessoryOptions() = gameData.accessoryOptions()
    fun setAccessoryEquipped(accessoryGuid: String, equipped: Boolean) = editCareer(null, "Cannot change that accessory") { adv, _ -> adv.setAccessoryEquipped(accessoryGuid, equipped) }
    fun setArmorEquipped(guid: String, equipped: Boolean) = editCareer(null, "Cannot change that armor") { adv, _ -> adv.setArmorEquipped(guid, equipped) }
    fun setArmorPartEquipped(guid: String, isMod: Boolean, equipped: Boolean) = editCareer(null, "Cannot change that part") { adv, _ ->
        if (isMod) adv.setArmorModEquipped(guid, equipped) else adv.setArmorGearEquipped(guid, equipped)
    }
    fun setArmorSetEquipped(setGuid: String, equipped: Boolean) = editCareer(null, "Nothing to change in that set") { adv, _ -> adv.setArmorSetEquipped(setGuid, equipped) }
    fun addArmorSet(name: String) = editCareer("Set added", "That name is empty or already used") { adv, _ -> adv.addArmorSet(name) }
    fun renameArmorSet(guid: String, name: String) = editCareer("Set renamed", "That name is empty or already used") { adv, _ -> adv.renameArmorSet(guid, name) }
    fun removeArmorSet(guid: String) = editCareer("Set removed (its armor is kept)", "Cannot remove that set") { adv, _ -> adv.removeArmorSet(guid) }
    fun moveArmorToSet(armorGuid: String, setGuid: String) = editCareer(null, "Cannot move that armor") { adv, _ -> adv.moveArmorToSet(armorGuid, setGuid) }
    /** The accessories that fit this weapon. */
    fun accessoryOptions(weaponGuid: String) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).accessoryOptions(weaponGuid) } ?: gameData.accessoryOptions()
    fun armorModOptions() = gameData.armorModOptions()
    fun accessoryCost(weaponGuid: String, id: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).accessoryCost(weaponGuid, id, rating) }
    fun armorModCost(id: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).armorModCost(id, rating) }
    fun buyAccessory(weaponGuid: String, id: String, mount: String?, rating: Int?) = editCareer("Accessory bought", "Not enough nuyen") { adv, _ -> adv.buyAccessory(weaponGuid, id, mount, rating) != null }
    fun buyArmorMod(armorGuid: String, id: String, rating: Int?) = editCareer("Mod bought", "Not enough nuyen") { adv, _ -> adv.buyArmorMod(armorGuid, id, rating) != null }

    fun armorGearOptions(room: Double) = gameData.gearOptions().filter { (it.armorCapacity ?: Double.MAX_VALUE) <= room + 1e-9 }
    fun buyArmorGear(armorGuid: String, id: String, rating: Int?) = editCareer("Gear installed", "Cannot install that (nuyen or capacity)") { adv, _ -> adv.buyArmorGear(armorGuid, id, rating) != null }

    fun wareOptions() = gameData.wareOptions()
    fun wareGrades(bioware: Boolean): List<String> = open?.let { gameData.wareGrades(bioware, it.character) }.orEmpty()
    fun wareCost(id: String, grade: String, rating: Int?, parentGuid: String? = null) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareCost(id, grade, rating, parentGuid) }
    fun wareEssence(id: String, grade: String, rating: Int?, parentGuid: String? = null) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareEssence(id, grade, rating, parentGuid) }
    /** What changing the grade and rating of the installed ware item [guid] would cost, in creation (null when nothing can be edited). */
    fun wareEditPreview(guid: String, grade: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareEditPreview(guid, grade, rating) }
    /** Give an installed ware item a new grade and rating, in creation. */
    fun editWare(guid: String, grade: String, rating: Int?) = editCareer("Updated", "Cannot change that (nuyen, essence or availability)") { adv, _ -> adv.editWare(guid, grade, rating) }
    /** Move an installed cyberware or bioware item to the next grade, paying only the difference in price. */
    fun upgradeWare(guid: String) = editCareer("Upgraded", "Cannot upgrade that (nuyen or grade)") { adv, _ -> adv.upgradeWare(guid) != null }
    fun buyWare(id: String, grade: String, rating: Int?, parentGuid: String? = null) = editCareer("Installed", "Cannot install that (nuyen, essence or capacity)") { adv, _ -> adv.buyWare(id, grade, rating, parentGuid) != null }
    fun pluginOptions(parentGuid: String) = open?.let { gameData.career(it.character).pluginOptions(parentGuid) }.orEmpty()
    /** The loose ammunition a weapon can be loaded from. */
    fun compatibleAmmo(weaponGuid: String) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).compatibleAmmo(weaponGuid) }.orEmpty()
    fun loadAmmo(weaponGuid: String, ammoGuid: String, slot: Int = 0, rounds: Int? = null) =
        editCareer("Ammo loaded", "Cannot load that (the clip is full or holds other ammunition, or the ammunition does not fit)") { adv, _ -> adv.loadAmmo(weaponGuid, ammoGuid, slot, rounds) }
    fun unloadAmmo(weaponGuid: String, slot: Int = 0) = editCareer("Ammo unloaded", "Nothing to unload") { adv, _ -> adv.unloadAmmo(weaponGuid, slot) }
    fun setActiveClip(weaponGuid: String, slot: Int) = editCareer(null, "That clip is not available") { adv, _ -> adv.setActiveClip(weaponGuid, slot) }
    fun fireRounds(weaponGuid: String, rounds: Int, slot: Int = 0) = editCareer(null, "Not enough rounds in that clip") { adv, _ -> adv.fireRounds(weaponGuid, rounds, slot) }

    /** The newest log entry that can be undone, for the menu shortcut. */
    fun lastUndoable(): chummer.core.engine.ExpenseEntry? =
        open?.sheet?.career?.let { c -> c.expenses.firstOrNull { it.guid in c.undoable } }

    /** One more karma point into nuyen (+1) or one back (-1), in creation. */
    fun changeNuyenBp(delta: Int) = edit(null, blocked = if (delta > 0) "No more karma can go into nuyen" else "No karma in nuyen to take back") { it.changeNuyenBp(delta) }

    /** Take a bought item out again, in creation (career uses undo). */
    fun removePurchase(guid: String) = editCareer("Removed", "Cannot remove that here") { adv, _ -> adv.removePurchase(guid) }

    /** Sell a bought item in career for a share of its price. */
    fun sellPurchase(guid: String, percent: Int) = editCareer("Sold", "Cannot sell that") { adv, _ -> adv.sellPurchase(guid, percent) != null }

    /** Remove an item in career with no nuyen changing hands. */
    fun discardPurchase(guid: String) = editCareer("Removed", "Cannot remove that") { adv, _ -> adv.discardPurchase(guid) }

    fun lifestyleOptions() = gameData.lifestyleOptions()
    fun buyLifestyle(id: String, months: Int) = editCareer("Lifestyle added", "Cannot add that lifestyle (not enough nuyen)") { adv, _ -> adv.buyLifestyle(id, months) != null }

    fun martialArtOptions() = gameData.martialArtOptions()
    fun techniqueOptions(artName: String) = gameData.techniqueOptions(artName)
    fun martialArtCost(id: String) = martialArtCoster()(id)
    fun martialArtCoster(): (String) -> Int? {
        val current = open ?: return { null }
        val adv = gameData.career(current.character)
        return { adv.martialArtCost(it) }
    }
    fun techniqueCost(artGuid: String) = open?.let { gameData.career(it.character).techniqueCost(artGuid) }
    fun buyMartialArt(id: String) = editCareer("Martial art learned", "Cannot learn that (karma, or already known)") { adv, _ -> adv.buyMartialArt(id) != null }
    fun buyTechnique(artGuid: String, id: String) = editCareer("Technique learned", "Cannot learn that (karma, or already known)") { adv, _ -> adv.buyTechnique(artGuid, id) != null }

    fun powerOptions() = gameData.powerOptions()
    fun addPower(entryId: String, extra: String = "") = edit("Power added", "Cannot add that power (power points, or it is already taken)") { it.powerProblem(entryId, extra) == null && run { it.addPower(entryId, 1, extra); true } }
    fun changePowerLevel(guid: String, delta: Int) = edit(null, "No more levels (power points, or the limit)") { it.changePowerLevel(guid, delta) }
    fun removePower(guid: String) = edit("Power removed") { it.removePower(guid) }

    fun undoExpense(guid: String) = editCareer("Entry undone", "That entry cannot be undone") { adv, _ -> adv.log.undo(guid) }

    /** Karma price of a quality for the open career character, or null in creation. */
    fun careerQualityCost(entryId: String): Int? = careerQualityCoster()(entryId)

    /** One career helper for a whole list of qualities: building it reads the settings, which is far too slow to do for each of hundreds of entries. */
    fun careerQualityCoster(): (String) -> Int? {
        val current = open?.takeIf { it.sheet?.isCareer == true } ?: return { null }
        val adv = gameData.career(current.character)
        return { adv.qualityCost(it) }
    }

    fun finishPlan() = open?.let { gameData.finishPlan(it.character) }

    /** Finish creation: the character becomes a career character. A backup of the creation file is kept by the save. */
    fun finishCreation(startingNuyen: Int) {
        val current = open ?: return
        viewModelScope.launch {
            if (!stillThere(current.file)) return@launch
            try {
                val (problems, sheet) = io {
                    val problems = gameData.finish(current.character, startingNuyen)
                    if (problems.isNotEmpty()) problems to null
                    else { repo.save(current.file, current.character); emptyList<String>() to gameData.sheet(current.character) }
                }
                if (sheet == null) { message = "Cannot finish: ${problems.joinToString("; ")}"; return@launch }
                open = OpenCharacter(current.file, current.character, sheet.first, sheet.second)
                message = "Character created. Career mode is on."
            } catch (e: FileMissingException) {
                fileMissing = true
            } catch (e: Throwable) {
                message = "Could not finish the character: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun qualityOptions() = gameData.qualityOptions()
    fun knowledgeOptions() = gameData.knowledgeOptions()
    fun spellOptions() = gameData.spellOptions()
    fun traditionOptions() = gameData.traditionOptions()

    fun setTradition(entryId: String) = edit("Tradition chosen") { it.setTradition(entryId); true }
    fun addSpell(entryId: String, alchemical: Boolean = false) =
        if (open?.sheet?.isCareer == true) editCareer(if (alchemical) "Learned preparation" else "Learned spell", "Not enough karma for that spell") { adv, _ -> adv.addSpell(entryId, alchemical = alchemical) != null }
        else edit(if (alchemical) "Added preparation" else "Added spell") { it.addSpell(entryId, alchemical = alchemical); true }
    fun careerSpellCost(entryId: String): Int? = careerSpellCoster()(entryId, false)
    /** The karma price of a spell in career (a preparation when the flag is true); null in creation. */
    fun careerSpellCoster(): (String, Boolean) -> Int? {
        val current = open?.takeIf { it.sheet?.isCareer == true } ?: return { _, _ -> null }
        val adv = gameData.career(current.character)
        return { id, alchemical -> adv.spellCost(id, alchemical) }
    }
    fun removeSpell(guid: String) = edit("Removed spell") { it.removeItem(guid) }

    fun complexFormOptions() = gameData.complexFormOptions()
    fun initiationCost(group: Boolean, ordeal: Boolean, schooling: Boolean) = open?.let { gameData.career(it.character).initiation().nextCost(group, ordeal, schooling) } ?: 0
    fun initiate(group: Boolean, ordeal: Boolean, schooling: Boolean) = editCareer("Grade taken", "Cannot take the next grade (limit, karma, or nuyen)") { adv, _ -> adv.initiate(group, ordeal, schooling) != null }
    fun metamagicOptions() = open?.let { gameData.career(it.character).initiation().options() }.orEmpty()
    fun addMetamagic(id: String) = editCareer("Added", "Cannot add that (karma, or its requirements)") { adv, _ -> adv.addMetamagic(id) != null }
    fun artOptions() = open?.let { gameData.career(it.character).initiation().artOptions() }.orEmpty()
    fun addArt(id: String) = editCareer("Art added", "Cannot add that art") { adv, _ -> adv.initiation().addArt(id) != null }
    fun removeArt(guid: String) = editCareer("Art removed", "Nothing changed") { adv, _ -> adv.initiation().removeArt(guid) }
    fun enhancementOptions() = open?.let { gameData.career(it.character).initiation().enhancementOptions() }.orEmpty()
    fun gradeSpellOptions(category: String) = open?.let { gameData.career(it.character).initiation().gradeSpellOptions(category) }.orEmpty()
    fun addEnhancement(id: String) = editCareer("Enhancement added", "Cannot add that (karma, or its requirements)") { adv, _ -> adv.addEnhancement(id) != null }
    fun addGradeSpell(id: String) = editCareer("Spell added", "Not enough karma") { adv, _ -> adv.addGradeSpell(id) != null }
    fun updateSprite(guid: String, name: String, level: Int, tasks: Int, registered: Boolean) = editCareer("Sprite changed", "Cannot change the sprite that way (level, or registration limit)") { adv, _ -> adv.sprites().update(guid, name, level, tasks, registered) }
    fun chooseMentor(guid: String, set: Int, choice: String) = editCareer(null, "Cannot choose that") { adv, _ -> adv.mentors().choose(guid, set, choice) }
    fun spiritOptions() = open?.let { gameData.career(it.character).spirits().options() }.orEmpty()
    fun addSpirit(entryId: String) = editCareer("Spirit added", "Cannot add that spirit (force)") { adv, _ -> adv.spirits().add(entryId, "", services = 0) != null }
    fun updateSpirit(guid: String, name: String, level: Int, tasks: Int, bound: Boolean) = editCareer("Spirit changed", "Cannot change the spirit that way (force, or bound limit)") { adv, _ -> adv.spirits().update(guid, name, level, tasks, bound) }
    fun removeSpirit(guid: String) = editCareer("Spirit removed", "Nothing changed") { adv, _ -> adv.spirits().remove(guid) }
    fun critterPowerOptions() = open?.let { gameData.career(it.character).critterPowers().options() }.orEmpty()
    fun addCritterPower(id: String, rating: Int) = if (open?.sheet?.isCareer == true) editCareer("Critter power bought", "Cannot add that (karma, rating, or already known)") { adv, _ -> adv.addCritterPower(id, rating) != null } else editCareer("Critter power added", "Cannot add that (rating, or already known)") { adv, _ -> adv.critterPowers().add(id, rating) != null }
    fun removeCritterPower(guid: String) = editCareer("Critter power removed", "Nothing changed") { adv, _ -> adv.critterPowers().remove(guid) }
    fun aiProgramOptions() = open?.let { gameData.career(it.character).aiPrograms().options() }.orEmpty()
    fun aiProgramCost(advanced: Boolean) = open?.let { gameData.career(it.character).aiProgramCost(advanced) } ?: 0
    fun addAiProgram(id: String) = editCareer("Program added", "Cannot add that (karma, or it needs another program)") { adv, _ -> if (open?.sheet?.isCareer == true) adv.addAiProgram(id) != null else adv.aiPrograms().add(id) != null }
    fun removeAiProgram(guid: String) = editCareer("Program removed", "Nothing changed") { adv, _ -> adv.aiPrograms().remove(guid) }
    fun addCreationSpec(skillGuid: String, spec: String, skillName: String) = edit("Specialization added", "Cannot specialize that skill (it needs a rating, and takes one specialization)") { it.addSpecialization(skillGuid, spec, hasRating = open?.sheet?.activeSkills?.any { s -> s.name == skillName && s.rating > 0 } == true) != null }
    fun removeCreationSpec(specGuid: String) = edit("Specialization removed") { it.removeSpecialization(specGuid) }
    fun spriteOptions() = open?.let { gameData.career(it.character).sprites().options() }.orEmpty()
    fun addSprite(entryId: String, name: String) = editCareer("Sprite added", "Cannot add that sprite (limit, or level)") { adv, _ -> adv.sprites().add(entryId, name) != null }
    fun removeSprite(guid: String) = editCareer("Sprite removed", "Nothing changed") { adv, _ -> adv.sprites().remove(guid) }
    fun addComplexForm(entryId: String, extra: String = "") =
        if (open?.sheet?.isCareer == true) editCareer("Learned complex form", "Cannot learn that complex form (karma, limit, or already known)") { adv, _ -> adv.addComplexForm(entryId, extra) != null }
        else edit("Added complex form") { it.addComplexForm(entryId, extra); true }
    fun removeComplexForm(guid: String) = edit("Removed complex form") { it.removeItem(guid) }

    /** Buy (+1) or give back (-1) a step of a skill group with karma, in creation. */
    fun changeSkillGroup(name: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "$name is at the creation maximum, or not enough karma left" else "No karma step in $name to give back") { ed -> if (delta > 0) ed.withinKarma({ ed.changeSkillGroup(name, 1) }, { ed.changeSkillGroup(name, -1) }) else ed.changeSkillGroup(name, delta) }

    /** Buy (+1, free points first, then karma) or give back (-1) a rating of a knowledge skill, in creation. */
    fun changeKnowledge(guid: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "At the creation maximum, or not enough karma left" else "Nothing to give back") { ed -> if (delta > 0) ed.withinKarma({ ed.changeKnowledgeSkill(guid, 1) }, { ed.changeKnowledgeSkill(guid, -1) }) else ed.changeKnowledgeSkill(guid, delta) }

    fun addKnowledge(entryId: String, native: Boolean) = edit("Added skill") { it.addKnowledgeSkill(entryId, native = native); true }
    fun removeKnowledge(guid: String) = edit("Removed skill") { it.removeKnowledgeSkill(guid) }

    /** The profiles and metatypes the new character dialog offers. Slow the first time (loads the game data). */
    suspend fun metatypesFor(profile: chummer.core.data.CharacterSettings): List<GameDataStore.MetatypeOption> = io { gameData.metatypesFor(profile) }

    suspend fun newCharacterOptions(): GameDataStore.NewCharacterOptions = io {
        repo.syncSideFiles(gameData.userSettingsDir, gameData.userCustomDir)
        gameData.newCharacterOptions()
    }

    fun priorityTalents(letter: String) = gameData.priorityTalents(letter)
    fun talentSkillChoices(talent: chummer.core.engine.TalentOption) = gameData.talentSkillChoices(talent)
    fun heritageOffered(letter: String, metatype: String, variant: String) = gameData.heritageOffered(letter, metatype, variant)

    suspend fun critterOptions(): List<chummer.core.engine.CritterOption> = io { gameData.newCharacterOptions(); gameData.critterOptions() }

    fun createCritter(name: String, critterId: String, force: Int) = viewModelScope.launch {
        try {
            val file = io {
                val profile = gameData.newCharacterOptions().profiles.first()
                repo.saveNew(gameData.createCritter(profile, name, critterId, force), name.ifBlank { "New critter" })
            }
            refresh()
            openFile(file)
        } catch (e: Throwable) {
            message = "Could not create the critter: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun createCharacter(profile: chummer.core.data.CharacterSettings, name: String, alias: String, metatypeId: String, variantId: String? = null, priorities: chummer.core.engine.PriorityChoice? = null) {
        viewModelScope.launch {
            try {
                val file = io {
                    val character = gameData.createCharacter(profile, name, alias, metatypeId, variantId, priorities)
                    repo.saveNew(character, (alias.ifBlank { name }).ifBlank { "New character" })
                }
                refresh()
                openFile(file)
            } catch (e: Throwable) {
                message = "Could not create the character: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun close() {
        open = null
        fileMissing = false
        last.clear()
        // The character may have changed (finished creation, a purchase, a new name): its summary on the list must follow. Only the changed file is re-read.
        refresh(quiet = true)
    }

    fun delete(file: StoredFile) {
        viewModelScope.launch {
            val ok = io { repo.delete(file.id) }
            message = if (ok) "Deleted ${file.name}" else "Could not delete ${file.name}"
            open = null
            last.clear()
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
