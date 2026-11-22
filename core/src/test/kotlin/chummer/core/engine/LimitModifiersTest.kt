package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.children
import chummer.core.child
import chummer.core.parseXml
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Condition-bound limit modifiers against the lists Chummer printed for Viper and the stored ones in the fixtures. */
class LimitModifiersTest {
    private val ref = File(System.getProperty("chummer.reference"))
    private val src = File(System.getProperty("chummer.src"))

    @Test
    fun viperLinesMatchChummersExport() {
        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        val lines = LimitModifiers.of(CharacterIo.readFile(File(ref, "Viper_career.chum5")))
        for ((tag, limit) in listOf("limitmodifiersphys" to "Physical", "limitmodifiersment" to "Mental", "limitmodifierssoc" to "Social")) {
            val want = export.child(tag)!!.children("limitmodifier").map { it.text("fullname_english")!!.trim() }
            val got = lines.filter { it.limit == limit }.map { it.fullName }
            assertEquals(limit, want.sorted(), got.sorted())
        }
    }

    @Test
    fun everyConditionKeyInTheFixturesHasText() {
        val missing = HashSet<String>()
        for (f in File(src, "Chummer.Tests/TestFiles").listFiles { x -> x.name.endsWith(".chum5") }.orEmpty()) {
            val c = try { CharacterIo.readFile(f) } catch (e: Exception) { continue }
            for (l in LimitModifiers.of(c)) if (l.condition.startsWith("LimitCondition_")) missing += l.condition
        }
        assertTrue("keys without text: $missing", missing.isEmpty())
    }
}
