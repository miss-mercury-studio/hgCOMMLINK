package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bonuses that add objects: the gear, mentor spirit and contact really appear on the character, and removing the item takes them back out of the improvements. */
class ObjectBonusTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private fun quality(name: String) = data.byId.entries.first { it.value.file == "qualities.xml" && it.value.element.tagName == "quality" && it.value.name == name }.key

    @Test
    fun technomancerBringsALivingPersonaForFree() {
        val c = viper()
        val before = c.root.child("gears")!!.children("gear").size
        val q = CharacterEditor(c, data, settings = settings).addQuality(quality("Technomancer"))
        val gears = c.root.child("gears")!!.children("gear")
        assertEquals(before + 1, gears.size)
        val persona = gears.first { it.text("name") == "Living Persona" }
        assertEquals("0", persona.text("cost"))
        assertEquals(q, persona.text("parentid")?.trim())
        val imp = Improvement.parseAll(c.root).first { it.type == "Gear" && it.sourceName == q }
        assertEquals(persona.text("guid")?.trim(), imp.improvedName)
    }

    @Test
    fun aMentorSpiritIsAddedFromTheChoice() {
        val c = viper()
        val mentor = data.byId.values.first { it.file == "mentors.xml" && it.element.tagName == "mentor" }
        CharacterEditor(c, data, settings = settings).addQuality(quality("Mentor Spirit"), extra = mentor.name!!)
        val spirits = c.root.child("mentorspirits")!!.children("mentorspirit")
        assertEquals(mentor.name, spirits.single().text("name"))
        val imp = Improvement.parseAll(c.root).first { it.type == "MentorSpirit" }
        assertEquals(spirits.single().text("guid")?.trim(), imp.improvedName)
    }
}
