package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.parseXml
import chummer.core.text
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compares the skill engine with Chummer's own print XML for Viper (reference/exports).
 * The export reflects the live Chummer session, which had some temporary drug effects switched on,
 * so attribute totals are taken from the export rather than recomputed; everything else is computed.
 */
class GoldenExportSkillsTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val dataDir = File(src, "Chummer/data")
    private val campaign = File(ref, "Viper_career")

    @Test
    fun viperSkillsMatchChummerExport() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(dataDir, "settings.xml"), listOf(File(campaign, "settings")))
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
        val pack = DataPack.load(dataDir, dirs.dirs)
        val rules = settings.toRuleSettings()

        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        val attrBlock = export.children("attributes").first { it.children().isNotEmpty() }
        val attrs = attrBlock.children("attribute").mapNotNull { a ->
            val n = a.text("name_english") ?: return@mapNotNull null
            n to AttributeResult(n, 0, 0, 0, a.text("total")!!.trim().toInt())
        }.toMap()

        val career = true
        val set = ImprovementSet(Improvement.parseAll(c.root), career)
        val engine = SkillEngine(set, attrs, rules, career, magicEnabled = false, resonanceEnabled = false)
        val ours = SkillEngine.skillsOf(c, pack).associateBy { it.name }
        val groups = c.skillGroups()

        var checked = 0
        val misses = ArrayList<String>()
        for (s in export.child("skills")!!.children("skill")) {
            val name = s.text("name")!!.trim()
            val raw = ours[name] ?: run { misses += "$name: not in the saved file"; return@run null } ?: continue
            checked++
            val res = engine.calculate(raw, groups[raw.group]?.first ?: 0, groups[raw.group]?.second ?: 0)
            fun expect(field: String, want: Any?, got: Any?) {
                if (want != got) misses += "$name $field: Chummer=$want ours=$got"
            }
            expect("attribute", s.text("attribute")?.trim(), raw.attribute)
            expect("skillgroup", s.text("skillgroup")?.trim()?.takeIf { it != "None" }.orEmpty(), raw.group)
            expect("rating", s.text("rating")?.trim()?.toInt(), res.rating)
            expect("ratingmax", s.text("ratingmax")?.trim()?.toInt(), res.ratingMaximum)
            expect("default", s.text("default")?.trim().equals("True", true), res.canDefault)
            // total is the dice pool; native-language skills show a special value, so skip those.
            if (!raw.isNativeLanguage) expect("total", s.text("total")?.trim()?.toIntOrNull(), res.pool)
        }
        println("Export skills: $checked checked, ${misses.size} differences")
        misses.forEach { println("  DIFF $it") }
        assertTrue("no skills compared", checked > 60)
        assertTrue("regressed: ${misses.size} > $KNOWN_DIFFERENCES", misses.size <= KNOWN_DIFFERENCES)
        if (misses.size < KNOWN_DIFFERENCES) println("Improved: lower KNOWN_DIFFERENCES to ${misses.size}")
    }

    companion object {
        const val KNOWN_DIFFERENCES = 0
    }
}
