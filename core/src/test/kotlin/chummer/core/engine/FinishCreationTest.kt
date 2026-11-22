package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/** Finishing New_Skills.chum5 with a starting nuyen of 120 must give what Chummer saved as "New_Skills _Career.chum5". */
class FinishCreationTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = chummer.core.data.CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun canon(e: Element, parent: String = ""): String = buildString {
        append('<').append(e.tagName).append('>')
        val kids = e.children()
        if (kids.isEmpty()) {
            val text = e.textContent.trim()
            val random = e.tagName == "guid" || e.tagName == "date" || e.tagName == "parentid" || e.tagName == "notesColor" || e.tagName.startsWith("priority") ||
                (parent == "improvement" && (e.tagName == "sourcename" || e.tagName == "improvedname") && uuid.matches(text) && text.lowercase() !in data.byId.keys) ||
                (e.tagName == "id" && parent == "group")
            append(if (random) "*" else text.replace(Regex("\\s+"), " "))
        } else kids.forEach { append(canon(it, e.tagName)) }
        append("</").append(e.tagName).append('>')
    }

    @Test
    fun finishedCharacterMatchesChummer() {
        val c = CharacterIo.readFile(File(ref, "New_Skills.chum5"))
        val want = CharacterIo.readFile(File(ref, "New_Skills _Career.chum5"))
        val plan = CharacterFinisher.plan(c, settings, data)
        assertEquals(7, plan.karmaKept)
        assertEquals(635, plan.karmaLost)
        assertTrue(plan.addsStreetLifestyle)
        assertEquals(emptyList<String>(), CharacterFinisher.finish(c, settings, data, 120))
        // Compare section by section so a difference is easy to see.
        val misses = ArrayList<String>()
        for (tag in want.root.children().map { it.tagName }.distinct()) {
            val a = want.root.children(tag).joinToString { canon(it) }
            val b = c.root.children(tag).joinToString { canon(it) }
            if (a != b) {
                val at = a.zip(b).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(a.length, b.length) else it }
                misses += "<$tag> at $at\n  Chummer: ${a.substring(maxOf(0, at - 80), minOf(a.length, at + 160))}\n  ours:    ${b.substring(maxOf(0, at - 80), minOf(b.length, at + 160))}"
            }
        }
        misses.forEach { println("DIFF $it") }
        assertEquals(emptyList<String>(), misses)
    }
}
