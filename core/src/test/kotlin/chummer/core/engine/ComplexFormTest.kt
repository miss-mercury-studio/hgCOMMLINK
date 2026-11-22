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

/** Complex forms: the saved layout, the free count and karma price in creation, the limit, and career purchases with undo. */
class ComplexFormTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key
    private fun form(name: String) = ComplexForms.catalog(data).first { it.name == name }.id
    private fun technomancer() = NewCharacter.createPriority(
        data, settings, NewCharacterRequest(name = "T", metatypeId = human()),
        PriorityChoice("E", "A", "B", "C", "D", "Technomancer", true, talentSkills = PriorityTable(data).let { t -> t.skillChoices(t.talents("B").first { it.value == "Technomancer" }).take(2) }),
    )

    @Test
    fun aTechnomancerStartsWithResonanceAndFreeForms() {
        val c = technomancer()
        assertEquals("True", c.root.text("resenabled"))
        val sheet = Sheet.build(c, data, settings)
        val section = sheet.complexForms!!
        assertEquals(PriorityTable(data).talents("B").first { it.value == "Technomancer" }.complexForms, section.free)
        assertEquals(section.free, c.root.text("cfplimit")?.toInt())
        assertTrue(section.limit >= section.free)
    }

    @Test
    fun aFormIsSavedAsChummerSavesIt() {
        val c = technomancer()
        val guid = CharacterEditor(c, data, settings = settings).addComplexForm(form("Cleaner"))
        val f = c.root.child("complexforms")!!.children("complexform").single()
        assertEquals(listOf("guid", "name", "target", "duration", "fv", "extra", "source", "page", "notes", "grade"), f.children().map { it.tagName })
        assertEquals(guid, f.text("guid")); assertEquals("Persona", f.text("target")); assertEquals("0", f.text("grade"))
        assertTrue(CharacterEditor(c, data, settings = settings).removeItem(guid))
        assertTrue(c.root.child("complexforms")!!.children().isEmpty())
    }

    @Test
    fun formsPastTheFreeCountCostKarma() {
        val c = technomancer()
        val free = c.root.text("cfplimit")!!.toInt()
        val editor = CharacterEditor(c, data, settings = settings)
        val ids = ComplexForms.catalog(data).filter { !it.needsChoice }.map { it.id }
        val karma0 = KarmaLedger.of(c, settings, data).remaining
        repeat(free) { editor.addComplexForm(ids[it]) }
        assertEquals(karma0, KarmaLedger.of(c, settings, data).remaining)
        editor.addComplexForm(ids[free])
        assertEquals(karma0 - 4, KarmaLedger.of(c, settings, data).remaining)
    }

    @Test
    fun careerPurchasesCostKarmaAndUndoGivesItBack() {
        val c = technomancer()
        assertTrue(CharacterFinisher.finish(c, settings, data, startingNuyen = 0).isEmpty())
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(50, "Reward")
        val start = adv.log.karma
        assertEquals(4, adv.complexFormCost())
        val g = adv.addComplexForm(form("Cleaner"))
        assertNotNull(g)
        assertEquals(start - 4, adv.log.karma)
        assertNull(adv.addComplexForm(form("Cleaner"))) // already known
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(start, adv.log.karma)
        assertTrue(c.root.child("complexforms")!!.children().isEmpty())
        // Not enough karma: nothing changes.
        adv.log.manualKarma(-adv.log.karma, "Spend all")
        assertNull(adv.addComplexForm(form("Cleaner")))
    }
}
