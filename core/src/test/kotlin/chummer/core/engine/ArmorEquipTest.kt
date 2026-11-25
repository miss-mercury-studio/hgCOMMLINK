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

/** Wearing and taking off armor, and armor sets, on Viper's file. */
class ArmorEquipTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }

    private fun armorImprovements(c: CharacterFile, guid: String) =
        c.root.child("improvements")!!.children("improvement").filter { it.text("improvementsource")?.trim() == "Armor" && it.text("sourcename")?.trim() == guid }

    @Test
    fun takingArmorOffAndOnSwitchesItsImprovements() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        // An armor that has improvements of its own (the one with the Intimidation limit modifier).
        val withImps = c.armors().first { armorImprovements(c, it.guid).isNotEmpty() }
        assertTrue(ed.setArmorEquipped(withImps.guid, false))
        assertFalse(c.armors().first { it.guid == withImps.guid }.equipped)
        assertTrue(armorImprovements(c, withImps.guid).all { it.text("enabled")?.trim() == "0" })
        assertTrue(ed.setArmorEquipped(withImps.guid, true))
        assertTrue(c.armors().first { it.guid == withImps.guid }.equipped)
        assertTrue(armorImprovements(c, withImps.guid).all { it.text("enabled")?.trim() == "1" })
        assertFalse(ed.setArmorEquipped("no-such-armor", true))
    }

    @Test
    fun wearingOneArmorLeavesTheOthers() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val before = c.armors().associate { it.guid to it.equipped }
        val target = c.armors().first { !it.equipped }
        ed.setArmorEquipped(target.guid, true)
        for (a in c.armors()) assertEquals(a.name, if (a.guid == target.guid) true else before[a.guid], a.equipped)
    }

    @Test
    fun aModCanBeSwitchedOffAndOn() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val mod = c.armors().first { it.mods.isNotEmpty() }.mods.first()
        assertTrue(ed.setArmorModEquipped(mod.guid, false))
        assertFalse(c.armors().flatMap { it.mods }.first { it.guid == mod.guid }.equipped)
        assertTrue(ed.setArmorModEquipped(mod.guid, true))
        assertTrue(c.armors().flatMap { it.mods }.first { it.guid == mod.guid }.equipped)
        assertFalse(ed.setArmorModEquipped("nothing", true))
    }

    @Test
    fun setsCanBeMadeFilledWornAndDeleted() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val a = c.armors()
        val first = a[0].guid
        val second = a[1].guid
        assertNull(ed.addArmorSet("  "))
        val set = ed.addArmorSet("Night Out")
        assertNotNull(set)
        assertNull("a name already used is refused", ed.addArmorSet("night out"))
        assertEquals("Night Out", c.armorLocations()[set])
        assertTrue(ed.moveArmorToSet(first, set!!))
        assertTrue(ed.moveArmorToSet(second, set))
        assertFalse(ed.moveArmorToSet(first, "no-such-set"))
        // Take the whole set off, then put it back on; armor outside the set stays as it was.
        val outside = c.armors().filter { it.location != set }.associate { it.guid to it.equipped }
        ed.setArmorSetEquipped(set, false)
        assertTrue(c.armors().filter { it.location == set }.none { it.equipped })
        ed.setArmorSetEquipped(set, true)
        assertTrue(c.armors().filter { it.location == set }.all { it.equipped })
        for (x in c.armors().filter { it.location != set }) assertEquals(outside[x.guid], x.equipped)
        // The sheet shows the set.
        assertTrue(ed.renameArmorSet(set, "Social"))
        assertEquals("Social", c.armorLocations()[set])
        // Deleting the set keeps its armor, in no set.
        val count = c.armors().size
        assertTrue(ed.removeArmorSet(set))
        assertEquals(count, c.armors().size)
        assertTrue(c.armors().none { it.location == set })
        assertFalse(c.armorLocations().containsKey(set))
    }
}
