package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.children
import chummer.core.child
import chummer.core.data.CharacterSettings
import chummer.core.parseXml
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Weapon figures for Viper against Chummer's print XML, in the export's state (Kamikaze on, the "Spell"
 * improvement), so STR and the physical limit match what Chummer used.
 */
class GoldenWeaponsTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")

    @Test
    fun viperWeaponsMatchChummerExport() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")))
        val rules = settings.toRuleSettings()
        val spellCha = Improvement("Attribute", "Custom", "CHA", "", "", 0.0, 3.0, 0.0, 0.0, 0.0, 1.0, false, true, false, "")
        val improvements = Improvement.parseAll(c.root)
            .filter { it.source != "ArmorEncumbrance" }
            .map { if (it.customGroup == "Kamikaze") it.copy(enabled = true) else it } + spellCha
        val set = ImprovementSet(improvements, isCareer = true)
        val attrs = AttributeEngine.forSet(c, set, rules)
        val essence = EssenceEngine(set, chummer.core.DataPack.load(File(src, "Chummer/data")), true, rules.essenceDecimals, rules.dontRoundEssenceInternally)
            .essence(6.0, c.augmentations())
        val derived = DerivedStats(set, attrs, essence, settings)

        val ours = WeaponEngine(c.root, set, attrs.getValue("STR").totalValue, derived.limitPhysical, ammoSlots = exportAmmoSlots(c)).all()
        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        val theirs = export.child("weapons")!!.children("weapon")
        assertEquals(theirs.size, ours.size)

        var checked = 0
        val misses = ArrayList<String>()
        for ((i, w) in ours.withIndex()) {
            val e = theirs[i]
            assertEquals(e.text("name")!!.trim(), w.name)
            fun expect(field: String, got: String) {
                val want = e.text(field)?.trim().orEmpty()
                checked++
                if (want != got.trim()) misses += "${w.name} $field: Chummer='$want' ours='$got'"
            }
            expect("damage", w.damage)
            expect("ap", w.ap)
            expect("accuracy", w.accuracy)
            expect("rc", w.rc)
            expect("conceal", w.conceal)
            expect("reach", w.reach)
            expect("mode", w.mode)
            expect("ammo", w.ammo)
        }
        println("Export weapons: $checked checked, ${misses.size} differences")
        misses.forEach { println("  DIFF $it") }
        assertTrue("regressed: ${misses.size} > $KNOWN_DIFFERENCES", misses.size <= KNOWN_DIFFERENCES)
        if (misses.size < KNOWN_DIFFERENCES) println("Improved: lower KNOWN_DIFFERENCES to ${misses.size}")
    }

    /** In the export's state the Ingram had its second magazine (Regular Ammo) loaded, not the saved third (Stick-n-Shock). */
    private fun exportAmmoSlots(c: chummer.core.CharacterFile): Map<String, Int> =
        c.root.child("weapons")!!.children("weapon").filter { it.text("name") == "Ingram Smartgun X" }
            .associate { it.text("guid")!!.trim() to 2 }

    companion object {
        const val KNOWN_DIFFERENCES = 0
    }
}
