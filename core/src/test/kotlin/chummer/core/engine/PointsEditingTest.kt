package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Raising and lowering with free points first, then karma, on a Sum-to-Ten character that still has points left. */
class PointsEditingTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private fun apex() = CharacterIo.readFile(File(src, "Chummer.Tests/TestFiles/Apex Predator.chum5"))
    private fun settings(c: chummer.core.CharacterFile) =
        CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), emptyList())

    private fun skillName(row: org.w3c.dom.Element) = data[row.text("suid")!!.trim()]!!.element.child("name")!!.textContent.trim()

    @Test
    fun skillsUseFreePointsFirstThenKarma() {
        val c = apex()
        val ed = CharacterEditor(c, data, settings = settings(c))
        val before = PointsLedger.of(c, data)!!
        assertEquals(6, before.skills.left) // 36 points, 30 spent
        val row = c.root.child("newskills")!!.child("skills")!!.children("skill").first { it.text("isknowledge") != "True" && (it.text("base")?.trim()?.toIntOrNull() ?: 0) == 0 && it.text("karma")?.trim() == "0" }
        val name = skillName(row)
        repeat(6) { assertTrue(ed.changeSkill(name, 1)) }
        assertEquals("6", row.text("base"))
        assertEquals("0", row.text("karma"))
        assertEquals(0, PointsLedger.of(c, data)!!.skills.left)
        // Out of points: the next steps cost karma (the creation maximum is 6, so use another skill).
        val other = c.root.child("newskills")!!.child("skills")!!.children("skill").first { it !== row && it.text("isknowledge") != "True" && it.text("base")?.trim() == "0" && it.text("karma")?.trim() == "0" }
        assertTrue(ed.changeSkill(skillName(other), 1))
        assertEquals("1", other.text("karma"))
        assertEquals("0", other.text("base"))
        // Giving back takes karma first, then free points.
        assertTrue(ed.changeSkill(skillName(other), -1))
        assertEquals("0", other.text("karma"))
        assertFalse(ed.changeSkill(skillName(other), -1))
        assertTrue(ed.changeSkill(name, -1))
        assertEquals("5", row.text("base"))
        assertEquals(1, PointsLedger.of(c, data)!!.skills.left)
    }

    @Test
    fun attributesWithNoPointsLeftCostKarma() {
        val c = apex()
        val ed = CharacterEditor(c, data, settings = settings(c))
        assertEquals(0, PointsLedger.of(c, data)!!.attributes.left)
        val row = c.root.child("attributes")!!.children("attribute").first { it.text("name") == "BOD" }
        val base = row.text("base")
        assertTrue(ed.changeAttribute("BOD", 1))
        assertEquals(base, row.text("base"))
        assertEquals("1", row.text("karma"))
        assertTrue(ed.changeAttribute("BOD", -1))
        assertEquals("0", row.text("karma"))
        // Free a point by lowering the base; the next raise uses it.
        assertTrue(ed.changeAttribute("BOD", -1))
        assertEquals(1, PointsLedger.of(c, data)!!.attributes.left)
        assertTrue(ed.changeAttribute("BOD", 1))
        assertEquals(base, row.text("base"))
        assertEquals("0", row.text("karma"))
    }
}
