package chummer.core.data

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Viper's campaign files: the settings profile and the San Francisco Showdown custom data.
 * These check that settings resolve by GUID, that all seven custom data folders are found,
 * and that the amend files change the data the way Chummer would.
 */
class ViperCampaignTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")
    private val dataDir = File(src, "Chummer/data")
    private val character by lazy { CharacterIo.readFile(File(ref, "Viper_career.chum5")) }
    private val settings by lazy {
        CharacterSettings.find(character.root.text("settings").orEmpty(), File(dataDir, "settings.xml"), listOf(File(campaign, "settings")))
    }
    private val dirs by lazy {
        CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
    }
    private val base by lazy { DataPack.load(dataDir) }
    private val custom by lazy { DataPack.load(dataDir, dirs.dirs) }

    private fun named(pack: DataPack, file: String, name: String) =
        pack.byId.values.firstOrNull { it.file == file && it.name == name }

    @Test
    fun settingsResolveByGuid() {
        assertFalse("must not be the fallback", settings.isFallback)
        assertEquals("San Francisco Showdown", settings.name)
        assertEquals(character.root.text("settings"), settings.id)
        assertEquals(4, settings.int("limbcount"))
        assertEquals(18, settings.books.size)
        assertEquals(7, settings.customData.size)
        assertEquals(character.root.child("sources")!!.children("source").map { it.textContent }, settings.books)
        assertEquals(listOf("Betaware", "Deltaware", "Gammaware", "Omegaware", "Greyware"), settings.bannedWareGrades)
    }

    @Test
    fun settingsExpressionsEvaluate() {
        assertEquals(75.0, settings.evaluate("liftlimitexpression", mapOf("STR" to 5.0)), 0.0)
        assertEquals(50.0, settings.evaluate("carrylimitexpression", mapOf("STR" to 5.0)), 0.0)
        assertEquals(12.0, settings.evaluate("knowledgepointsexpression", mapOf("INTUnaug" to 3.0, "LOGUnaug" to 3.0)), 0.0)
        val rules = settings.toRuleSettings()
        assertEquals(4, rules.limbCount)
        assertEquals(setOf("torso", "skull"), rules.excludeLimbSlots)
    }

    @Test
    fun legacyDefaultFallsBackToFirstBuiltIn() {
        val s = CharacterSettings.find("default.xml", File(dataDir, "settings.xml"))
        assertTrue(s.isFallback)
        assertEquals("Standard", s.name)
        assertEquals(6, s.int("limbcount"))
    }

    @Test
    fun allSevenCustomDataFoldersAreFound() {
        assertTrue("missing: ${dirs.missing}", dirs.missing.isEmpty())
        assertEquals(
            listOf(
                "Chrome Flesh Stealth Errata", "Forbidden Arcana Stealth Errata", "Cyberlimbs Cannot Add Headware",
                "Education Qualities Apply Karma Discount in Create Mode", "Rainforest Carbine Missions Errata",
                "Unrestricted Kill Code Qualities", "San Francisco Showdown",
            ),
            dirs.dirs.map { it.name },
        )
        // Viper's character file lists the same folders by name.
        val inFile = character.root.child("customdatadirectorynames")!!.children("directoryname").map { it.textContent }
        assertEquals(inFile, dirs.dirs.map { it.name })
    }

    @Test
    fun amendFilesChangeTheData() {
        // Present in the base data, removed by San Francisco Showdown.
        for ((file, name) in listOf("spells.xml" to "Comet", "qualities.xml" to "Born Rich", "skills.xml" to "Artificing", "cyberware.xml" to "Radar Sensor")) {
            assertNotNull("$name should exist in the base data", named(base, file, name))
            assertNull("$name should be removed by the amend", named(custom, file, name))
        }
        // Added by custom_gear.xml.
        assertNull(named(base, "gear.xml", "Kludged Attack Dongle"))
        assertNotNull(custom["ce6d5e99-2cb7-4992-8930-c576ba99ff22"])
    }

    @Test
    fun metatypeAmendHidesNonMetahumans() {
        fun hidden(pack: DataPack) = pack.byId.values.filter { it.file == "metatypes.xml" && it.element.tagName == "metatype" && it.element.child("hide") != null }
            .map { it.name }
        assertTrue("nothing is hidden in the base data", hidden(base).isEmpty() || hidden(base).size < hidden(custom).size)
        val categories = custom.byId.values.filter { it.file == "metatypes.xml" && it.element.tagName == "metatype" }
            .groupBy { it.category == "Metahuman" }
        assertTrue(categories.getValue(false).all { it.element.child("hide") != null })
    }

    @Test
    fun amendWeaponFileIsIgnoredBecauseItsNameDoesNotMatchWeaponsXml() {
        // The folder has amend_weapon.xml (singular); the base file is weapons.xml, so Chummer never applies it.
        val id = "7bc2f035-ce0d-4f0a-8c14-dd62fdb3f4f4"
        assertEquals(base[id]!!.element.text("damage"), custom[id]!!.element.text("damage"))
    }

    @Test
    fun skillSpecializationAmends() {
        fun specs(pack: DataPack, skill: String) =
            named(pack, "skills.xml", skill)?.element?.child("specs")?.children("spec")?.map { it.textContent }
        println("Automatics specs: base=${specs(base, "Automatics")} custom=${specs(custom, "Automatics")}")
        println("Pistols specs:    base=${specs(base, "Pistols")} custom=${specs(custom, "Pistols")}")
    }

    @Test
    fun customDataLoadTime() {
        DataPack.load(dataDir) // warm up the JIT so the two timings are comparable
        val t0 = System.nanoTime()
        DataPack.load(dataDir)
        val t1 = System.nanoTime()
        DataPack.load(dataDir, dirs.dirs)
        val t2 = System.nanoTime()
        println("Load time: base ${(t1 - t0) / 1_000_000} ms, with 7 custom data folders ${(t2 - t1) / 1_000_000} ms")
    }

    @Test
    fun everyViperSourceIdResolvesInTheCustomisedData() {
        val missing = character.sourceIds().filter { custom[it.second] == null }
        assertTrue("unresolved with campaign data: $missing", missing.isEmpty())
    }
}
