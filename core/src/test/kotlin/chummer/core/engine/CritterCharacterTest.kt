package chummer.core.engine

import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A new critter: its attributes follow its Force, its powers are free, its skills are free levels. */
class CritterCharacterTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun critter(name: String) = data.byId.entries.first { it.value.file == "critters.xml" && it.value.element.tagName == "metatype" && it.value.name == name }.key

    @Test
    fun aSpiritOfAirAtForceFiveHasItsFormulasWorkedOut() {
        val c = NewCharacter.createKarma(data, settings, NewCharacterRequest(name = "Zephyr", metatypeId = critter("Spirit of Air"), force = 5))
        fun row(n: String) = c.root.child("attributes")!!.children("attribute").first { it.text("name") == n }
        // BOD F-2 = 3, AGI F+3 = 8, REA F+4 = 9, STR F-3 = 2, the mental ones F = 5, EDG F/2 = 2.5 rounded to 3.
        assertEquals("3", row("BOD").text("metatypemin")); assertEquals("8", row("AGI").text("metatypemin")); assertEquals("9", row("REA").text("metatypemin"))
        assertEquals("2", row("STR").text("metatypemin")); assertEquals("5", row("WIL").text("metatypemin")); assertEquals("5", row("MAG").text("metatypemin"))
        assertEquals("True", c.root.text("critter")); assertEquals("True", c.root.text("created")); assertEquals("Spirits", c.root.text("metatypecategory"))
        // Its powers cost nothing and cannot be removed.
        val powers = c.root.child("critterpowers")!!.children("critterpower")
        assertTrue(powers.map { it.text("name") }.toString(), "Astral Form" in powers.map { it.text("name") })
        assertTrue(powers.all { it.text("counttowardslimit") == "False" })
        // Its skills are free levels equal to Force.
        val sheet = Sheet.build(c, data, settings)
        assertEquals(5, sheet.activeSkills.first { it.name == "Perception" }.rating)
        assertEquals(8, sheet.attributes.first { it.name == "AGI" }.total)
        assertTrue(sheet.isCareer)
    }
}
