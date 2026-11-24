package studio.missmercury.hgcommlink

import android.content.Context
import chummer.core.data.GameDataVersion
import chummer.core.data.SupportedData
import chummer.core.data.SupportedDataSet
import java.io.File

/**
 * What the app knows about its game data without loading it: the list of data sets it is tested on (chummer-supported.txt, copied into
 * the APK at build time) and the version of the data now on the device, saved so crash reports can name it.
 */
object GameDataInfo {
    @Volatile private var list: List<SupportedDataSet>? = null

    fun supported(context: Context): List<SupportedDataSet> = list ?: runCatching {
        SupportedData.parse(context.assets.open("chummer-supported.txt").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList()).also { list = it }

    private fun file(context: Context) = File(context.filesDir, "game-data-version.txt")

    /** The version last saved, for places that must not load or hash the data (a crash report). Null if none was saved yet. */
    fun saved(context: Context): GameDataVersion? = file(context).takeIf { it.isFile }?.readText()?.let(GameDataVersion::fromText)

    fun save(context: Context, version: GameDataVersion) {
        runCatching { file(context).writeText(version.toText()) }
    }

    fun forget(context: Context) {
        file(context).delete()
    }
}
