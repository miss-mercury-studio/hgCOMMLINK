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

    /** Text of an element tree with the random parts (GUIDs and group ids) blanked, so two files can be compared. */
    private fun canonNoGuids(e: Element, parent: String = ""): String = buildString {
        append('<').append(e.tagName).append('>')
        val kids = e.children()
        if (kids.isEmpty()) {
            val random = e.tagName == "guid" || e.tagName == "parentid" || (e.tagName == "id" && parent == "group")
            append(if (random) "*" else e.textContent.trim().replace(Regex("\\s+"), " "))
        } else kids.forEach { append(canonNoGuids(it, e.tagName)) }
        append("</").append(e.tagName).append('>')
    }

    @Test
    fun generatedHumanKarmaCharacterMatchesChummersEmptyCharacter() {
        val settings = CharacterSettings.find(
            "ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref),
        )
        assertTrue("settings profile not found", !settings.isFallback)
        val resolved = chummer.core.data.CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        val data = DataPack.load(File(src, "Chummer/data"), resolved.dirs)
        val human = data.byId.values.first { it.file == "metatypes.xml" && it.element.tagName == "metatype" && it.element.child("name")?.textContent?.trim() == "Human" }
        val humanId = human.element.child("id")!!.textContent.trim()

        val generated = NewCharacter.createKarma(
            data, settings,
            NewCharacterRequest(metatypeId = humanId, settingsHash = 1889893532, customDataNames = resolved.dirs.map { it.name }),
        )
        val want = load("New_Empty.chum5")

        val a = canonNoGuids(want.root)
        val b = canonNoGuids(generated.root)
        if (a != b) {
            // Show the first difference with some context.
            val at = a.zip(b).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(a.length, b.length) else it }
            println("DIFF at $at:")
            println("  Chummer: ${a.substring(maxOf(0, at - 120), minOf(a.length, at + 200))}")
            println("  ours:    ${b.substring(maxOf(0, at - 120), minOf(b.length, at + 200))}")
        }
        assertEquals(a, b)

        // The random ids are unique, and the file writes out and reads back.
        val guids = generated.root.child("newskills")!!.child("skills")!!.children("skill").map { it.child("guid")!!.textContent }
        assertEquals(guids.size, guids.toSet().size)
        val again = CharacterIo.read(CharacterIo.toBytes(generated).inputStream())
        assertEquals(a, canonNoGuids(again.root))
    }

    @Test
    fun everyBaseMetatypeGeneratesACharacterTheSheetCanRead() {
        val settings = CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref))
        val data = DataPack.load(File(src, "Chummer/data"))
        val metatypes = data.byId.entries.filter { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" }
        assertTrue(metatypes.size >= 5)
        for ((id, entry) in metatypes) {
            val name = entry.element.child("name")!!.textContent.trim()
            val c = NewCharacter.createKarma(data, settings, NewCharacterRequest(name = "Test $name", metatypeId = id))
            val sheet = Sheet.build(c, data, settings)
            // Every attribute starts at the metatype's minimum.
            for (a in sheet.attributes.filter { it.name in setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL") }) {
                val min = entry.element.child(a.name.lowercase() + "min")!!.textContent.trim().toInt()
                assertEquals("$name ${a.name}", maxOf(min, 1), a.total)
            }
            assertEquals("Test $name", sheet.name)
            assertEquals(name, sheet.metatype)
        }
        val ork = metatypes.first { it.value.element.child("name")!!.textContent.trim() == "Ork" }
        val orkSheet = Sheet.build(NewCharacter.createKarma(data, settings, NewCharacterRequest(metatypeId = ork.key)), data, settings)
        println("Ork at creation: " + orkSheet.attributes.joinToString { "${it.name} ${it.total}/${it.maximum}" })
        assertEquals(4, orkSheet.attributes.first { it.name == "BOD" }.total)
    }

    @Test
    fun generatedTrollMatchesChummersEmptyTroll() {
        val settings = CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref))
        val resolved = chummer.core.data.CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        val data = DataPack.load(File(src, "Chummer/data"), resolved.dirs)
        val troll = data.byId.values.first { it.file == "metatypes.xml" && it.element.tagName == "metatype" && it.element.child("name")?.textContent?.trim() == "Troll" }
        val generated = NewCharacter.createKarma(
            data, settings,
            NewCharacterRequest(metatypeId = troll.element.child("id")!!.textContent.trim(), settingsHash = 1889893532, customDataNames = resolved.dirs.map { it.name }),
        )
        val a = canonNoGuids(load("empty_troll.chum5").root)
        val b = canonNoGuids(generated.root)
        if (a != b) {
            val at = a.zip(b).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(a.length, b.length) else it }
            println("TROLL DIFF at $at:")
            println("  Chummer: ${a.substring(maxOf(0, at - 150), minOf(a.length, at + 400))}")
            println("  ours:    ${b.substring(maxOf(0, at - 150), minOf(b.length, at + 400))}")
        }
        assertEquals(a, b)
    }

    /**
     * The karma ledger on the reference characters. Chummer does not store the remaining karma, so these figures are
     * computed from the rules (budget 800, attribute step cost 5, new skill 2, quality bp x 1, Troll metatype 90) and are
     * waiting for the user to confirm them against Chummer's "Karma Remaining" for each file.
     */
    @Test
    fun karmaLedgerOnTheReferenceCharacters() {
        val settings = CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref))
        fun remaining(file: String): KarmaLedger = KarmaLedger.of(load(file), settings).also { println("$file: $it remaining=${it.remaining}") }
        assertEquals(800, remaining("New_Empty.chum5").remaining)
        assertEquals(800 - 10 - 2, remaining("New_Spent.chum5").remaining)
        assertEquals(800 - 20, remaining("New_Quality.chum5").remaining)
        assertEquals(800 - 90, remaining("empty_troll.chum5").remaining)
        assertEquals(800 - 90 - 45 - 70, remaining("troll_spent.chum5").remaining)
    }

    private fun settingsForTemplates() = CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref))

    /** Buying a point of BOD and a rating of Pistols on Chummer's empty character gives Chummer's "spent" character. */
    @Test
    fun buyingBodAndPistolsMatchesNewSpent() {
        val settings = settingsForTemplates()
        val c = load("New_Empty.chum5")
        val editor = CharacterEditor(c, pack, translations::get, settings)
        assertTrue(editor.changeAttribute("BOD", 1))
        assertTrue(editor.changeSkill("Pistols", 1))
        assertEquals(canonNoGuids(load("New_Spent.chum5").root), canonNoGuids(c.root))
        // And giving them back restores the empty character.
        assertTrue(editor.changeAttribute("BOD", -1))
        assertTrue(editor.changeSkill("Pistols", -1))
        assertEquals(canonNoGuids(load("New_Empty.chum5").root), canonNoGuids(c.root))
        assertTrue(!editor.changeAttribute("BOD", -1))
    }

    @Test
    fun buyingReaAndWilOnTheTrollMatchesTrollSpent() {
        val settings = settingsForTemplates()
        val c = load("empty_troll.chum5")
        val editor = CharacterEditor(c, pack, translations::get, settings)
        repeat(3) { assertTrue(editor.changeAttribute("REA", 1)) }
        repeat(4) { assertTrue(editor.changeAttribute("WIL", 1)) }
        assertEquals(canonNoGuids(load("troll_spent.chum5").root), canonNoGuids(c.root))
    }

    @Test
    fun attributesStopAtTheMetatypeMaximum() {
        val settings = settingsForTemplates()
        val c = load("New_Empty.chum5")
        val editor = CharacterEditor(c, pack, translations::get, settings)
        repeat(5) { assertTrue(editor.changeAttribute("STR", 1)) }   // 1 to 6, the human maximum
        assertTrue(!editor.changeAttribute("STR", 1))
        val max = settings.toRuleSettings().maxSkillRatingCreate      // 5 in the San Francisco Showdown profile
        assertEquals(5, max)
        repeat(max) { assertTrue(editor.changeSkill("Pistols", 1)) }
        assertTrue(!editor.changeSkill("Pistols", 1))
    }
}
