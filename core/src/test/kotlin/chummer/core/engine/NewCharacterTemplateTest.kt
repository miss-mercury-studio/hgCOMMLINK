package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/**
 * Characters made from scratch in Chummer (reference/New_*.chum5, Karma build, Human) as golden files.
 * New_Empty has nothing bought; New_Spent has one attribute point and one skill point; New_Quality has
 * the Lightning Reflexes quality.
 */
class NewCharacterTemplateTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))

    private fun load(name: String) = CharacterIo.readFile(File(ref, name))

    private val pack: DataPack by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val translations: Map<String, String> by lazy {
        chummer.core.parseXml(File(src, "Chummer/lang/en-us.xml")).documentElement
            .child("strings")!!.children("string").associate { it.text("key")!!.trim() to it.text("text").orEmpty().trim() }
    }

    private fun canon(e: Element): String = buildString {
        append('<').append(e.tagName).append('>')
        val kids = e.children()
        if (kids.isEmpty()) append(e.textContent.trim().replace(Regex("\\s+"), " ")) else kids.forEach { append(canon(it)) }
        append("</").append(e.tagName).append('>')
    }

    @Test
    fun addingLightningReflexesToTheEmptyCharacterMatchesChummer() {
        val want = load("New_Quality.chum5")
        val c = load("New_Empty.chum5")
        val sourceId = want.root.child("qualities")!!.children("quality").single().text("sourceid")!!.trim()
        val editor = CharacterEditor(c, pack, translations::get)
        val guid = editor.addQuality(sourceId)

        val wantQ = want.root.child("qualities")!!.children("quality").single()
        val gotQ = c.root.child("qualities")!!.children("quality").single()
        val misses = ArrayList<String>()
        for (tag in wantQ.children().map { it.tagName }.distinct()) {
            if (tag == "guid") continue
            val a = wantQ.children(tag).joinToString { canon(it) }
            val b = gotQ.children(tag).joinToString { canon(it) }
            if (a != b) misses += "<$tag>: Chummer='$a' ours='$b'"
        }
        fun imps(f: CharacterFile) = Improvement.parseAll(f.root).map { it.copy(sourceName = "") }.groupingBy { it }.eachCount()
        if (imps(want) != imps(c)) misses += "improvements: Chummer=${imps(want).keys} ours=${imps(c).keys}"
        println("New_Quality: ${misses.size} differences")
        misses.forEach { println("  DIFF $it") }
        assertTrue(guid.isNotEmpty())
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun spentFileDiffersFromEmptyOnlyByTheTwoPurchases() {
        val a = load("New_Empty.chum5")
        val b = load("New_Spent.chum5")
        fun flat(e: Element, prefix: String = ""): List<Pair<String, String>> =
            e.children().flatMap { c -> if (c.children().isEmpty()) listOf("$prefix/${c.tagName}" to c.textContent.trim()) else flat(c, "$prefix/${c.tagName}") }
        val diffs = flat(a.root).zip(flat(b.root)).filter { it.first != it.second }
        println("Empty vs Spent: $diffs")
        assertEquals(4, diffs.size)
    }
}
