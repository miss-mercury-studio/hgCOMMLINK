package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gear that takes plug-ins: what may go in, buying one into the parent, and taking it back. */
class GearPluginTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private fun gearNamed(c: CharacterFile, name: String) = c.root.child("gears")!!.children("gear").first { it.text("name")?.trim() == name }

    @Test
    fun aFakeSinTakesIdItemsOnly() {
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val sin = gearNamed(c, "Fake SIN").text("guid")!!.trim()
        val options = a.gearPluginOptions(sin)
        println("Fake SIN takes ${options.size} plug-ins, e.g. ${options.take(4).map { it.name }}")
        assertTrue(options.isNotEmpty())
        val allowed = GearPlugins.categories(data[gearNamed(c, "Fake SIN").text("sourceid")!!.trim()]!!.element).toSet()
        assertTrue("only the categories the SIN lists", options.all { it.category in allowed })
    }

    @Test
    fun gearWithNoSlotsTakesNothing() {
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        assertTrue(a.gearPluginOptions(gearNamed(c, "Medkit").text("guid")!!.trim()).isEmpty())
        assertTrue(a.gearPluginOptions("no-such-item").isEmpty())
        assertNull(a.buyGearPlugin(gearNamed(c, "Medkit").text("guid")!!.trim(), data.byId.keys.first()))
    }

    @Test
    fun aPlugInIsBoughtIntoItsParentAndUndoTakesItOut() {
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val sin = gearNamed(c, "Fake SIN")
        val parent = sin.text("guid")!!.trim()
        val pick = a.gearPluginOptions(parent).first { it.cost != null && it.unsupported.isEmpty() && it.maxRating == 0 && a.gearCost(it.id) != null }
        val before = sin.child("children")!!.children("gear").size
        val guid = a.buyGearPlugin(parent, pick.id, null)
        assertNotNull(guid)
        assertEquals(before + 1, sin.child("children")!!.children("gear").size)
        assertEquals(parent, sin.child("children")!!.children("gear").first { it.text("guid")?.trim() == guid }.text("parentid")?.trim())
        assertTrue(a.log.undo(a.log.entries().last().guid))
        assertEquals(before, sin.child("children")!!.children("gear").size)
        assertFalse(a.gearPluginOptions(parent).isEmpty())
    }
}
