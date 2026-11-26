package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.Fixtures
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Golden test: recompute Essence and compare with the totaless value Chummer stored. */
class GoldenEssenceTest {
    private val src = File(System.getProperty("chummer.src"))
    private val fixtures: List<File> = Fixtures.characters()

    @Test
    fun essenceMatchesChummerStoredValue() {
        var checked = 0
        val misses = ArrayList<String>()
        for (f in fixtures) {
            val c = CharacterIo.readFile(f)
            val stored = c.root.text("totaless")?.trim()?.toDoubleOrNull() ?: continue
            val career = c.root.text("created")?.trim().equals("True", true)
            val settings = Fixtures.settings(f, c)
            val rules = settings.toRuleSettings()
            val pack = Fixtures.pack(f, settings)
            val set = ImprovementSet(Improvement.parseAll(c.root), career)
            val essRow = c.root.child("attributes")?.children("attribute").orEmpty().first { it.text("name") == "ESS" }
            val max = essRow.text("metatypemax")?.trim()?.toDoubleOrNull() ?: 6.0
            val ours = EssenceEngine(
                set, pack, career, rules.essenceDecimals, rules.dontRoundEssenceInternally,
                isPrototypeTranshuman = (c.root.text("prototypetranshuman")?.trim()?.toDoubleOrNull() ?: 0.0) > 0,
            ).essence(max, c.augmentations())
            checked++
            if (abs(ours - stored) > 0.005) {
                misses += "%s stored=%s ours=%.2f (ver %s)".format(f.name.take(24), stored, ours, c.appVersion)
            }
        }
        println("Essence: ${checked - misses.size}/$checked match")
        misses.forEach { println("  MISS $it") }
        assertTrue("engine regressed: ${misses.size} > $KNOWN_MISSES", misses.size <= KNOWN_MISSES)
        if (misses.size < KNOWN_MISSES) println("Improved: lower KNOWN_MISSES to ${misses.size}")
    }

    companion object {
        const val KNOWN_MISSES = 4
    }
}
