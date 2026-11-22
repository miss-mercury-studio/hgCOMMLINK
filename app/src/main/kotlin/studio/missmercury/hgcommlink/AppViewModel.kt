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

    /** The log entry the last career change wrote, when it can be undone: the snackbar offers UNDO for it. */
    var undoOffer by mutableStateOf<String?>(null)

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
                message = "Could not open ${file.name}: ${e.message}"
            }
        }
    }

    /** Add a quality from the game data to the open character, save the file and recalculate the sheet. */
    fun addQuality(entryId: String, extra: String = "") {
        val current = open ?: return
        viewModelScope.launch {
            val problem = if (current.sheet?.isCareer == true) null else try { io { gameData.editor(current.character).qualityLimitProblem(entryId) } } catch (e: Throwable) { null }
            if (current.sheet?.isCareer == true) editCareer("Added quality", "Not enough karma for that quality") { adv, _ -> adv.addQuality(entryId, extra) != null }
            else if (problem != null) message = problem
            else edit("Added quality") { editor -> editor.addQuality(entryId, extra); true }
        }
    }

    /** Buy (+1) or give back (-1) a point of an attribute with karma, in creation. */
    fun changeAttribute(name: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "$name is at its maximum" else "No karma point in $name to give back") { it.changeAttribute(name, delta) }

    /** Buy (+1) or give back (-1) a rating point of an active skill with karma, in creation. */
    fun changeSkill(name: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "$name is at the creation maximum" else "No karma point in $name to give back") { it.changeSkill(name, delta) }

    /** Remove a quality (and the improvements it granted) from the open character. */
    fun removeQuality(guid: String) =
        if (open?.sheet?.isCareer == true) editCareer("Removed quality", "Not enough karma to remove that quality") { adv, _ -> adv.removeQuality(guid) }
        else edit("Removed quality") { editor -> editor.removeItem(guid) }

    private fun edit(done: String?, blocked: String = "Nothing changed", change: (chummer.core.engine.CharacterEditor) -> Boolean) {
        val current = open ?: return
        viewModelScope.launch {
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
            } catch (e: Throwable) {
                message = "Could not change the character: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Like [edit], for career changes that work on the file directly rather than through the editor. */
    private fun editCareer(done: String?, blocked: String, change: (chummer.core.engine.CareerAdvancement, chummer.core.CharacterFile) -> Boolean) {
        val current = open ?: return
        viewModelScope.launch {
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
    fun armorCost(id: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).armorCost(id, rating) }
    fun weaponCost(id: String) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).weaponCost(id) }
    fun buyArmor(id: String, rating: Int?) = editCareer("Armor bought", "Cannot buy that armor (nuyen, or it comes with parts that are not supported yet)") { adv, _ -> adv.buyArmor(id, rating) != null }
    fun buyWeapon(id: String) = editCareer("Weapon bought", "Cannot buy that weapon (nuyen, or it comes with accessories that are not supported yet)") { adv, _ -> adv.buyWeapon(id) != null }

    fun accessoryOptions() = gameData.accessoryOptions()
    fun armorModOptions() = gameData.armorModOptions()
    fun accessoryCost(weaponGuid: String, id: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).accessoryCost(weaponGuid, id, rating) }
    fun armorModCost(id: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).armorModCost(id, rating) }
    fun buyAccessory(weaponGuid: String, id: String, mount: String?, rating: Int?) = editCareer("Accessory bought", "Not enough nuyen") { adv, _ -> adv.buyAccessory(weaponGuid, id, mount, rating) != null }
    fun buyArmorMod(armorGuid: String, id: String, rating: Int?) = editCareer("Mod bought", "Not enough nuyen") { adv, _ -> adv.buyArmorMod(armorGuid, id, rating) != null }

    fun wareOptions() = gameData.wareOptions()
    fun wareGrades(bioware: Boolean): List<String> = open?.let { gameData.wareGrades(bioware, it.character) }.orEmpty()
    fun wareCost(id: String, grade: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareCost(id, grade, rating) }
    fun wareEssence(id: String, grade: String, rating: Int?) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareEssence(id, grade, rating) }
    fun buyWare(id: String, grade: String, rating: Int?, parentGuid: String? = null) = editCareer("Installed", "Cannot install that (nuyen, essence or capacity)") { adv, _ -> adv.buyWare(id, grade, rating, parentGuid) != null }
    fun pluginOptions(parentSourceId: String) = gameData.pluginOptions(parentSourceId)
    fun loadAmmo(weaponGuid: String, ammoGuid: String) = editCareer("Ammo loaded", "Cannot load that (no empty clip, or wrong ammunition)") { adv, _ -> adv.loadAmmo(weaponGuid, ammoGuid) }

    /** The newest log entry that can be undone, for the menu shortcut. */
    fun lastUndoable(): chummer.core.engine.ExpenseEntry? =
        open?.sheet?.career?.let { c -> c.expenses.firstOrNull { it.guid in c.undoable } }

    /** One more karma point into nuyen (+1) or one back (-1), in creation. */
    fun changeNuyenBp(delta: Int) = edit(null, blocked = if (delta > 0) "No more karma can go into nuyen" else "No karma in nuyen to take back") { it.changeNuyenBp(delta) }

    /** Take a bought item out again, in creation (career uses undo). */
    fun removePurchase(guid: String) = editCareer("Removed", "Cannot remove that here") { adv, _ -> adv.removePurchase(guid) }

    fun undoExpense(guid: String) = editCareer("Entry undone", "That entry cannot be undone") { adv, _ -> adv.log.undo(guid) }

    /** Karma price of a quality for the open career character, or null in creation. */
    fun careerQualityCost(entryId: String): Int? = open?.takeIf { it.sheet?.isCareer == true }?.let { gameData.career(it.character).qualityCost(entryId) }

    fun finishPlan() = open?.let { gameData.finishPlan(it.character) }

    /** Finish creation: the character becomes a career character. A backup of the creation file is kept by the save. */
    fun finishCreation(startingNuyen: Int) {
        val current = open ?: return
        viewModelScope.launch {
            try {
                val (problems, sheet) = io {
                    val problems = gameData.finish(current.character, startingNuyen)
                    if (problems.isNotEmpty()) problems to null
                    else { repo.save(current.file, current.character); emptyList<String>() to gameData.sheet(current.character) }
                }
                if (sheet == null) { message = "Cannot finish: ${problems.joinToString("; ")}"; return@launch }
                open = OpenCharacter(current.file, current.character, sheet.first, sheet.second)
                message = "Character created. Career mode is on."
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
    fun addSpell(entryId: String) =
        if (open?.sheet?.isCareer == true) editCareer("Learned spell", "Not enough karma for that spell") { adv, _ -> adv.addSpell(entryId) != null }
        else edit("Added spell") { it.addSpell(entryId); true }
    fun careerSpellCost(entryId: String): Int? = open?.takeIf { it.sheet?.isCareer == true }?.let { gameData.career(it.character).spellCost(entryId) }
    fun removeSpell(guid: String) = edit("Removed spell") { it.removeItem(guid) }

    /** Buy (+1) or give back (-1) a step of a skill group with karma, in creation. */
    fun changeSkillGroup(name: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "$name is at the creation maximum" else "No karma step in $name to give back") { it.changeSkillGroup(name, delta) }

    /** Buy (+1, free points first, then karma) or give back (-1) a rating of a knowledge skill, in creation. */
    fun changeKnowledge(guid: String, delta: Int) =
        edit(null, blocked = if (delta > 0) "At the creation maximum" else "Nothing to give back") { it.changeKnowledgeSkill(guid, delta) }

    fun addKnowledge(entryId: String, native: Boolean) = edit("Added skill") { it.addKnowledgeSkill(entryId, native = native); true }
    fun removeKnowledge(guid: String) = edit("Removed skill") { it.removeKnowledgeSkill(guid) }

    /** The profiles and metatypes the new character dialog offers. Slow the first time (loads the game data). */
    suspend fun newCharacterOptions(): GameDataStore.NewCharacterOptions = io {
        repo.syncSideFiles(gameData.userSettingsDir, gameData.userCustomDir)
        gameData.newCharacterOptions()
    }

    fun createCharacter(profile: chummer.core.data.CharacterSettings, name: String, alias: String, metatypeId: String, variantId: String? = null) {
        viewModelScope.launch {
            try {
                val file = io {
                    val character = gameData.createCharacter(profile, name, alias, metatypeId, variantId)
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
