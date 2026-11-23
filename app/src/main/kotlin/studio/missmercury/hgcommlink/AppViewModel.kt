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

    fun armorGearOptions(room: Double) = gameData.gearOptions().filter { (it.armorCapacity ?: Double.MAX_VALUE) <= room + 1e-9 }
    fun buyArmorGear(armorGuid: String, id: String, rating: Int?) = editCareer("Gear installed", "Cannot install that (nuyen or capacity)") { adv, _ -> adv.buyArmorGear(armorGuid, id, rating) != null }

    fun wareOptions() = gameData.wareOptions()
    fun wareGrades(bioware: Boolean): List<String> = open?.let { gameData.wareGrades(bioware, it.character) }.orEmpty()
    fun wareCost(id: String, grade: String, rating: Int?, parentGuid: String? = null) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareCost(id, grade, rating, parentGuid) }
    fun wareEssence(id: String, grade: String, rating: Int?, parentGuid: String? = null) = open?.takeIf { it.sheet != null }?.let { gameData.career(it.character).wareEssence(id, grade, rating, parentGuid) }
    fun buyWare(id: String, grade: String, rating: Int?, parentGuid: String? = null) = editCareer("Installed", "Cannot install that (nuyen, essence or capacity)") { adv, _ -> adv.buyWare(id, grade, rating, parentGuid) != null }
    fun pluginOptions(parentGuid: String) = open?.let { gameData.career(it.character).pluginOptions(parentGuid) }.orEmpty()
    fun loadAmmo(weaponGuid: String, ammoGuid: String) = editCareer("Ammo loaded", "Cannot load that (no empty clip, or wrong ammunition)") { adv, _ -> adv.loadAmmo(weaponGuid, ammoGuid) }

    fun unloadAmmo(weaponGuid: String) = editCareer("Ammo unloaded", "Nothing to unload") { adv, _ -> adv.unloadAmmo(weaponGuid) }

    /** The newest log entry that can be undone, for the menu shortcut. */
    fun lastUndoable(): chummer.core.engine.ExpenseEntry? =
        open?.sheet?.career?.let { c -> c.expenses.firstOrNull { it.guid in c.undoable } }

    /** One more karma point into nuyen (+1) or one back (-1), in creation. */
    fun changeNuyenBp(delta: Int) = edit(null, blocked = if (delta > 0) "No more karma can go into nuyen" else "No karma in nuyen to take back") { it.changeNuyenBp(delta) }

    /** Take a bought item out again, in creation (career uses undo). */
    fun removePurchase(guid: String) = editCareer("Removed", "Cannot remove that here") { adv, _ -> adv.removePurchase(guid) }

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
    fun careerSpellCost(entryId: String): Int? = careerSpellCoster()(entryId)
    fun careerSpellCoster(): (String) -> Int? {
        val current = open?.takeIf { it.sheet?.isCareer == true } ?: return { null }
        val adv = gameData.career(current.character)
        return { adv.spellCost(it) }
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
