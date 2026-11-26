package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.Fixtures
import chummer.core.child
import chummer.core.children
import chummer.core.int
import chummer.core.text
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden test: recompute every attribute and compare with the total Chummer stored.
 * The stored value already includes the enabled improvements, so equality means our
 * engine reproduces Chummer's own numbers. Known gaps are tracked in [KNOWN_MISSES]; the
 * test fails if the engine gets worse, and prints when it gets better so the number can be lowered.
 */
class GoldenAttributeTest {
    private val src = File(System.getProperty("chummer.src"))
    private val fixtures: List<File> = Fixtures.characters()

    @Test
    fun attributesMatchChummerStoredTotals() {
        var checked = 0
        val misses = ArrayList<String>()
        for (f in fixtures) {
            val c = CharacterIo.readFile(f)
            val rules = Fixtures.settings(f, c).toRuleSettings()
            val calc = AttributeEngine.forFile(c, rules)
            for (row in c.root.child("attributes")?.children("attribute").orEmpty()) {
                val name = row.text("name").orEmpty()
                if (name == "ESS") continue
                checked++
                val stored = row.int("totalvalue")
                val ours = calc.getValue(name).totalValue
                if (ours != stored) misses += "${f.name.take(24)} $name stored=$stored ours=$ours (ver ${c.appVersion}, ${c.root.text("metatype")})"
            }
        }
        println("Attributes: ${checked - misses.size}/$checked match, ${misses.size} miss")
        misses.forEach { println("  MISS $it") }
        assertTrue("engine regressed: ${misses.size} misses > $KNOWN_MISSES", misses.size <= KNOWN_MISSES)
        if (misses.size < KNOWN_MISSES) println("Improved: lower KNOWN_MISSES to ${misses.size}")
    }

    companion object {
        const val KNOWN_MISSES = 4
    }
}
