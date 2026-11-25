package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Adding lifestyles: the catalog, the price on the ledger, career purchase with undo, and a purchase that cannot be paid for. */
class LifestyleEditorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy {
        CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")))
    }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private fun option(name: String) = LifestyleCatalog.options(data).first { it.name == name }

    @Test
    fun theCatalogOffersMonthlyLifestylesOnly() {
        val names = LifestyleCatalog.options(data).map { it.name }
        assertTrue("Medium" in names && "Street" in names)
        assertFalse(names.any { it.startsWith("ID ERROR") || it.startsWith("Hospitalized") })
    }

    @Test
    fun careerPurchaseCostsNuyenAndUndoGivesItBack() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(100000.0, "Test reward")
        val start = adv.log.nuyen
        val before = c.root.child("lifestyles")?.children("lifestyle").orEmpty().size
        val guid = adv.buyLifestyle(option("Medium").id, 2)!!
        val ls = LifestyleEditor(c, data).lifestyle(guid)!!
        assertEquals("Medium", ls.text("baselifestyle")); assertEquals("2", ls.text("months"))
        val paid = start - adv.log.nuyen
        assertTrue(paid >= 10000.0 * 0.5)
        assertEquals(LifestyleEditor(c, data).total(guid), paid, 0.01)
        val entry = adv.log.entries().first { it.objectId == guid }
        assertTrue(adv.log.canUndo(entry))
        assertTrue(adv.log.undo(entry.guid))
        assertNull(LifestyleEditor(c, data).lifestyle(guid))
        assertEquals(before, c.root.child("lifestyles")?.children("lifestyle").orEmpty().size)
        assertEquals(start, adv.log.nuyen, 0.01)
    }

    @Test
    fun aLifestyleThatCannotBePaidForIsNotAdded() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val have = adv.log.nuyen
        val before = c.root.child("lifestyles")?.children("lifestyle").orEmpty().size
        assertNull(adv.buyLifestyle(option("Luxury").id, 120))
        assertEquals(before, c.root.child("lifestyles")?.children("lifestyle").orEmpty().size)
        assertEquals(have, adv.log.nuyen, 0.01)
        assertNotNull(adv.buyLifestyle(option("Street").id, 1))
    }
}
