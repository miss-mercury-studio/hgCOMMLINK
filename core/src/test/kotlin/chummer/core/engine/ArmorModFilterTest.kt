package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The armor mod picker offers only what Chummer's does: not hidden mods, and only mods that suit the armor. */
class ArmorModFilterTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private fun armorEntry(name: String) = data.byId.values.first { it.file == "armor.xml" && it.element.tagName == "armor" && it.name == name }.element

    @Test
    fun hiddenModsAreNeverOffered() {
        val all = ArmorWeaponCatalog.armorMods(data).map { it.name }
        assertFalse(all.any { it == "Custom Fit" || it == "Custom Fit (Stack)" })
        val jacket = ArmorWeaponCatalog.armorModsFor(data, armorEntry("Armor Jacket"), emptyList()).map { it.name }
        assertFalse(jacket.any { it.startsWith("Custom Fit") })
    }

    @Test
    fun modsMadeForOneArmorAreOfferedOnlyThere() {
        val jacket = ArmorWeaponCatalog.armorModsFor(data, armorEntry("Armor Jacket"), emptyList())
        val names = jacket.map { it.name }
        println("Armor Jacket is offered ${names.size} of ${ArmorWeaponCatalog.armorMods(data).size} mods")
        assertTrue("general mods stay", names.any { it == "Gel Packs" })
        assertFalse("a mod that needs Full Body Armor", names.contains("Full Body Armor: Helmet"))
        assertTrue("every offered mod is General or the armor's own", jacket.all { it.category == "General" || it.category == "Armor Jacket" })
        val full = ArmorWeaponCatalog.armorModsFor(data, armorEntry("Full Body Armor"), emptyList()).map { it.name }
        assertTrue(full.contains("Full Body Armor: Helmet"))
        assertEquals(false, full.any { it.startsWith("Custom Fit") })
    }
}
