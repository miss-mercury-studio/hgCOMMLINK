package studio.missmercury.hgcommlink

import android.content.Context
import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.engine.Sheet
import chummer.core.text
import java.io.File

/**
 * Chummer's game data: unpacked from the APK assets on first run (and after each app update), then loaded
 * once and kept for the life of the process.
 */
class GameDataStore(private val context: Context) {
    @Volatile
    private var pack: DataPack? = null

    private fun dataDir(): File {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.filesDir, "chummer-data")
        val marker = File(dir, ".unpacked-$stamp")
        if (!marker.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            for (name in context.assets.list("chummer-data").orEmpty()) {
                context.assets.open("chummer-data/$name").use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
            }
            marker.writeText("ok")
        }
        return dir
    }

    @Synchronized
    fun pack(): DataPack = pack ?: DataPack.load(dataDir()).also { pack = it }

    /** The character's settings profile; `isFallback` is set when it is not available on this device. */
    fun settingsFor(c: CharacterFile): CharacterSettings =
        CharacterSettings.find(c.root.text("settings").orEmpty(), File(dataDir(), "settings.xml"))

    /** Build the sheet. The flag says whether the character's own settings profile was used. */
    fun sheet(c: CharacterFile): Pair<Sheet, Boolean> {
        val settings = settingsFor(c)
        return Sheet.build(c, pack(), settings) to !settings.isFallback
    }
}
