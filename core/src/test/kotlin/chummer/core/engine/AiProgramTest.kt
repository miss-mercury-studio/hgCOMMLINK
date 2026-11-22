package chummer.core.engine

import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A.I. programs: layout, requirements, creation karma past the free limits, career purchases with undo. */
class AiProgramTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun ai(): chummer.core.CharacterFile {
        val meta = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "A.I." }
        val t = PriorityTable(data).talents("B").first { it.value == "A.I." }
        return NewCharacter.createPriority(
            data, settings, NewCharacterRequest(name = "T", metatypeId = meta.key),
            PriorityChoice("C", "A", "B", "D", "E", "A.I.", true, talentSkills = PriorityTable(data).skillChoices(t).take(t.pickCount)),
        )
    }

    @Test
    fun aProgramIsSavedAndAnAdvancedOneNeedsItsRequirement() {
        val c = ai()
        val ed = AiProgramEditor(c, data)
        val options = ed.options()
        val plain = options.first { !it.advanced && it.needs.isEmpty() }
        val needy = options.first { it.advanced && it.needs.isNotEmpty() }
        assertNull(ed.add(needy.id))
        val guid = ed.add(plain.id)!!
        val p = c.root.child("aiprograms")!!.children("aiprogram").single()
        assertEquals(listOf("sourceid", "guid", "name", "candelete", "isadvancedprogram", "requiresprogram", "extra", "source", "page", "notes", "notesColor"), p.children().map { it.tagName })
        assertEquals(guid, p.text("guid")); assertEquals("False", p.text("isadvancedprogram"))
        assertNull(ed.add(plain.id)) // not twice
        assertTrue(Sheet.build(c, data, settings).aiPrograms!!.any { it.guid == guid })
        assertTrue(ed.remove(guid))
        assertTrue(ed.programs().isEmpty())
    }

    @Test
    fun creationKarmaCountsProgramsPastTheFreeLimits() {
        val c = ai()
        val ed = AiProgramEditor(c, data)
        val normals = ed.options().filter { !it.advanced && it.needs.isEmpty() }
        val karma0 = KarmaLedger.of(c, settings, data).remaining
        ed.add(normals[0].id); ed.add(normals[1].id)
        // No free programs in the data's talent, so each costs the program price (5).
        assertEquals(karma0 - 10, KarmaLedger.of(c, settings, data).remaining)
        // Free programs from a limit are covered first.
        c.root.child("ainormalprogramlimit")?.textContent = "1"
        assertEquals(karma0 - 5, KarmaLedger.of(c, settings, data).remaining)
    }

    @Test
    fun careerPurchasesCostKarmaAndUndoGivesItBack() {
        val c = ai()
        assertTrue(CharacterFinisher.finish(c, settings, data, startingNuyen = 0).isEmpty())
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(60, "Reward")
        val start = adv.log.karma
        val normal = adv.aiPrograms().options().first { !it.advanced && it.needs.isEmpty() }
        assertNotNull(adv.addAiProgram(normal.id))
        assertEquals(start - 5, adv.log.karma)
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(start, adv.log.karma)
        assertTrue(adv.aiPrograms().programs().isEmpty())
        adv.log.manualKarma(-adv.log.karma, "Spend all")
        assertNull(adv.addAiProgram(normal.id))
    }
}
