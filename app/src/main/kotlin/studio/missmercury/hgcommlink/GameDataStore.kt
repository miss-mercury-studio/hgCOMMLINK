package studio.missmercury.hgcommlink

import android.content.Context
import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataDir
import chummer.core.data.CustomDataLocator
import chummer.core.child
import chummer.core.children
import chummer.core.engine.CharacterEditor
import chummer.core.engine.NewCharacter
import chummer.core.engine.NewCharacterRequest
import chummer.core.engine.QualityCatalog
import chummer.core.engine.QualityOption
import chummer.core.engine.Sheet
import chummer.core.parseXml
import chummer.core.text
import java.io.File

/**
 * Chummer's game data and custom data, then loaded and kept for the life of the process. The loaded pack is rebuilt when the
 * character's custom data choice, or the files in it, change.
 *
 * Where the data comes from depends on the edition: the GitHub edition unpacks it from the APK's assets on first run (and after each
 * app update); the Play edition has none in the APK and uses the copy the player added from their own Chummer folder
 * ([GameDataImporter]), which an app update never touches.
 */
class GameDataStore(private val context: Context) {
    private var pack: DataPack? = null
    private var packKey: String? = null

    private fun unpack(assetDir: String, target: String): File {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.filesDir, target)
        val marker = File(dir, ".unpacked-$stamp")
        if (!marker.exists()) {
            dir.deleteRecursively()
            copyAssets(assetDir, dir)
            marker.writeText("ok")
        }
        return dir
    }

    private fun copyAssets(assetPath: String, target: File) {
        val names = context.assets.list(assetPath).orEmpty()
        if (names.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { i -> target.outputStream().use { i.copyTo(it) } }
        } else {
            target.mkdirs()
            for (n in names) copyAssets("$assetPath/$n", File(target, n))
        }
    }

    /** Whether Chummer's game data is on the device: always in the GitHub edition, once the player has added it in the Play edition. */
    fun hasData(): Boolean = BuildConfig.BUNDLED_GAME_DATA || GameDataImporter.installed(context)

    /** A folder of game data: unpacked from the APK, or (Play edition) the copy the player added. */
    private fun gameDir(assetDir: String, target: String): File =
        if (BuildConfig.BUNDLED_GAME_DATA) unpack(assetDir, target) else File(context.filesDir, target)

    /** Add the game data from the Chummer folder the player picked (Play edition), and drop everything built from the old data. */
    fun importFrom(tree: android.net.Uri): String {
        val summary = GameDataImporter.import(context, tree)
        synchronized(this) { pack = null; packKey = null }
        synchronized(catalogs) { catalogs.clear() }
        synchronized(settingsCache) { settingsCache.clear() }
        translationsCache = null
        File(context.filesDir, "amended-cache").deleteRecursively()
        return summary
    }

    private fun dataDir() = gameDir("chummer-data", "chummer-data")

    /** Chummer's texts by key (for example the English text of a limit condition), read from the language file. A missing file gives none. */
    @Volatile private var translationsCache: Map<String, String>? = null
    private val translations: Map<String, String>
        get() = translationsCache ?: run {
            val file = File(gameDir("chummer-lang", "chummer-lang"), "en-us.xml")
            if (!file.isFile) return emptyMap()
            parseXml(file).documentElement.child("strings")?.children("string").orEmpty()
                .associate { (it.child("key")?.textContent ?: "").trim() to (it.child("text")?.textContent ?: "").trim() }
                .also { translationsCache = it }
        }

    private val catalogs = HashMap<String, Any>()

    /**
     * A list of everything the game data offers (all qualities, all gear...), built once per data pack. Building one takes tens of milliseconds
     * on a desktop and several times that on a phone, which is too long to spend while a picker opens on the main thread.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> catalog(name: String, build: (DataPack) -> T): T {
        synchronized(catalogs) { catalogs[name]?.let { return it as T } }
        // Without loaded data there is nothing to offer yet (the same empty list the pickers always got).
        val made = build(synchronized(this) { pack } ?: return emptyList<Any>() as T)
        synchronized(catalogs) { catalogs[name] = made as Any }
        return made
    }

    /** Build the big lists on a background thread as soon as the data is loaded, so the first picker opens at once too. */
    private fun warm() {
        val p = pack ?: return
        Thread {
            runCatching {
                catalog("quality", QualityCatalog::list); catalog("spell", chummer.core.engine.SpellCatalog::list); catalog("gear", chummer.core.engine.GearCatalog::list)
                catalog("ware", chummer.core.engine.WareCatalog::list); catalog("weapon", chummer.core.engine.ArmorWeaponCatalog::weapons)
                catalog("knowledge", chummer.core.engine.KnowledgeCatalog::list)
            }
            android.util.Log.i("hgcommlink", "catalogs ready for ${p.byId.size} entries")
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Qualities that can be added, as far as the current game data goes. Needs the data loaded (open a character first). */
    /** Knowledge skills and languages that can be added. */
    fun knowledgeOptions(): List<chummer.core.engine.KnowledgeOption> =
        catalog("knowledge", chummer.core.engine.KnowledgeCatalog::list)

    fun complexFormOptions(): List<chummer.core.engine.ComplexFormOption> =
        catalog("complexform", chummer.core.engine.ComplexForms::catalog)

    fun spellOptions(): List<chummer.core.engine.SpellOption> =
        catalog("spell", chummer.core.engine.SpellCatalog::list)

    fun traditionOptions(): List<chummer.core.engine.TraditionOption> =
        catalog("tradition", chummer.core.engine.TraditionCatalog::list)

    fun armorOptions(): List<chummer.core.engine.ItemOption> = catalog("armor", chummer.core.engine.ArmorWeaponCatalog::armor)
    fun weaponOptions(): List<chummer.core.engine.ItemOption> = catalog("weapon", chummer.core.engine.ArmorWeaponCatalog::weapons)
    fun vehicleOptions(): List<chummer.core.engine.ItemOption> = catalog("vehicle", chummer.core.engine.VehicleCatalog::vehicles)

    fun accessoryOptions(): List<chummer.core.engine.ItemOption> = catalog("accessory", chummer.core.engine.ArmorWeaponCatalog::accessories)
    fun armorModOptions(): List<chummer.core.engine.ItemOption> = catalog("armormod", chummer.core.engine.ArmorWeaponCatalog::armorMods)

    fun wareOptions(): List<chummer.core.engine.WareOption> = catalog("ware", chummer.core.engine.WareCatalog::list)
    fun wareGrades(bioware: Boolean, c: CharacterFile): List<String> = synchronized(this) { pack?.let { chummer.core.engine.WareCatalog.gradeNames(it, bioware, settingsFor(c).bannedWareGrades) }.orEmpty() }

    fun pluginOptions(parentSourceId: String): List<chummer.core.engine.WareOption> = synchronized(this) { pack?.let { chummer.core.engine.WareCatalog.pluginsFor(it, parentSourceId) }.orEmpty() }

    fun powerOptions(): List<chummer.core.engine.PowerOption> = catalog("power", chummer.core.engine.PowerCatalog::list)

    fun gearOptions(): List<chummer.core.engine.GearOption> =
        catalog("gear", chummer.core.engine.GearCatalog::list)

    fun qualityOptions(): List<QualityOption> = catalog("quality", QualityCatalog::list)

    fun finishPlan(c: CharacterFile) =
        chummer.core.engine.CharacterFinisher.plan(c, settingsFor(c), synchronized(this) { pack ?: error("Game data is not loaded yet") })

    /** Move a character from creation to career; returns what stops it, or an empty list when done. */
    fun finish(c: CharacterFile, startingNuyen: Int) =
        chummer.core.engine.CharacterFinisher.finish(c, settingsFor(c), synchronized(this) { pack ?: error("Game data is not loaded yet") }, startingNuyen)

    fun editor(c: CharacterFile) =
        CharacterEditor(c, synchronized(this) { pack ?: error("Game data is not loaded yet") }, translations::get, settingsFor(c))

    /** The career karma and nuyen tools for a finished character. */
    fun career(c: CharacterFile) = chummer.core.engine.CareerAdvancement(c, synchronized(this) { pack ?: error("Game data is not loaded yet") }, settingsFor(c))
    private fun chummerCustomDir() = gameDir("chummer-customdata", "chummer-customdata")

    /** Settings profiles copied from the user's folder (see CharacterRepository.syncSideFiles). */
    val userSettingsDir: File get() = File(context.filesDir, "user-settings")

    /**
     * Profiles that ship with the app, for characters that name one Chummer does not ship (older versions called theirs `default.xml`). They are
     * looked up after the user's own files, so a profile of the same name in the characters folder wins, and they are not offered for new characters.
     */
    private val bundledSettingsDir: File get() = unpack("chummer-profiles", "chummer-profiles")

    /** Custom data files copied from the user's folder. */
    val userCustomDir: File get() = File(context.filesDir, "user-customdata")

    private val settingsCache = HashMap<String, Pair<Long, CharacterSettings>>()

    /**
     * The character's settings profile; `isFallback` is set when it is not available on this device. Reading the profiles parses the whole
     * settings file, so the result is kept until a settings file changes (screens ask for it many times, some on the main thread).
     */
    fun settingsFor(c: CharacterFile): CharacterSettings {
        val ref = c.root.text("settings").orEmpty()
        val builtIn = File(dataDir(), "settings.xml")
        val bundled = bundledSettingsDir
        val stamp = builtIn.lastModified() + builtIn.length() + (userSettingsDir.listFiles()?.sumOf { it.lastModified() + it.length() } ?: 0L) + (bundled.listFiles()?.sumOf { it.lastModified() + it.length() } ?: 0L)
        synchronized(settingsCache) {
            settingsCache[ref]?.takeIf { it.first == stamp }?.let { return it.second }
        }
        val found = CharacterSettings.find(ref, builtIn, listOf(userSettingsDir, bundled))
        synchronized(settingsCache) { settingsCache[ref] = stamp to found }
        return found
    }

    /** A choice in the new character dialog. */
    data class MetatypeOption(val id: String, val name: String, val variants: List<Pair<String, String>>)
    data class NewCharacterOptions(val profiles: List<CharacterSettings>, val metatypes: List<MetatypeOption>)

    /** The base metatypes a settings profile offers: its custom data may hide some (a `<hide />` marker), so the list follows the chosen profile. */
    fun metatypesFor(profile: CharacterSettings): List<MetatypeOption> {
        val resolved = CustomDataLocator(listOf(chummerCustomDir(), userCustomDir)).resolve(profile.customData)
        return packFor(resolved.dirs).byId.values
            .filter { it.file == "metatypes.xml" && it.element.tagName == "metatype" && it.element.child("hide") == null }
            .map { e ->
                MetatypeOption(
                    e.element.child("id")?.textContent?.trim().orEmpty(), e.element.child("name")?.textContent?.trim().orEmpty(),
                    e.element.child("metavariants")?.children("metavariant").orEmpty().filter { it.child("hide") == null }.map { v ->
                        (v.child("id")?.textContent?.trim().orEmpty()) to (v.child("name")?.textContent?.trim().orEmpty())
                    },
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Karma-build profiles and the base metatypes to offer when starting a character. Loads the game data. */
    fun newCharacterOptions(): NewCharacterOptions {
        val profiles = CharacterSettings.available(File(dataDir(), "settings.xml"), listOf(userSettingsDir)).filter { it.buildMethod in setOf("Karma", "Priority", "SumtoTen") }
        val resolved = CustomDataLocator(listOf(chummerCustomDir(), userCustomDir)).resolve(profiles.firstOrNull()?.customData.orEmpty())
        val metatypes = packFor(resolved.dirs).byId.values
            // A `<hide />` marker (set by custom data such as a campaign's house rules) keeps a metatype out of the creation list.
            .filter { it.file == "metatypes.xml" && it.element.tagName == "metatype" && it.element.child("hide") == null }
            .map { e ->
                MetatypeOption(
                    e.element.child("id")?.textContent?.trim().orEmpty(), e.element.child("name")?.textContent?.trim().orEmpty(),
                    e.element.child("metavariants")?.children("metavariant").orEmpty().filter { it.child("hide") == null }.map { v ->
                        (v.child("id")?.textContent?.trim().orEmpty()) to (v.child("name")?.textContent?.trim().orEmpty())
                    },
                )
            }
            .sortedBy { it.name.lowercase() }
        return NewCharacterOptions(profiles, metatypes)
    }

    /** Create a new Karma-build character with the chosen profile (and the custom data that profile enables). */
    fun createCharacter(profile: CharacterSettings, name: String, alias: String, metatypeId: String, variantId: String? = null, priorities: chummer.core.engine.PriorityChoice? = null): CharacterFile {
        val resolved = CustomDataLocator(listOf(chummerCustomDir(), userCustomDir)).resolve(profile.customData)
        val request = NewCharacterRequest(name = name, alias = alias, metatypeId = metatypeId, metavariantId = variantId, customDataNames = resolved.dirs.map { it.name })
        val pack = packFor(resolved.dirs)
        return if (priorities != null) NewCharacter.createPriority(pack, profile, request, priorities) else NewCharacter.createKarma(pack, profile, request)
    }

    /** The talents a priority letter offers (value and name), and whether a metatype is offered at a heritage letter. */
    fun critterOptions(): List<chummer.core.engine.CritterOption> = catalog("critter", chummer.core.engine.CritterCatalog::list)
    fun createCritter(profile: CharacterSettings, name: String, critterId: String, force: Int): CharacterFile {
        val resolved = CustomDataLocator(listOf(chummerCustomDir(), userCustomDir)).resolve(profile.customData)
        val request = NewCharacterRequest(name = name, metatypeId = critterId, force = force, customDataNames = resolved.dirs.map { it.name })
        return NewCharacter.createKarma(packFor(resolved.dirs), profile, request)
    }
    fun priorityTalents(letter: String): List<chummer.core.engine.TalentOption> = synchronized(this) { pack?.let { chummer.core.engine.PriorityTable(it).talents(letter) }.orEmpty() }
    fun talentSkillChoices(talent: chummer.core.engine.TalentOption): List<String> = synchronized(this) { pack?.let { chummer.core.engine.PriorityTable(it).skillChoices(talent) }.orEmpty() }
    fun martialArtOptions(): List<chummer.core.engine.MartialArtOption> = catalog("martial", chummer.core.engine.MartialArtCatalog::arts)
    fun techniqueOptions(artName: String): List<chummer.core.engine.TechniqueOption> = synchronized(this) {
        val p = pack ?: return@synchronized emptyList()
        chummer.core.engine.MartialArtCatalog.arts(p).firstOrNull { it.name == artName }?.let { chummer.core.engine.MartialArtCatalog.techniquesFor(p, it.id) }.orEmpty()
    }
    fun heritageOffered(letter: String, metatype: String, variant: String): Boolean = synchronized(this) { pack?.let { chummer.core.engine.PriorityTable(it).heritage(letter, metatype, variant) != null } ?: true }

    private fun keyOf(dirs: List<CustomDataDir>): String = dirs.joinToString("|") { d ->
        d.guid + ":" + d.dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.joinToString(",") { "${it.name}/${it.length()}/${it.lastModified()}" }
    }

    @Synchronized
    private fun packFor(dirs: List<CustomDataDir>): DataPack {
        val key = keyOf(dirs)
        return pack?.takeIf { key == packKey } ?: DataPack.load(dataDir(), dirs, File(context.filesDir, "amended-cache")).also { pack = it; packKey = key; synchronized(catalogs) { catalogs.clear() }; warm() }
    }

    /** Build the sheet, plus a warning to show when its numbers may differ from Chummer's (null when none). */
    fun sheet(c: CharacterFile): Pair<Sheet, String?> {
        var t = System.nanoTime()
        fun lap(what: String) { val n = System.nanoTime(); android.util.Log.i("hgcommlink", "  $what: ${(n - t) / 1_000_000} ms"); t = n }
        val settings = settingsFor(c)
        lap("settings")
        val resolved = CustomDataLocator(listOf(chummerCustomDir(), userCustomDir)).resolve(settings.customData)
        lap("locate custom data (${resolved.dirs.size} folders)")
        val warning = when {
            settings.isFallback -> "Settings profile not on this device: default rules used, numbers may differ. " +
                "Put the profile's .xml in your characters folder."
            resolved.missing.isNotEmpty() ->
                "${resolved.missing.size} custom data folder(s) from this profile were not found, so items they change may show " +
                    "Chummer's original values. Put their files (including manifest.xml) in your characters folder."
            else -> null
        }
        val data = packFor(resolved.dirs)
        lap("data pack")
        return (Sheet.build(c, data, settings) to warning).also { lap("sheet") }
    }
}
