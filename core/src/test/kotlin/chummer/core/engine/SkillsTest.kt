package chummer.core.engine

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
import java.io.File

/**
 * Skills. Chummer does not store skill totals in the file, so there is no stored value to compare with.
 * These tests check the wiring (data lookup, attributes, settings) and hand-derived expectations;
 * a comparison against Chummer's own output is still needed.
 */
class SkillsTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val dataDir = File(src, "Chummer/data")
    private val campaign = File(ref, "Viper_career")

    private val viper by lazy { CharacterIo.readFile(File(ref, "Viper_career.chum5")) }
    private val settings by lazy {
        CharacterSettings.find(viper.root.text("settings").orEmpty(), File(dataDir, "settings.xml"), listOf(File(campaign, "settings")))
    }
    private val pack by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
        DataPack.load(dataDir, dirs.dirs)
    }

    private fun engineFor(c: chummer.core.CharacterFile, rules: RuleSettings, data: chummer.core.GameData): Pair<SkillEngine, List<SkillEngine.Raw>> {
        val career = c.root.text("created")?.trim().equals("True", true)
        val set = ImprovementSet(Improvement.parseAll(c.root), career)
        val attrs = AttributeEngine.forFile(c, rules)
        fun flag(t: String) = c.root.text(t)?.trim().equals("True", true)
        val engine = SkillEngine(set, attrs, rules, career, flag("magenabled"), flag("resenabled"), flag("critter"))
        return engine to SkillEngine.skillsOf(c, data)
    }

    @Test
    fun viperSkillsMatchHandDerivedPools() {
        val rules = settings.toRuleSettings()
        val (engine, skills) = engineFor(viper, rules, pack)
        val attrs = AttributeEngine.forFile(viper, rules)
        val groups = viper.skillGroups()
        val rated = skills.filter { it.basePoints + it.karmaPoints > 0 }
        assertTrue("Viper has rated skills", rated.size >= 20)
        for (r in rated) {
            val result = engine.calculate(r, groups[r.group]?.first ?: 0, groups[r.group]?.second ?: 0)
            // Viper's skills have no skill improvements of their own, so the rating is base + karma.
            assertEquals("${r.name} rating", r.basePoints + r.karmaPoints, result.rating)
            val attr = attrs.getValue(r.attribute).totalValue
            assertEquals("${r.name} pool", attr + result.rating, result.pool)
        }
    }

    @Test
    fun unratedSkillsDefaultToAttributeMinusOne() {
        val rules = settings.toRuleSettings()
        val (engine, skills) = engineFor(viper, rules, pack)
        val attrs = AttributeEngine.forFile(viper, rules)
        val unrated = skills.filter { !it.isKnowledge && it.basePoints + it.karmaPoints == 0 && it.canDefaultByData && it.attribute !in setOf("MAG", "RES") }
        assertTrue(unrated.isNotEmpty())
        for (r in unrated) {
            val res = engine.calculate(r)
            assertEquals("${r.name} default pool", maxOf(0, attrs.getValue(r.attribute).totalValue - 1), res.pool)
        }
        // Skills that cannot default have no pool at all.
        val noDefault = skills.filter { !it.isKnowledge && it.basePoints + it.karmaPoints == 0 && !it.canDefaultByData && it.attribute !in setOf("MAG", "RES") }
        for (r in noDefault) assertEquals("${r.name} cannot default", 0, engine.calculate(r).pool)
    }

    @Test
    fun magicSkillsAreDisabledWithoutMagic() {
        // Viper lists no magical skills (Chummer hides them without magic), so build one by hand.
        val rules = settings.toRuleSettings()
        val (engine, _) = engineFor(viper, rules, pack)
        val spellcasting = SkillEngine.Raw("Spellcasting", "Magical Active", "MAG", "Sorcery", true, 0, 3, false)
        val res = engine.calculate(spellcasting)
        assertEquals(false, res.enabled)
        assertEquals(0, res.pool)
    }

    @Test
    fun surveyAllFixtures() {
        val base = DataPack.load(dataDir)
        val fixtures = File(src, "Chummer.Tests/TestFiles").listFiles { f -> f.extension == "chum5" }.orEmpty().sortedBy { it.name }
        var total = 0
        var unresolved = 0
        var overMax = 0
        for (f in fixtures) {
            val c = CharacterIo.readFile(f)
            val rules = CharacterSettings.find(c.root.text("settings").orEmpty(), File(dataDir, "settings.xml")).toRuleSettings()
            val (engine, skills) = engineFor(c, rules, base)
            val groups = c.skillGroups()
            for (r in skills) {
                total++
                // Files from Chummer before 5.2 do not flag knowledge skills, so they look like unknown active skills.
                if (!r.isKnowledge && r.attribute.isEmpty() && c.appVersion.startsWith("5.2")) unresolved++
                val res = engine.calculate(r, groups[r.group]?.first ?: 0, groups[r.group]?.second ?: 0)
                if (res.rating > res.ratingMaximum) overMax++
                assertTrue("${f.name} ${r.name}: negative pool", res.pool >= 0)
            }
        }
        println("Skills surveyed: $total, with no data entry for the skill: $unresolved, rating over maximum: $overMax")
        assertTrue("every active skill resolves to game data", unresolved == 0)
    }

    @Test
    fun printViperSkills() {
        val rules = settings.toRuleSettings()
        val (engine, skills) = engineFor(viper, rules, pack)
        val attrs = AttributeEngine.forFile(viper, rules)
        for (r in skills.filter { it.basePoints + it.karmaPoints > 0 }.take(12)) {
            val res = engine.calculate(r)
            println("  %-26s %-4s attr=%d rating=%d pool=%d".format(r.name, r.attribute, attrs[r.attribute]?.totalValue ?: -1, res.rating, res.pool))
        }
    }
}
