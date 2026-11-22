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
}
