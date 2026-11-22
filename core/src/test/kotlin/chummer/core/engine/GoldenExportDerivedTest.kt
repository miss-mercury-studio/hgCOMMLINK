package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.parseXml
import chummer.core.text
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compares derived stats with Chummer's own print XML for Viper (reference/exports).
 *
 * The export was made with a different state than the saved file: the Kamikaze drug group was on, an
 * ungrouped custom improvement named "Spell" (Attribute CHA, rating 1, augmented 3) was active (seen in the
 * user's Improvements tab; it is not in the saved file), and the Social Situations armor set was equipped instead of the Runner Armor set
 * saved in the file (the user confirmed this), which is why there is no armor encumbrance. This test
 * replays that state, so it checks the calculations, not the state itself.
 */
class GoldenExportDerivedTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val dataDir = File(src, "Chummer/data")
    private val campaign = File(ref, "Viper_career")

    @Test
    fun viperDerivedStatsMatchChummerExport() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(dataDir, "settings.xml"), listOf(File(campaign, "settings")))
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
        val pack = DataPack.load(dataDir, dirs.dirs)
        val rules = settings.toRuleSettings()

        // The export's state: Kamikaze on, the "Spell" CHA +3 improvement, and no armor encumbrance.
        val spellCha = Improvement("Attribute", "Custom", "CHA", "", "", 0.0, 3.0, 0.0, 0.0, 0.0, 1.0, false, true, false, "")
        val improvements = Improvement.parseAll(c.root)
            .filter { it.source != "ArmorEncumbrance" }
            .map { if (it.customGroup == "Kamikaze") it.copy(enabled = true) else it } + spellCha
        val set = ImprovementSet(improvements, isCareer = true)
        val attrs = AttributeEngine.forSet(c, set, rules)

        val essence = EssenceEngine(set, pack, true, rules.essenceDecimals, rules.dontRoundEssenceInternally)
            .essence(6.0, c.augmentations())
        val d = DerivedStats(set, attrs, essence, settings)

        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        var checked = 0
        val misses = ArrayList<String>()
        fun expect(field: String, ours: Int) {
            val want = export.text(field)?.trim()?.toIntOrNull() ?: return
            checked++
            if (want != ours) misses += "$field: Chummer=$want ours=$ours"
        }
        // Attributes first, so a wrong state is visible before the derived values.
        val block = export.children("attributes").first { it.children().isNotEmpty() }
        for (a in block.children("attribute")) {
            val n = a.text("name_english") ?: continue
            if (n == "ESS") continue
            checked++
            val want = a.text("total")!!.trim().toInt()
            val got = attrs[n]?.totalValue ?: 0
            if (want != got) misses += "attribute $n: Chummer=$want ours=$got"
        }
        expect("limitphysical", d.limitPhysical)
        expect("limitmental", d.limitMental)
        expect("limitsocial", d.limitSocial)
        expect("limitastral", d.limitAstral)
        expect("initvalue", d.initiative.value)
        expect("initdice", d.initiative.dice)
        expect("physicalcm", d.physicalCM)
        expect("stuncm", d.stunCM)
        expect("cmoverflow", d.cmOverflow)
        expect("physicalcmthresholdoffset", d.physicalCMThresholdOffset)
        expect("stuncmthresholdoffset", d.stunCMThresholdOffset)
        expect("liftweight", d.liftWeight)
        expect("carryweight", d.carryWeight)
        expect("composure", d.composure)
        expect("judgeintentions", d.judgeIntentions)
        expect("memory", d.memory)
        expect("surprise", d.surprise)
        expect("liftandcarry", d.liftAndCarry)
        expect("fatigueresist", d.fatigueResist)
        expect("dodge", d.dodge)
        for ((field, v) in d.resistances()) expect(field, v)
        expect("physicalcmnaturalrecovery", d.physicalCMNaturalRecovery)
        // Movement: the export's full movement string.
        checked++
        val moveSet = set
        val movement = Movement.of(c, pack, moveSet, attrs)?.full()
        val wantMove = export.text("movement")!!.trim()
        if (movement != wantMove) misses += "movement: Chummer=$wantMove ours=$movement"
        // Essence from the engine against the export's total essence.
        val wantEss = export.text("totaless")!!.trim().toDouble()
        checked++
        if (kotlin.math.abs(wantEss - essence) > 0.005) misses += "totaless: Chummer=$wantEss ours=$essence"

        println("Export derived stats: $checked checked, ${misses.size} differences")
        misses.forEach { println("  DIFF $it") }
        assertTrue("regressed: ${misses.size} > $KNOWN_DIFFERENCES", misses.size <= KNOWN_DIFFERENCES)
        if (misses.size < KNOWN_DIFFERENCES) println("Improved: lower KNOWN_DIFFERENCES to ${misses.size}")
    }

    companion object {
        const val KNOWN_DIFFERENCES = 0
    }
}
