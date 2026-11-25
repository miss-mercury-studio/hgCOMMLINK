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

/** A new A.I.: the metatype, the Depth the talent gives, and Depth shown and bought like any attribute. */
class ArtificialIntelligenceTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }

    @Test
    fun anAiHasDepthFromItsTalentAndCanRaiseIt() {
        val meta = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "A.I." }
        // Heritage C (A.I. is offered there), attributes A, talent B (4 Depth), resources D, skills E: 2+4+3+1+0 = 10.
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = meta.key), PriorityChoice("C", "A", "B", "D", "E", "A.I.", true, talentSkills = emptyList()))
        val row = c.root.child("attributes")!!.children("attribute").first { it.text("name") == "DEP" }
        assertEquals("4", row.text("metatypemin")); assertEquals("True", c.root.text("depenabled"))
        // The sheet lists Depth with the others.
        val sheet = Sheet.build(c, data, settings)
        assertEquals(4, sheet.attributes.first { it.name == "DEP" }.total)
        // One point of the special attributes, if the letters give any, raises it; otherwise karma does.
        val editor = CharacterEditor(c, data, settings = settings)
        assertTrue(editor.changeAttribute("DEP", 1))
        assertEquals(5, Sheet.build(c, data, settings).attributes.first { it.name == "DEP" }.total)
    }

    /** An A.I. recovers Core damage with Software + Depth and has no natural stun recovery (`Character.PhysicalCMNaturalRecovery`). */
    @Test
    fun anAiRecoversWithSoftwarePlusDepth() {
        val meta = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "A.I." }
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = meta.key), PriorityChoice("C", "A", "B", "D", "E", "A.I.", true, talentSkills = emptyList()))
        assertEquals("0", c.root.child("attributes")!!.children("attribute").first { it.text("name") == "BOD" }.text("metatypemax"))
        // The Core monitor is 8 + half Depth (4), and an A.I. has no stun track.
        val sheet = Sheet.build(c, data, settings)
        assertEquals(10, sheet.physicalCM)
        assertEquals(0, sheet.stunCM)
        val before = Sheet.build(c, data, settings).damage
        assertEquals(0, before.stunRecovery)
        assertEquals(0, before.physicalRecovery)   // Software cannot default
        val editor = CharacterEditor(c, data, settings = settings)
        assertTrue(editor.changeSkill("Software", 1))
        val after = Sheet.build(c, data, settings).damage
        assertEquals(1 + 4, after.physicalRecovery)   // rating 1 + Depth 4
        assertEquals(0, after.stunRecovery)
    }

    /** Desktop Chummer stalls on an A.I. that has ware, so the desktop check flags it (and flags nothing else). */
    @Test
    fun anAiWithWareIsFlaggedForDesktopChummer() {
        val meta = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "A.I." }
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = meta.key), PriorityChoice("C", "A", "B", "D", "E", "A.I.", true, talentSkills = emptyList()))
        assertTrue(DesktopCheck.problems(c).isEmpty())
        val ware = c.doc.createElement("cyberware").also { it.appendChild(c.doc.createElement("name").also { n -> n.textContent = "Datajack" }) }
        c.root.child("cyberwares")!!.appendChild(ware)
        assertEquals(1, DesktopCheck.problems(c).size)
        assertEquals(1, Sheet.build(c, data, settings).desktopProblems.size)
    }
}
