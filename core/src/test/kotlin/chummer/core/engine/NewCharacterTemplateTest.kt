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
            // Note colours are a display setting of the Chummer session that saved the file, so they differ between files.
            val random = e.tagName == "guid" || e.tagName == "parentid" || e.tagName == "notesColor" || (e.tagName == "id" && parent == "group")
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
     * The karma ledger on the reference characters, with the San Francisco Showdown budget of 700. Chummer does not store
     * the remaining karma, so only the figure the user read from Chummer's status bar for `AppMade_Ork` is confirmed
     * (see [karmaLedgerMatchesChummerForTheAppMadeOrk]); the others are computed from the rules.
     */
    @Test
    fun karmaLedgerOnTheReferenceCharacters() {
        val settings = CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref))
        assertEquals(700, settings.int("buildpoints"))
        fun remaining(file: String): KarmaLedger = KarmaLedger.of(load(file), settings).also { println("$file: $it remaining=${it.remaining}") }
        assertEquals(700, remaining("New_Empty.chum5").remaining)
        assertEquals(700 - 10 - 2, remaining("New_Spent.chum5").remaining)
        assertEquals(700 - 20, remaining("New_Quality.chum5").remaining)
        assertEquals(700 - 90, remaining("empty_troll.chum5").remaining)
        assertEquals(700 - 90 - 45 - 70, remaining("troll_spent.chum5").remaining)
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

    /**
     * Writes two files made by this code to `core/build/samples/`, for opening in desktop Chummer by hand:
     * a new Ork with purchases and a quality, and a copy of Viper with Toughness added. Not a check of Chummer;
     * the assertions only guard the numbers the user is told to expect.
     */
    @Test
    fun karmaLedgerMatchesChummerForTheAppMadeOrk() = writeSamplesToTryInDesktopChummer()

    @Test
    fun writeSamplesToTryInDesktopChummer() {
        val settings = settingsForTemplates()
        val resolved = chummer.core.data.CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        val data = DataPack.load(File(src, "Chummer/data"), resolved.dirs)
        val out = File("build/samples").apply { mkdirs() }

        val ork = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.element.child("name")?.textContent?.trim() == "Ork" }
        val c = NewCharacter.createKarma(
            data, settings,
            NewCharacterRequest(name = "App Made Ork", alias = "Sample", metatypeId = ork.key, settingsHash = 1889893532, customDataNames = resolved.dirs.map { it.name }),
        )
        val editor = CharacterEditor(c, data, translations::get, settings)
        repeat(2) { assertTrue(editor.changeAttribute("AGI", 1)) }
        assertTrue(editor.changeAttribute("BOD", 1))
        repeat(2) { assertTrue(editor.changeSkill("Pistols", 1)) }
        assertTrue(editor.changeSkill("Perception", 1))
        val toughness = data.byId.entries.first { it.value.file == "qualities.xml" && it.value.element.child("name")?.textContent?.trim() == "Toughness" }
        editor.addQuality(toughness.key)
        File(out, "AppMade_Ork.chum5").outputStream().use { CharacterIo.write(c, it) }
        val ledger = KarmaLedger.of(c, settings)
        println("AppMade_Ork: $ledger remaining=${ledger.remaining}")
        // Chummer 5.226 showed Karma 700, Karma Remaining 583 for this file, with Metatype 50, Attributes 50,
        // Active Skills 8 and Positive Qualities 9 in its Karma Summary.
        assertEquals(583, ledger.remaining)
        assertEquals(listOf(50, 50, 8, 9), listOf(ledger.metatype, ledger.attributes, ledger.skills, ledger.qualities))
        // Chummer's Karma Summary showed Positive Qualities 9/50 and Negative Qualities 0/50.
        assertEquals(listOf(9, 0, 50), listOf(ledger.positiveQualities, ledger.negativeQualities, ledger.qualityLimit))
        assertEquals(emptyList<String>(), ledger.problems)

        // Viper with Toughness added (the settings and data Viper was saved with).
        val viperSettings = CharacterSettings.find(load("Viper_career.chum5").root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings")))
        val viperDirs = chummer.core.data.CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(viperSettings.customData).dirs
        val viperData = DataPack.load(File(src, "Chummer/data"), viperDirs)
        val v = load("Viper_career.chum5")
        val vt = viperData.byId.entries.first { it.value.file == "qualities.xml" && it.value.element.child("name")?.textContent?.trim() == "Toughness" }
        CharacterEditor(v, viperData, translations::get, viperSettings).addQuality(vt.key)
        File(out, "AppEdited_Viper_Toughness.chum5").outputStream().use { CharacterIo.write(v, it) }
        println("Wrote samples to ${out.absolutePath}")
    }

    @Test
    fun qualityLimitAndOverspendAreReported() {
        val settings = settingsForTemplates()
        val data = DataPack.load(File(src, "Chummer/data"))
        val c = load("New_Empty.chum5")
        val editor = CharacterEditor(c, data, translations::get, settings)
        fun entry(name: String) = data.byId.entries.first { it.value.file == "qualities.xml" && it.value.element.child("name")?.textContent?.trim() == name }.key
        // Lightning Reflexes (20) and Toughness (9) fit in 50; adding a third big one does not.
        assertEquals(null, editor.qualityLimitProblem(entry("Toughness")))
        editor.addQuality(entry("Lightning Reflexes"))
        editor.addQuality(entry("Toughness"))
        val big = data.byId.entries.filter { it.value.file == "qualities.xml" && it.value.element.child("category")?.textContent?.trim() == "Positive" }
            .first { (it.value.element.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0) >= 25 }
        val problem = editor.qualityLimitProblem(big.key)
        println("limit problem: $problem")
        assertTrue(problem!!.startsWith("Positive qualities would be"))
        // Overspending and an over-limit quality list show up as problems.
        val over = KarmaLedger(budget = 100, metatype = 50, attributes = 60, skills = 0, qualities = 0, contacts = 0,
            notCounted = emptyList(), positiveQualities = 60, negativeQualities = 0, qualityLimit = 50)
        assertEquals(listOf("Overspent by 10 karma", "Positive qualities 60/50 karma"), over.problems)
    }

    /**
     * New_Skills.chum5 (Ork, Karma build): Firearms group 1, Heavy Weapons 1 (karma), knowledge skills Black Markets (1 karma),
     * English (native), Street Gangs: Seattle and Turkish (2 free points each). Chummer 5.226 showed Karma Remaining 642 and
     * "Knowledge Skills 0 of 4: 1 Karma" with Skill Groups 5 and Active Skills 2.
     */
    @Test
    fun karmaLedgerMatchesChummerForTheSkillsFile() {
        val settings = settingsForTemplates()
        val data = DataPack.load(File(src, "Chummer/data"))
        val l = KarmaLedger.of(load("New_Skills.chum5"), settings, data)
        println("New_Skills: $l remaining=${l.remaining} knowledgePointsLeft=${l.knowledgePointsLeft}")
        assertEquals(listOf(50, 0, 5, 2, 1), listOf(l.metatype, l.attributes, l.skillGroups, l.skills, l.knowledge))
        assertEquals(642, l.remaining)
        assertEquals(listOf(4, 4, 0), listOf(l.knowledgePoints, l.knowledgePointsUsed, l.knowledgePointsLeft))
        assertEquals(emptyList<String>(), l.problems)
        assertEquals(emptyList<String>(), l.notCounted)
    }

    /** Rebuilding Chummer's New_Skills character from New_Empty with the editor gives the same file (apart from random ids). */
    @Test
    fun buildingTheSkillsFileFromTheEmptyOneMatchesChummer() {
        val settings = settingsForTemplates()
        val want = load("New_Skills.chum5")
        val resolved = chummer.core.data.CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        val data = DataPack.load(File(src, "Chummer/data"), resolved.dirs)
        val ork = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.element.child("name")?.textContent?.trim() == "Ork" }
        // The file's own settings hash is used, since it cannot be calculated here.
        val c = NewCharacter.createKarma(
            data, settings,
            NewCharacterRequest(metatypeId = ork.key, settingsHash = want.root.text("settingshashcode")!!.trim().toInt(), customDataNames = resolved.dirs.map { it.name }),
        )
        val editor = CharacterEditor(c, data, translations::get, settings)
        fun knowledge(name: String) = data.byId.entries.first {
            it.value.file == "skills.xml" && (it.value.element.parentNode as? Element)?.tagName == "knowledgeskills" &&
                it.value.element.child("name")?.textContent?.trim() == name
        }.key
        assertTrue(editor.changeSkillGroup("Firearms", 1))
        assertTrue(editor.changeSkill("Heavy Weapons", 1))
        val blackMarkets = editor.addKnowledgeSkill(knowledge("Black Markets"), type = "Professional")
        assertTrue(editor.changeKnowledgeSkill(blackMarkets, 1, withKarma = true))
        editor.addKnowledgeSkill(knowledge("English"), native = true)
        val gangs = editor.addKnowledgeSkill(knowledge("Street Gangs: Seattle"))
        repeat(2) { assertTrue(editor.changeKnowledgeSkill(gangs, 1)) }
        val turkish = editor.addKnowledgeSkill(knowledge("Turkish"))
        repeat(2) { assertTrue(editor.changeKnowledgeSkill(turkish, 1)) }
        val a = canonNoGuids(want.root)
        val b = canonNoGuids(c.root)
        if (a != b) {
            val at = a.zip(b).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(a.length, b.length) else it }
            println("SKILLS DIFF at $at:")
            println("  Chummer: ${a.substring(maxOf(0, at - 150), minOf(a.length, at + 300))}")
            println("  ours:    ${b.substring(maxOf(0, at - 150), minOf(b.length, at + 300))}")
        }
        assertEquals(a, b)

        // The free points are used up, so the next knowledge rating costs karma; a native language cannot be raised.
        val before = KarmaLedger.of(c, settings, data).knowledge
        assertTrue(editor.changeKnowledgeSkill(turkish, 1))
        assertEquals(before + 3 * 1, KarmaLedger.of(c, settings, data).knowledge)
        val english = editor.addKnowledgeSkill(knowledge("English"), native = true)
        assertTrue(!editor.changeKnowledgeSkill(english, 1))
        // Group steps can be given back once, and not below zero.
        assertTrue(editor.changeSkillGroup("Firearms", -1))
        assertTrue(!editor.changeSkillGroup("Firearms", -1))
    }
}
