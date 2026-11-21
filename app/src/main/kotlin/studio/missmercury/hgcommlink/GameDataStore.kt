package studio.missmercury.hgcommlink

import android.content.Context
import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataDir
import chummer.core.data.CustomDataLocator
import chummer.core.engine.Sheet
import chummer.core.text
import java.io.File

/**
 * Chummer's game data and custom data: unpacked from the APK assets on first run (and after each app update),
 * then loaded and kept for the life of the process. The loaded pack is rebuilt when the character's custom data
 * choice, or the files in it, change.
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

    private fun dataDir() = unpack("chummer-data", "chummer-data")
    private fun chummerCustomDir() = unpack("chummer-customdata", "chummer-customdata")

    /** Settings profiles copied from the user's folder (see CharacterRepository.syncSideFiles). */
    val userSettingsDir: File get() = File(context.filesDir, "user-settings")

    /** Custom data files copied from the user's folder. */
    val userCustomDir: File get() = File(context.filesDir, "user-customdata")

    /** The character's settings profile; `isFallback` is set when it is not available on this device. */
    fun settingsFor(c: CharacterFile): CharacterSettings =
        CharacterSettings.find(c.root.text("settings").orEmpty(), File(dataDir(), "settings.xml"), listOf(userSettingsDir))

    private fun keyOf(dirs: List<CustomDataDir>): String = dirs.joinToString("|") { d ->
        d.guid + ":" + d.dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.joinToString(",") { "${it.name}/${it.length()}/${it.lastModified()}" }
    }

    @Synchronized
    private fun packFor(dirs: List<CustomDataDir>): DataPack {
        val key = keyOf(dirs)
        return pack?.takeIf { key == packKey } ?: DataPack.load(dataDir(), dirs, File(context.filesDir, "amended-cache")).also { pack = it; packKey = key }
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
