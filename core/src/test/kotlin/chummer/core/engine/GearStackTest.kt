package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ammunition bought twice is one stack (undo takes back only the last purchase), and identical stacks can be merged. */
class GearStackTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private fun ammoId(c: CharacterFile) = data.byId.entries.first { (id, e) ->
        e.file == "gear.xml" && e.element.tagName == "gear" && e.element.child("category")?.textContent?.trim() == "Ammunition" &&
            CareerAdvancement(c, data, settings).gearCost(id, null, 1.0) != null && (e.element.child("rating")?.textContent?.trim() ?: "0") == "0"
    }.key
    private fun stacks(c: CharacterFile, name: String) = c.root.child("gears")!!.children("gear").filter { it.text("name")?.trim() == name }

    @Test
    fun buyingAmmoTwiceMakesOneStackAndUndoTakesBackOnePurchase() {
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val id = ammoId(c)
        val name = data[id]!!.element.child("name")!!.textContent.trim()
        val before = stacks(c, name).size
        val first = a.buyGear(id, null, 10.0)!!
        val second = a.buyGear(id, null, 5.0)!!
        assertEquals("the second purchase joins the first stack", first, second)
        assertEquals(before + 1, stacks(c, name).size)
        assertEquals(15.0, GearStacks.quantity(stacks(c, name).first { it.text("guid")?.trim() == first }), 0.0)
        assertTrue(a.log.undo(a.log.entries().last().guid))
        assertEquals(10.0, GearStacks.quantity(stacks(c, name).first { it.text("guid")?.trim() == first }), 0.0)
        assertTrue(a.log.undo(a.log.entries().last().guid))
        assertEquals(before, stacks(c, name).size)
    }

    @Test
    fun identicalStacksMergeIntoOne() {
        val c = viper()
        val ed = GearEditor(c, data, settings)
        val id = ammoId(c)
        val name = data[id]!!.element.child("name")!!.textContent.trim()
        val before = stacks(c, name).size
        val a = ed.addGear(id, null, 10.0)
        ed.addGear(id, null, 20.0)
        ed.addGear(id, null, 5.0)
        assertEquals(before + 3, stacks(c, name).size)
        assertTrue("the sheet offers a merge", GearStacks.twins(c.root.child("gears")!!.children("gear"), ed.stack(a)!!).size == 2)
        assertTrue(ed.mergeStacks(a))
        val left = stacks(c, name)
        assertEquals(before + 1, left.size)
        assertEquals(35.0, GearStacks.quantity(left.first { it.text("guid")?.trim() == a }), 0.0)
        assertFalse("nothing left to merge", ed.mergeStacks(a))
    }
}
