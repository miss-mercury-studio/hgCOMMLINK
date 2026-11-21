package chummer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M0 spike: checks our first-pass reading and calculation against the values Chummer
 * itself stored in the Viper reference character.
 */
class SpikeTest {
    private val src = File(System.getProperty("chummer.src"))
    private val viper = File(System.getProperty("chummer.reference"), "Viper_career.chum5")
    private val pack by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val character by lazy { CharacterFile.load(viper) }

    @Test
    fun dataPackLoadsAndEveryViperSourceIdResolves() {
        val t0 = System.nanoTime()
        val loaded = DataPack.load(File(src, "Chummer/data"))
        val rt = Runtime.getRuntime()
        println("DataPack: ${loaded.byId.size} entries, ${loaded.fileCount} files, ${(System.nanoTime() - t0) / 1_000_000} ms, heap ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB")
        val ids = character.sourceIds()
        val missing = ids.filter { pack[it.second] == null }
        assertTrue("unresolved sourceids: $missing", missing.isEmpty())
        assertTrue(ids.size > 250)
    }

    @Test
    fun attributeTotalsMatchStoredValues() {
        val imps = character.improvements()
        for (a in character.attributes().filter { it.name != "ESS" }) {
            val bonus = imps.filter { it.type == "Attribute" && it.enabled && it.name == a.name }.sumOf { it.aug }
            assertEquals(a.name, a.storedTotal, a.min + a.base + a.karma + bonus)
        }
    }

    @Test
    fun expenseLogSumsMatchKarmaAndNuyen() {
        assertEquals(character.root.text("karma")!!.trim().toDouble(), character.expenseTotal("Karma"), 0.001)
        assertEquals(character.root.text("nuyen")!!.trim().toDouble(), character.expenseTotal("Nuyen"), 0.001)
    }

    @Test
    fun essenceMatchesStoredValueWhenMetagenicsAreExcluded() {
        // Metagenic bioware (Narco, Synaptic Acceleration) is stored with an ess value
        // but does not reduce Essence. Where Chummer encodes that rule is still to be found (M2).
        val metagenic = setOf("Exotic Metagenetics", "Transgenics")
        fun used(l: List<CharacterFile.Augmentation>): Double = l.sumOf { a ->
            val own = if (pack[a.sourceId]?.element?.text("category") in metagenic) 0.0
            else Expr.eval(a.essFormula, mapOf("Rating" to a.rating.toDouble()))
            own + used(a.children)
        }
        val essence = 6.0 - used(character.augmentations())
        assertEquals(character.root.text("totaless")!!.trim().toDouble(), essence, 0.001)
    }

    @Test
    fun exprEvaluatesDataFormulas() {
        val r = mapOf("Rating" to 3.0)
        assertEquals(0.4, Expr.eval("(Rating * 0.1) + 0.1", mapOf("Rating" to 3.0)), 1e-9)
        assertEquals(0.75, Expr.eval("Rating * 0.25", r), 1e-9)
        assertEquals(1.5, Expr.eval("Rating div 2", r), 1e-9)
        assertEquals(-3.0, Expr.eval("-Rating", r), 1e-9)
    }
}
