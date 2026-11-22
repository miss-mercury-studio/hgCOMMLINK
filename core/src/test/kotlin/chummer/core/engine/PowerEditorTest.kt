package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/** Adept powers rebuilt from the data must equal the ones Chummer saved in the fixtures, improvements included. */
class PowerEditorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val ws = Regex("""\s+""")
    private val skip = setOf("guid", "notes", "enhancements", "adeptwayrequires", "bonus", "pointsperlevel", "adeptway", "page", "source", "action", "rating", "extrapointcost", "freepoints", "maxlevel")

    private data class Key(val type: String, val name: String, val value: Double, val rating: Double, val unique: String, val min: Double, val max: Double, val aug: Double, val augMax: Double)
    private fun key(i: Improvement) = Key(i.type, i.improvedName, i.value, i.rating, i.uniqueName, i.minimum, i.maximum, i.augmented, i.augmentedMaximum)

    private fun files() = src.walkTopDown().filter { it.extension == "chum5" && !it.name.startsWith("Davis Jones") }.toList()

    @Test
    fun powersMatchTheFixtures() {
        var checked = 0
        val bad = ArrayList<String>()
        var improvementsChecked = 0
        var improvementsMatched = 0
        val improvementDiffs = ArrayList<String>()
        for (f in files()) {
            val c0 = CharacterIo.readFile(f)
            val stored = c0.root.child("powers")?.children("power").orEmpty()
            if (stored.isEmpty()) continue
            val settings = CharacterSettings.find(c0.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"))
            val storedImps = Improvement.parseAll(c0.root).filter { it.source == "Power" && it.type != "DisableQuality" }.groupBy { it.sourceName }
            for (p in stored) {
                val id = p.text("id")?.trim().orEmpty()
                if (data[id] == null) continue
                val scratch = CharacterIo.readFile(f)
                scratch.root.child("powers")!!.children("power").forEach { scratch.root.child("powers")!!.removeChild(it) }
                val ed = PowerEditor(scratch, data, settings)
                val guid = ed.add(id, p.text("rating")?.trim()?.toIntOrNull() ?: 1, p.text("extra")?.trim().orEmpty())
                val made = scratch.root.child("powers")!!.children("power").first { it.text("guid")?.trim() == guid }
                checked++
                for (k in p.children()) {
                    if (k.tagName in skip || k.children().isNotEmpty()) continue
                    val want = k.textContent.trim().replace(ws, " ")
                    val got = made.child(k.tagName)?.textContent?.trim()?.replace(ws, " ")
                    if (want != got) bad += "${f.name}: ${p.text("name")} ${k.tagName} Chummer=[$want] ours=[$got]"
                }
                // Numbers compare as numbers (".5" and "0.5" are the same).
                for (tag in listOf("pointsperlevel", "adeptway", "extrapointcost")) {
                    val want = p.text(tag)?.trim()?.toDoubleOrNull() ?: 0.0
                    val got = made.text(tag)?.trim()?.toDoubleOrNull() ?: 0.0
                    if (want != got) bad += "${f.name}: ${p.text("name")} $tag Chummer=$want ours=$got"
                }
                val want = (storedImps[p.text("guid")?.trim().orEmpty()] ?: emptyList()).map(::key).groupingBy { it }.eachCount()
                val got = Improvement.parseAll(scratch.root).filter { it.sourceName == guid && it.type != "DisableQuality" }.map(::key).groupingBy { it }.eachCount()
                improvementsChecked++
                if (want == got) improvementsMatched++ else if (improvementDiffs.size < 14) improvementDiffs += "${f.name}: ${p.text("name")} (${p.text("extra")}) rating ${p.text("rating")} want=${want.keys.map { it.type + ":" + it.name + ":" + it.value + ":" + it.rating }} got=${got.keys.map { it.type + ":" + it.name + ":" + it.value + ":" + it.rating }}"
            }
        }
        println("Powers checked $checked, ${bad.size} field differences; improvements matched $improvementsMatched of $improvementsChecked")
        bad.take(25).forEach { println("  POWERDIFF $it") }
        improvementDiffs.forEach { println("  IMPDIFF $it") }
        assertTrue(checked > 60)
        assertTrue("regressed: ${bad.size}", bad.size <= KNOWN_FIELD_DIFFERENCES)
        assertTrue("improvements regressed: ${improvementsChecked - improvementsMatched}", improvementsChecked - improvementsMatched <= KNOWN_IMPROVEMENT_MISSES)
    }

    @Test
    fun whichPowerBonusNodesHaveNoHandler() {
        val unknown = java.util.TreeMap<String, Int>()
        for (o in PowerCatalog.list(data)) for (n in o.unsupported) unknown.merge(n, 1, Int::plus)
        println("POWER NODES WITHOUT HANDLER $unknown")
    }

    @Test
    fun powerPointsNeverExceedWhatTheCharacterHas() {
        var characters = 0
        for (f in files()) {
            val c = CharacterIo.readFile(f)
            if (c.root.child("powers")?.children("power").orEmpty().isEmpty()) continue
            val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"))
            val pp = PowerPoints.of(c, settings, data)
            characters++
            println("PP ${f.name}: used ${pp.used} of ${pp.total}")
            assertTrue("${f.name}: used ${pp.used} of ${pp.total}", pp.used <= pp.total + 0.001)
        }
        assertTrue(characters >= 10)
    }

    // The 10 known misses are older-version differences in Linguistics, Astral Perception, Danger Sense and Precision Throwing.
    companion object { const val KNOWN_FIELD_DIFFERENCES = 0; const val KNOWN_IMPROVEMENT_MISSES = 10 }
}
