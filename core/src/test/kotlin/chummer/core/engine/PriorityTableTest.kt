package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The priority tables against what Chummer stored in the Sum-to-Ten and Priority fixtures. */
class PriorityTableTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val table by lazy { PriorityTable(data) }
    private val fixtures by lazy {
        File(src, "Chummer.Tests/TestFiles").listFiles { f -> f.name.endsWith(".chum5") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            try { f to CharacterIo.readFile(f) } catch (e: Exception) { null }
        }.filter { it.second.root.text("buildmethod")?.trim() in setOf("SumtoTen", "Priority") }
    }

    @Test
    fun tablesHaveTheNumbersOfTheRulebook() {
        assertEquals(24, table.attributes("A")); assertEquals(12, table.attributes("E"))
        assertEquals(46, table.skills("A")); assertEquals(10, table.skillGroups("A")); assertEquals(0, table.skillGroups("D"))
        assertEquals(450000, table.resources("A")); assertEquals(6000, table.resources("E"))
        assertEquals(4, table.sumToTenValue("A")); assertEquals(0, table.sumToTenValue("E"))
        val talents = table.talents("A").map { it.value }.toSet()
        assertTrue(talents.toString(), talents.containsAll(listOf("Magician", "Mystic Adept", "Technomancer")))
    }

    @Test
    fun attributeAndSpecialPointsMatchTheFixtures() {
        assertTrue("only ${fixtures.size} fixtures", fixtures.size >= 30)
        val bad = ArrayList<String>()
        var checked = 0
        for ((f, c) in fixtures) {
            val a = table.allotment(c)
            if (a == null) { bad += "${f.name}: no allotment"; continue }
            checked++
            val attrs = c.root.text("totalattributes")?.trim()?.toIntOrNull()
            val special = c.root.text("totalspecial")?.trim()?.toIntOrNull()
            if (attrs != a.attributePoints) bad += "${f.name}: attributes stored $attrs ours ${a.attributePoints}"
            if (special != a.specialPoints) bad += "${f.name}: special stored $special ours ${a.specialPoints}"
        }
        println("checked $checked, differences: $bad")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
