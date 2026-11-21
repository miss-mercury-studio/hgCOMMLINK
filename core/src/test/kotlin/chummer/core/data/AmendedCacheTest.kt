package chummer.core.data

import chummer.core.DataPack
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The on-disk cache of amended data files must give exactly the same data as amending from scratch. */
class AmendedCacheTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")

    @Test
    fun cachedLoadEqualsFreshLoad() {
        val settings = CharacterSettings.find(
            VIPER_SETTINGS, File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")),
        )
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData).dirs
        assertTrue(dirs.size >= 7)
        val cache = File(System.getProperty("java.io.tmpdir"), "amended-cache-test-${System.nanoTime()}")
        try {
            val fresh = DataPack.load(File(src, "Chummer/data"), dirs)
            val firstWithCache = DataPack.load(File(src, "Chummer/data"), dirs, cache)
            val written = cache.listFiles().orEmpty().size
            assertTrue("nothing was cached", written > 0)
            val t0 = System.nanoTime()
            val second = DataPack.load(File(src, "Chummer/data"), dirs, cache)
            println("cached load: ${(System.nanoTime() - t0) / 1_000_000} ms, $written cached files")
            assertEquals(written, cache.listFiles().orEmpty().size)
            for (pack in listOf(firstWithCache, second)) {
                assertEquals(fresh.byId.size, pack.byId.size)
                for ((id, e) in fresh.byId) {
                    val other = pack.byId[id] ?: error("missing $id")
                    assertEquals(id, e.file, other.file)
                    assertEquals(id, e.element.textContent, other.element.textContent)
                }
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    companion object {
        // The settings GUID Viper's file refers to (see ViperCampaignTest).
        private val VIPER_SETTINGS: String by lazy {
            chummer.core.CharacterIo.readFile(File(System.getProperty("chummer.reference"), "Viper_career.chum5")).root.text("settings").orEmpty()
        }
    }
}
