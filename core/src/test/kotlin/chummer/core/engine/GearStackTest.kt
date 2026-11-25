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

    @Test
    fun ammoInsideAnotherItemStacksToo() {
        val c = viper()
        val ed = GearEditor(c, data, settings)
        val id = ammoId(c)
        val holder = c.root.child("gears")!!.children("gear").first { it.text("name")?.trim() == "Medkit" }
        val container = holder.child("children")!!
        val first = ed.addOrStack(id, null, 10.0, into = container, parentId = holder.text("guid")!!.trim())
        val second = ed.addOrStack(id, null, 5.0, into = container, parentId = holder.text("guid")!!.trim())
        assertEquals(first, second)
        assertEquals(1, container.children("gear").size)
        assertEquals(15.0, GearStacks.quantity(ed.anywhere(first)!!), 0.0)
    }

    @Test
    fun aPlugInCanBeSwitchedOffAndOn() {
        val c = viper()
        val ed = GearEditor(c, data, settings)
        val drug = c.root.child("gears")!!.children("gear").first { it.text("name")?.trim() == "Novacoke" }
        val part = drug.child("children")!!.children("gear").first().text("guid")!!.trim()
        assertTrue(ed.setEquipped(part, false))
        assertEquals("False", ed.anywhere(part)!!.text("equipped")?.trim())
        assertTrue(ed.setEquipped(part, true))
        assertEquals("True", ed.anywhere(part)!!.text("equipped")?.trim())
        assertFalse(ed.setEquipped("nothing", true))
    }

    @Test
    fun ammunitionIsBoughtForAKindOfWeapon() {
        val options = GearCatalog.list(data)
        val regular = options.first { it.name == "Ammo: Regular Ammo" }
        assertTrue(regular.ammoCategories.containsAll(listOf("Heavy Pistols", "Assault Rifles", "Submachine Guns")))
        assertFalse("not melee weapons", regular.ammoCategories.contains("Blades"))
        assertTrue("an item that is not ammunition needs no choice", options.first { it.name == "Medkit" }.ammoCategories.isEmpty())
        // Bought for a weapon type, it joins Viper's stack for that type and not the others.
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val before = stacks(c, "Ammo: Regular Ammo").size
        val sms = stacks(c, "Ammo: Regular Ammo").first { it.text("extra")?.trim() == "Submachine Guns" }
        val had = GearStacks.quantity(sms)
        val guid = a.buyGear(regular.id, null, 5.0, "Submachine Guns")!!
        assertEquals(before, stacks(c, "Ammo: Regular Ammo").size)
        assertEquals(sms.text("guid")?.trim(), guid)
        assertEquals(had + 5.0, GearStacks.quantity(sms), 0.0)
    }

    @Test
    fun aRefusedPurchaseSaysWhy() {
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val regular = GearCatalog.list(data).first { it.name == "Ammo: Regular Ammo" }
        assertEquals(null, a.gearBlockReason(regular.id, null, 1.0))
        assertEquals("Not enough nuyen", a.gearBlockReason(regular.id, null, 1_000_000.0))
        assertEquals("The price of that item cannot be worked out yet", a.gearBlockReason("no-such-item", null, 1.0))
    }
}
