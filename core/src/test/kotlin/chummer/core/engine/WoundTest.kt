package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Damage on the condition monitors causes a wound modifier on initiative and every skill pool (`Character.RefreshWoundPenalties`). */
class WoundTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }

    @Test
    fun everyThreeBoxesCostOneDie() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val ed = CharacterEditor(c, data, settings = settings)
        val clean = Sheet.build(c, data, settings)
        assertEquals(0, clean.damage.wound)
        val skill = clean.activeSkills.first { it.rating > 0 }
        repeat(2) { assertTrue(ed.changeDamage("physical", 1)) }
        assertEquals(0, Sheet.build(c, data, settings).damage.wound)
        assertTrue(ed.changeDamage("physical", 1))
        var s = Sheet.build(c, data, settings)
        assertEquals(-1, s.damage.wound)
        assertEquals(clean.initiativeValue - 1, s.initiativeValue)
        assertEquals(skill.pool - 1, s.activeSkills.first { it.name == skill.name }.pool)
        // Stun counts too, and the two add up.
        repeat(3) { ed.changeDamage("stun", 1) }
        s = Sheet.build(c, data, settings)
        assertEquals(-2, s.damage.wound)
        assertEquals(3, s.damage.stun)
        // Healing restores everything.
        ed.healAll()
        s = Sheet.build(c, data, settings)
        assertEquals(0, s.damage.wound)
        assertEquals(skill.pool, s.activeSkills.first { it.name == skill.name }.pool)
    }

    @Test
    fun theTracksHaveEnds() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val ed = CharacterEditor(c, data, settings = settings)
        val d = Sheet.build(c, data, settings).damage
        assertFalse(ed.changeDamage("stun", -1))
        repeat(d.stunMax) { assertTrue(ed.changeDamage("stun", 1)) }
        assertFalse(ed.changeDamage("stun", 1))
        repeat(d.physicalMax + d.overflowMax) { assertTrue(ed.changeDamage("physical", 1)) }
        assertFalse(ed.changeDamage("physical", 1))
    }

    @Test
    fun astralInitiativeIsTwiceIntuitionPlusWounds() {
        val c = CharacterIo.readFile(File(ref, "Mag_Spent.chum5"))
        val ed = CharacterEditor(c, data, settings = settings)
        val s = Sheet.build(c, data, settings)
        val int = s.attributes.first { it.name == "INT" }.total
        assertEquals(int * 2, s.astralInitiative!!.value)
        assertEquals(3, s.astralInitiative!!.dice)
        repeat(3) { ed.changeDamage("physical", 1) }
        assertEquals(int * 2 - 1, Sheet.build(c, data, settings).astralInitiative!!.value)
        // A mundane character has none.
        assertEquals(null, Sheet.build(CharacterIo.readFile(File(ref, "Viper_career.chum5")), data, settings).astralInitiative)
    }

    @Test
    fun recoveryPoolsFollowChummer() {
        // Physical: twice Body; stun: Body plus Willpower (Viper has Body 5 and Willpower 5, so the old Body + Willpower also gave 10).
        val c = CharacterIo.readFile(File(ref, "Mag_Spent.chum5"))
        val s = Sheet.build(c, data, settings)
        val bod = s.attributes.first { it.name == "BOD" }.total
        val wil = s.attributes.first { it.name == "WIL" }.total
        assertEquals(2 * bod, s.damage.physicalRecovery)
        assertEquals(bod + wil, s.damage.stunRecovery)
    }

    @Test
    fun fractionalNuyenKeepsItsCents() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        c.root.getElementsByTagName("nuyen").item(0).textContent = "247.50"
        assertEquals("247.5", c.summary().nuyen)
        c.root.getElementsByTagName("nuyen").item(0).textContent = "6678.00"
        assertEquals("6678", c.summary().nuyen)
    }
}
