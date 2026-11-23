package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prerequisites of qualities, checked against what Chummer let the fixture characters take. */
class PrerequisitesTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private fun byNameOrNull(name: String) = data.byId.values.firstOrNull { it.file == "qualities.xml" && it.element.tagName == "quality" && it.element.child("name")?.textContent?.trim() == name }?.element

    private fun fixtures() = src.walkTopDown().filter { it.extension == "chum5" && !it.name.startsWith("Davis Jones") }.toList()

    /** Every quality a fixture character holds, picked by the user, should meet its own prerequisites. */
    @Test
    fun qualitiesTheFixturesHoldMeetTheirPrerequisites() {
        var checked = 0
        val failures = ArrayList<String>()
        val unchecked = sortedSetOf<String>()
        for (f in fixtures()) {
            val c = CharacterIo.readFile(f)
            val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"))
            for (q in c.root.child("qualities")?.children("quality").orEmpty()) {
                if (q.text("qualitysource")?.trim() != "Selected") continue
                val entry = (data[q.text("sourceid")?.trim().orEmpty()]?.element ?: byNameOrNull(q.text("name")?.trim().orEmpty())) ?: continue
                // Creation-only flags describe adding, not holding.
                val r = Prerequisites.check(entry, c, data, settings.toRuleSettings())
                checked++
                unchecked += r.unchecked
                val problems = r.problems.filter { !it.startsWith("Only available") && !it.startsWith("Only given") }
                if (problems.isNotEmpty()) failures += "${f.name}: ${q.text("name")?.trim()}: $problems"
            }
        }
        println("Prerequisites: $checked qualities checked, ${failures.size} unmet; item kinds not read: $unchecked")
        failures.take(20).forEach { println("  UNMET $it") }
        assertTrue(checked > 100)
        assertTrue("regressed: ${failures.size}", failures.size <= KNOWN_UNMET)
        if (failures.size < KNOWN_UNMET) println("Improved: lower KNOWN_UNMET to ${failures.size}")
    }

    @Test
    fun aQualityNeedingMagicIsRefusedWithoutItAndAllowedWithIt() {
        val c = CharacterIo.readFile(fixtures().first { f -> CharacterIo.readFile(f).root.text("magenabled")?.trim() == "True" })
        val withMagic = CharacterIo.readFile(fixtures().first { f -> CharacterIo.readFile(f).root.text("magenabled")?.trim() == "True" })
        val withoutMagic = CharacterIo.readFile(fixtures().first { f -> CharacterIo.readFile(f).root.text("magenabled")?.trim() != "True" })
        // A quality whose only requirement is Magic enabled.
        val entry = data.byId.values.first {
            it.file == "qualities.xml" && it.element.tagName == "quality" &&
                it.element.child("required")?.children()?.singleOrNull()?.let { n -> n.tagName == "allof" && n.children().singleOrNull()?.tagName == "magenabled" } == true
        }.element
        assertTrue(Prerequisites.check(entry, withMagic, data).met)
        val refused = Prerequisites.check(entry, withoutMagic, data)
        assertEquals(listOf("Needs Magic enabled"), refused.problems)
        assertNotNull(c)
    }

    @Test
    fun forbiddenQualitiesRefuseAndMetatypeRequirementsRead() {
        // Take any quality with a forbidden quality and give the character that quality.
        val entry = data.byId.values.first {
            it.file == "qualities.xml" && it.element.tagName == "quality" &&
                it.element.child("forbidden")?.children("oneof")?.any { o -> o.children().any { n -> n.tagName == "quality" } } == true &&
                it.element.child("required") == null
        }.element
        val forbiddenName = entry.child("forbidden")!!.children("oneof").flatMap { it.children() }.first { it.tagName == "quality" }.textContent.trim()
        val c = CharacterIo.readFile(fixtures().first())
        val ed = CharacterEditor(c, data)
        val target = data.byId.entries.firstOrNull { it.value.file == "qualities.xml" && it.value.element.child("name")?.textContent?.trim() == forbiddenName }
        assertNotNull(target)
        c.root.child("qualities")?.children("quality")?.filter { it.text("name")?.trim() == forbiddenName }?.forEach { it.parentNode.removeChild(it) }
        assertTrue(Prerequisites.check(entry, c, data).problems.none { it.contains(forbiddenName) })
        ed.addQuality(target!!.key)
        assertTrue(Prerequisites.check(entry, c, data).problems.any { it == "Not allowed with $forbiddenName" })
    }

    companion object { const val KNOWN_UNMET = 2 }
}
