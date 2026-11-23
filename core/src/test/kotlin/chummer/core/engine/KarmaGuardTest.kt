package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** In creation a purchase may not take the karma below zero (`CharacterEditor.withinKarma`). */
class KarmaGuardTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }

    @Test
    fun purchasesStopAtZeroKarma() {
        val c = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
        val ed = CharacterEditor(c, data, settings = settings)
        val attrs = listOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL")
        var steps = 0
        var progressed = true
        while (progressed && steps < 400) {
            progressed = false
            for (a in attrs) {
                if (ed.withinKarma({ ed.changeAttribute(a, 1) }, { ed.changeAttribute(a, -1) })) { progressed = true; steps++ }
                assertTrue("overspent after $a: ${KarmaLedger.of(c, settings, data).remaining}", KarmaLedger.of(c, settings, data).remaining >= 0)
            }
        }
        val remaining = KarmaLedger.of(c, settings, data).remaining
        assertTrue("spent something: $steps steps", steps > 20)
        // Something is still below its maximum, and one more plain step would overspend.
        val open = attrs.first { a -> AttributeEngine.forFile(c, settings.toRuleSettings())[a]!!.let { it.value < it.totalMaximum } }
        assertTrue(ed.changeAttribute(open, 1))
        assertTrue("the guard held back a real overspend (left $remaining)", KarmaLedger.of(c, settings, data).remaining < 0)
        // Giving back is never refused by the guard.
        assertTrue(ed.withinKarma({ ed.changeAttribute(open, -1) }, { }))
        assertEquals(remaining, KarmaLedger.of(c, settings, data).remaining)
    }

    /** The same guard on skills: ranks are bought until the karma is gone and not one rank past it. */
    @Test
    fun skillPurchasesStopAtZeroKarma() {
        val c = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
        val ed = CharacterEditor(c, data, settings = settings)
        val names = Sheet.build(c, data, settings).activeSkills.map { it.name }
        var steps = 0
        var progressed = true
        while (progressed && steps < 600) {
            progressed = false
            for (n in names) {
                if (ed.withinKarma({ ed.changeSkill(n, 1) }, { ed.changeSkill(n, -1) })) { progressed = true; steps++ }
                assertTrue("overspent after $n: ${KarmaLedger.of(c, settings, data).remaining}", KarmaLedger.of(c, settings, data).remaining >= 0)
            }
        }
        assertTrue("bought $steps ranks", steps > 50)
        // One more plain rank on a skill below the maximum would overspend.
        val open = names.first { n -> ed.changeSkill(n, 1).also { if (it) ed.changeSkill(n, -1) } }
        assertTrue(ed.changeSkill(open, 1))
    }
}
