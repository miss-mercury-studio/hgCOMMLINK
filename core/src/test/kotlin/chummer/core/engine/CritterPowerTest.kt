package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Critter powers: the layout against Mittens Chargen, the karma, rated powers, and career purchases with undo. */
class CritterPowerTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun power(name: String) = CritterPowerEditor(CharacterIo.readFile(File(ref, "Viper_career.chum5")), data).options().first { it.name == name }

    @Test
    fun aPowerIsSavedAsChummerSavesIt() {
        val want = CharacterIo.readFile(File(src, "Chummer.Tests/TestFiles/Mittens Chargen.chum5")).root.child("critterpowers")!!.children("critterpower").single()
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val ed = CritterPowerEditor(c, data)
        val shift = ed.options().first { it.name == "Shift (Metahuman Form)" }
        val guid = ed.add(shift.id)!!
        val made = c.root.child("critterpowers")!!.children("critterpower").single()
        assertEquals(want.children().map { it.tagName }, made.children().map { it.tagName })
        for (tag in listOf("name", "rating", "category", "type", "action", "range", "duration", "grade", "source", "karma", "points")) assertEquals(tag, want.text(tag), made.text(tag))
        assertEquals(guid, made.text("guid"))
        assertNull(ed.add(shift.id)) // not twice
        assertTrue(ed.options().none { it.name == shift.name })
        assertTrue(ed.remove(guid))
        assertTrue(ed.powers().isEmpty())
    }

    @Test
    fun aRatedPowerNeedsARatingAndAnUnratedOneRefusesOne() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val ed = CritterPowerEditor(c, data)
        val toughness = ed.options().first { it.name == "Toughness" }
        assertTrue(toughness.maxRating > 0)
        assertNull(ed.add(toughness.id, 0))
        assertNotNull(ed.add(toughness.id, 3))
        assertEquals("3", ed.powers().single().extra)
        val plain = ed.options().first { it.maxRating == 0 }
        assertNull(ed.add(plain.id, 2))
    }

    @Test
    fun karmaIsSummedByTheLedgerAndPaidInCareer() {
        val viper = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val adv = CareerAdvancement(viper, data, CharacterSettings.find(viper.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))))
        adv.log.manualKarma(200, "Reward")
        val priced = adv.critterPowers().options().first { it.karma > 0 && it.maxRating == 0 }
        val start = adv.log.karma
        val guid = adv.addCritterPower(priced.id)!!
        assertEquals(start - priced.karma, adv.log.karma)
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(start, adv.log.karma)
        assertFalse(adv.critterPowers().remove(guid))
        // Not enough karma: nothing changes.
        adv.log.manualKarma(-adv.log.karma, "Spend all")
        assertNull(adv.addCritterPower(priced.id))
    }
}
