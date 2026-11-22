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

/** A new Sum-to-Ten character: what the letters set up, and the checks on the letters. */
class NewPriorityCharacterTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key

    @Test
    fun lettersAreChecked() {
        assertNull(PriorityChoice("E", "A", "B", "C", "D", "Magician", true).problem()) // 0+4+3+2+1
        assertNotNull(PriorityChoice("A", "A", "A", "A", "A", "Mundane", true).problem())
        assertNull(PriorityChoice("A", "B", "C", "D", "E", "Mundane", false).problem())
        assertNotNull(PriorityChoice("A", "A", "C", "D", "E", "Mundane", false).problem())
    }

    @Test
    fun aSumToTenMagicianStartsWithWhatTheLettersGive() {
        val choice = PriorityChoice("E", "A", "B", "C", "D", "Magician", true)
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "Test", metatypeId = human()), choice)
        val root = c.root
        assertEquals("SumtoTen", root.text("buildmethod"))
        assertEquals("E,0", root.text("prioritymetatype")); assertEquals("A,4", root.text("priorityattributes")); assertEquals("B,3", root.text("priorityspecial"))
        assertEquals("Magician", root.text("prioritytalent"))
        // Human at E: 1 special attribute point; attributes A: 24; skills C: 28 and 2 groups; resources D: 50,000.
        val ledger = PointsLedger.of(c, data)!!
        assertEquals(Pool(24, 0), ledger.attributes)
        assertEquals(Pool(1, 0), ledger.special)
        assertEquals(Pool(28, 0), ledger.skills)
        assertEquals(Pool(2, 0), ledger.groups)
        assertEquals("50000", root.text("startingnuyen"))
        assertEquals("10", root.text("sumtoten"))
        // The talent: the Magician quality (free, from the metatype source) and Magic as the attribute's minimum.
        val q = root.child("qualities")!!.children("quality").first { it.text("name") == "Magician" }
        assertEquals("Metatype", q.text("qualitysource"))
        assertEquals("True", root.text("magician"))
        val mag = root.child("attributes")!!.children("attribute").first { it.text("name") == "MAG" }
        val talent = PriorityTable(data).talents("B").first { it.value == "Magician" }
        assertEquals(talent.magic.toString(), mag.text("metatypemin"))
        // The free quality costs no karma: the budget is untouched.
        val karma = KarmaLedger.of(c, settings, data)
        assertEquals(25, karma.budget)
        assertEquals(25, karma.remaining)
        assertEquals("25", root.text("karma"))
    }

    @Test
    fun aLetterThatDoesNotOfferTheMetatypeIsRefused() {
        val troll = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Troll" }.key
        val failed = runCatching { NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = troll), PriorityChoice("E", "A", "B", "C", "D", "Magician", true)) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun theTalentGivesFreeSkillsAndSpells() {
        val table = PriorityTable(data)
        val talent = table.talents("B").first { it.value == "Magician" }
        assertEquals(2, talent.pickCount); assertEquals(4, talent.pickValue)
        val choices = table.skillChoices(talent)
        assertTrue(choices.toString(), "Spellcasting" in choices && "Counterspelling" in choices && "Pistols" !in choices)
        val choice = PriorityChoice("E", "A", "B", "C", "D", "Magician", true, talentSkills = listOf("Spellcasting", "Counterspelling"))
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), choice)
        // Chummer keeps the free ratings as improvements from the Heritage source, and the free spell count as `spelllimit`.
        val free = c.root.child("improvements")!!.children("improvement").filter { it.text("improvementsource") == "Heritage" }
        assertEquals(setOf("Spellcasting", "Counterspelling"), free.map { it.text("improvedname") }.toSet())
        assertTrue(free.all { it.text("improvementttype") == "SkillBase" && it.text("val") == "4" })
        assertEquals("7", c.root.text("spelllimit"))
        // Seven spells are free; the eighth costs karma.
        val editor = CharacterEditor(c, data, settings = settings)
        val spells = data.byId.entries.filter { it.value.file == "spells.xml" && it.value.element.tagName == "spell" }.map { it.key }
        repeat(7) { editor.addSpell(spells[it]) }
        assertEquals(25, KarmaLedger.of(c, settings, data).remaining)
        editor.addSpell(spells[7])
        assertEquals(20, KarmaLedger.of(c, settings, data).remaining)
        // Skills the talent does not offer, or too many, are refused.
        assertTrue(runCatching { NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), PriorityChoice("E", "A", "B", "C", "D", "Magician", true, talentSkills = listOf("Pistols"))) }.isFailure)
        assertTrue(runCatching { NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), PriorityChoice("E", "A", "B", "C", "D", "Magician", true, talentSkills = listOf("Spellcasting", "Counterspelling", "Assensing"))) }.isFailure)
    }

    @Test
    fun anAspectedMagicianPicksASkillGroup() {
        val table = PriorityTable(data)
        val talent = table.talents("B").first { it.value == "Aspected Magician" }
        assertTrue(talent.picksGroups)
        assertEquals(1, talent.pickCount)
        val groups = table.skillChoices(talent)
        assertTrue(groups.toString(), groups.isNotEmpty())
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), PriorityChoice("E", "A", "B", "C", "D", "Aspected Magician", true, talentSkills = listOf(groups.first())))
        val imp = c.root.child("improvements")!!.children("improvement").single { it.text("improvementsource") == "Heritage" }
        assertEquals("SkillGroupBase", imp.text("improvementttype"))
        assertEquals(groups.first(), imp.text("improvedname"))
    }

    @Test
    fun aSumToTenCharacterCanBeFinished() {
        // Heritage D (1), attributes A (4), skills B (3), resources C (2), talent E (0): ten in all.
        val choice = PriorityChoice("D", "A", "E", "B", "C", "Mundane", true)
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), choice)
        val editor = CharacterEditor(c, data, settings = settings)
        editor.changeAttribute("BOD", 1)
        // Unspent points are only warnings; nothing stops finishing.
        val review = CreationReview.of(c, settings, data)
        assertTrue(review.problems.toString(), review.canFinish)
        assertTrue(review.warnings.any { it.contains("attribute points unspent") })
        val plan = CharacterFinisher.plan(c, settings, data)
        assertEquals(7, plan.karmaKept) // 25 karma, only the carry-over stays
        assertEquals(18, plan.karmaLost)
        assertEquals(200, plan.nuyenKept) // the carry-over of the resources budget
        assertTrue(CharacterFinisher.finish(c, settings, data, startingNuyen = 80).isEmpty())
        assertEquals("True", c.root.text("created"))
        assertEquals("7", c.root.text("karma"))
        assertEquals("280", c.root.text("nuyen"))
        // After finishing, the sheet is a career sheet and the points no longer matter.
        val sheet = Sheet.build(c, data, settings)
        assertTrue(sheet.isCareer)
        assertEquals(null, sheet.points)
    }
}
