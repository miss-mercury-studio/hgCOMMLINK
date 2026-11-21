package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Builds Viper's whole sheet in the state saved in the file (Runner armor, no drugs active). */
class SheetTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")

    @Test
    fun viperSheetBuilds() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")))
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
        val pack = DataPack.load(File(src, "Chummer/data"), dirs.dirs)

        val sheet = Sheet.build(c, pack, settings)
        println(sheet.copy(activeSkills = emptyList(), knowledgeSkills = emptyList(), sections = sheet.sections.copy(gear = emptyList())))
        println("skills: ${sheet.activeSkills.size} active, ${sheet.knowledgeSkills.size} knowledge")
        assertTrue(sheet.activeSkills.size + sheet.knowledgeSkills.size > 70)
        assertTrue(sheet.attributes.none { it.name == "MAG" || it.name == "RES" })
        assertTrue(sheet.attributes.any { it.name == "BOD" })
        assertEquals(-1, sheet.armor.encumbrance)
        assertTrue(sheet.qualities.isNotEmpty())
        val s = sheet.sections
        assertEquals(15, s.weapons.size)
        assertEquals(69, s.gear.size)
        assertEquals(10, s.contacts.size)
        assertEquals(9, s.ware.size)
        assertTrue(s.armorSets.any { it.name == "Runner Armor" })
        // Chummer shows the jacket as 14: its 12 plus the Gel Packs mod.
        assertEquals("14", s.armorSets.flatMap { it.items }.first { it.name == "Armor Jacket" }.value)
    }

    @Test
    fun damageUsesStrength() {
        assertEquals("4S", SheetSections.damageText("({STR})S", 4))
        assertEquals("5P", SheetSections.damageText("({STR}+1)P", 4))
        assertEquals("9P", SheetSections.damageText("9P", 4))
        assertEquals("6S(e)", SheetSections.damageText("({STR}+2)S(e)", 4))
    }

    /** A character whose settings profile is unknown gets a built-in profile, which has fewer settings than a user one. */
    @Test
    fun viperSheetBuildsWithBuiltInSettingsOnly() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"))
        assertTrue(settings.isFallback)
        val pack = DataPack.load(File(src, "Chummer/data"))
        val sheet = Sheet.build(c, pack, settings)
        assertEquals(sheet.attributes.first { it.name == "STR" }.total * 15, sheet.liftWeight)
    }
}
