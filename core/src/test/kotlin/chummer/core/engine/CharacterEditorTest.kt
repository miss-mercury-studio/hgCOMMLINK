package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File

/** Adding and removing items on a real character, and what that does to the sheet. */
class CharacterEditorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")

    private val settings: CharacterSettings
    private val pack: DataPack
    private val translations: Map<String, String>

    init {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")))
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData).dirs
        pack = DataPack.load(File(src, "Chummer/data"), dirs)
        translations = chummer.core.parseXml(File(src, "Chummer/lang/en-us.xml")).documentElement
            .child("strings")!!.children("string").associate { it.text("key")!!.trim() to it.text("text").orEmpty().trim() }
    }

    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))

    /** A canonical text of an element: tags, attributes and whitespace-normalized text. */
    private fun canon(e: Element): String = buildString {
        append('<').append(e.tagName)
        val attrs = e.attributes
        (0 until attrs.length).map { attrs.item(it) }.sortedBy { it.nodeName }.forEach { append(' ').append(it.nodeName).append('=').append(it.nodeValue) }
        append('>')
        val kids = e.children()
        if (kids.isEmpty()) append(e.textContent.trim().replace(Regex("\\s+"), " ")) else kids.forEach { append(canon(it)) }
        append("</").append(e.tagName).append('>')
    }

    private fun improvementsOf(c: CharacterFile, guid: String) =
        Improvement.parseAll(c.root).filter { it.sourceName.equals(guid, true) }
            .map { it.copy(sourceName = "") }.groupingBy { it }.eachCount()

    @Test
    fun readdingEachQualityReproducesWhatChummerWrote() {
        val original = viper()
        val qualities = original.root.child("qualities")!!.children("quality")
        var checked = 0
        val misses = ArrayList<String>()
        for (q in qualities) {
            val guid = q.text("guid")!!.trim()
            val entryId = q.text("sourceid")!!.trim()
            if (pack[entryId] == null) continue
            val c = viper()
            val editor = CharacterEditor(c, pack, translations::get)
            assertTrue(editor.removeItem(guid))
            val newGuid = editor.addQuality(entryId, extra = q.text("extra")?.trim().orEmpty())
            val added = c.root.child("qualities")!!.children("quality").last()
            checked++

            // The element, apart from the new GUID.
            val want = original.root.child("qualities")!!.children("quality").first { it.text("guid")?.trim() == guid }
            for (tag in want.children().map { it.tagName }.distinct()) {
                // Notes are free text the user may have edited, and Viper was saved with the older colour name; the name can differ when the game data was renamed since saving.
                if (tag == "guid" || tag == "notes" || tag == "notesColor") continue
                val a = want.children(tag).joinToString { canon(it) }
                val b = added.children(tag).joinToString { canon(it) }
                if (a != b) misses += "${q.text("name")?.trim()} <$tag>: Chummer='$a' ours='$b'"
            }
            // The improvements it granted.
            val wantImps = improvementsOf(original, guid)
            val gotImps = improvementsOf(c, newGuid)
            if (wantImps != gotImps) misses += "${q.text("name")?.trim()} improvements: Chummer=${wantImps.keys.map { it.type + ":" + it.improvedName + "=" + it.value }} ours=${gotImps.keys.map { it.type + ":" + it.improvedName + "=" + it.value }}"
        }
        println("Qualities re-added: $checked, ${misses.size} differences")
        misses.forEach { println("  DIFF $it") }
        assertTrue(checked >= 8)
        assertTrue("regressed: ${misses.size} > $KNOWN_DIFFERENCES", misses.size <= KNOWN_DIFFERENCES)
        if (misses.size < KNOWN_DIFFERENCES) println("Improved: lower KNOWN_DIFFERENCES to ${misses.size}")
    }

    @Test
    fun addingAQualityChangesTheSheet() {
        val c = viper()
        val before = Sheet.build(c, pack, settings)
        val toughness = pack.byId.values.first { it.element.child("name")?.textContent?.trim() == "Toughness" && it.file == "qualities.xml" }
        val id = toughness.element.child("id")!!.textContent.trim()
        val editor = CharacterEditor(c, pack, translations::get)
        val guid = editor.addQuality(id)
        val after = Sheet.build(c, pack, settings)
        assertEquals(before.soak + 1, after.soak)
        assertTrue(after.qualities.any { it.name == "Toughness" })

        // The edited file is still a valid character file: it writes out and reads back with the same result.
        val out = ByteArrayOutputStream()
        CharacterIo.write(c, out)
        val reread = CharacterIo.read(out.toByteArray().inputStream())
        assertEquals(after.soak, Sheet.build(reread, pack, settings).soak)

        // And removing it restores the original numbers.
        assertTrue(editor.removeItem(guid))
        assertEquals(before.soak, Sheet.build(c, pack, settings).soak)
        assertEquals(before.qualities.size, Sheet.build(c, pack, settings).qualities.size)
    }

    @Test
    fun qualityCatalogListsTheDataAndFlagsWhatIsNotSupported() {
        val all = QualityCatalog.list(pack)
        val supported = all.count { it.unsupported.isEmpty() }
        println("Qualities in the data: ${all.size}, fully supported: $supported, ask for text: ${all.count { it.needsText }}")
        assertTrue(all.size > 200)
        assertTrue(all.any { it.name == "Toughness" && it.unsupported.isEmpty() && !it.needsText })
        assertTrue(all.any { it.name == "Vendetta" && it.needsText })
    }

    companion object {
        // Vendetta: Chummer saved its bonus without the text prompt (an older version of the data); Shoot First was renamed.
        const val KNOWN_DIFFERENCES = 2
    }
}
