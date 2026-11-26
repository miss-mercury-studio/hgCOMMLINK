package chummer.core

import chummer.core.data.CharacterSettings
import chummer.core.engine.RuleSettings
import java.io.File

/**
 * The characters the golden and round-trip tests run over: Chummer's own test files, the Viper reference character,
 * and any characters from bug reports or testers kept outside the repo (see chummer.submitted in gradle.properties).
 *
 * The submitted folder is searched recursively for `.chum5` files. A `settings` folder in it can hold those players'
 * settings files (`.xml`), for characters built with settings that are not Chummer's defaults.
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

    fun characters(): List<File> = upstream() + File(reference, "Viper_career.chum5") + submitted()

    /**
     * The rules a character was built under. For the upstream and reference characters an unknown profile quietly falls back to
     * Chummer's first built-in one, as before. For a submitted character that would hide the real problem behind a bogus mismatch,
     * so it fails and says which settings file to add.
     */
    fun ruleSettings(file: File, character: CharacterFile): RuleSettings {
        val name = character.root.text("settings").orEmpty()
        val found = CharacterSettings.find(name, File(src, "Chummer/data/settings.xml"), settingsDirs())
        // Old Chummer versions named the built-in profile "default.xml"; falling back for that name is Chummer's own behavior.
        val legacy = name.isBlank() || name.trim().equals("default.xml", ignoreCase = true)
        check(!(found.isFallback && !legacy && submittedDir != null && file.startsWith(submittedDir))) {
            "${file.name}: its settings profile \"$name\" was not found. Put the player's settings .xml in ${File(submittedDir, "settings")}."
        }
        return found.toRuleSettings()
    }

    /** Folders to look in for a character's settings file, besides Chummer's own. */
    fun settingsDirs(): List<File> =
        listOf(File(reference, "Viper_career/settings")) + listOfNotNull(submittedDir?.let { File(it, "settings") }?.takeIf { it.isDirectory })
}
