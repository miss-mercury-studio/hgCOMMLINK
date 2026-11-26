package chummer.core

import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import java.io.File

/**
 * The characters the golden and round-trip tests run over: Chummer's own test files, the Viper reference character,
 * and any characters from bug reports or testers kept outside the repo (see chummer.submitted in gradle.properties).
 *
 * Each submitted character lives in its own folder, with what it needs beside it, because two players can use settings
 * profiles or custom data folders with the same name and different contents:
 *
 *     <submitted>/<case>/<character>.chum5
 *     <submitted>/<case>/settings/<profile>.xml          the player's settings profile, if not one of Chummer's built-in ones
 *     <submitted>/<case>/customdata/<folder>/manifest.xml  and the folder's files, for each custom data folder the profile enables
 *
 * For a submitted character a missing profile or custom data folder fails the test and says what to add. For the upstream
 * and reference characters nothing changes: an unknown profile falls back to Chummer's first built-in one.
 */
object Fixtures {
    private val src get() = File(System.getProperty("chummer.src"))
    private val reference get() = File(System.getProperty("chummer.reference"))

    private val submittedDir: File? =
        System.getProperty("chummer.submitted")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    fun upstream(): List<File> =
        File(src, "Chummer.Tests/TestFiles").listFiles { f -> f.extension == "chum5" }.orEmpty().sortedBy { it.name }

    fun submitted(): List<File> =
        submittedDir?.walkTopDown()?.filter { it.isFile && it.extension == "chum5" }?.sortedBy { it.path }?.toList().orEmpty()

    fun characters(): List<File> = upstream() + listOf(File(reference, "Viper_career.chum5")).filter { it.isFile } + submitted()

    private fun isSubmitted(file: File) = submittedDir != null && file.startsWith(submittedDir)

    /** The folder a submitted character sits in: its settings and custom data are beside it. */
    private fun caseDir(file: File): File = file.parentFile

    /**
     * The settings profile a character was built under. For a submitted character an unknown profile would hide the real problem
     * behind a bogus mismatch, so it fails and says where to put the file.
     */
    fun settings(file: File, character: CharacterFile): CharacterSettings {
        val name = character.root.text("settings").orEmpty()
        val dirs = listOf(File(reference, "Viper_career/settings")) + if (isSubmitted(file)) listOf(File(caseDir(file), "settings")) else emptyList()
        val found = CharacterSettings.find(name, File(src, "Chummer/data/settings.xml"), dirs)
        // Old Chummer versions named the built-in profile "default.xml"; falling back for that name is Chummer's own behavior.
        val legacy = name.isBlank() || name.trim().equals("default.xml", ignoreCase = true)
        check(!(found.isFallback && !legacy && isSubmitted(file))) {
            "${file.name}: its settings profile \"$name\" was not found. Put the player's settings .xml in ${File(caseDir(file), "settings")}."
        }
        return found
    }

    private val basePack by lazy { DataPack.load(File(src, "Chummer/data")) }

    /**
     * The game data for a character. The upstream and reference characters use Chummer's data as is. A submitted character also gets
     * the custom data folders its settings enable, from Chummer's own `customdata` and from its folder's `customdata`.
     */
    fun pack(file: File, settings: CharacterSettings): DataPack {
        if (!isSubmitted(file)) return basePack
        val own = File(caseDir(file), "customdata")
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"), own)).resolve(settings.customData)
        check(resolved.missing.isEmpty()) {
            "${file.name}: custom data its settings enable was not found (${resolved.missing.joinToString { it.guid }}). " +
                "Put each folder, with its manifest.xml, in $own."
        }
        return if (resolved.dirs.isEmpty()) basePack else DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
}
