package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Adding, raising and removing adept powers against the power points MAG gives. */
class PowerEditingTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private fun load(name: String) = CharacterIo.readFile(src.walkTopDown().first { it.name == name })
    private fun settings(c: CharacterFile) = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"))
    private fun power(name: String) = data.byId.entries.first { it.value.file == "powers.xml" && it.value.element.tagName == "power" && it.value.name == name }.key

    @Test
    fun powerPointsLimitWhatCanBeAdded() {
        val c = load("Ghile Mear.chum5")
        val ed = CharacterEditor(c, data, settings = settings(c))
        // The character has spent all of its points (2 of 2): nothing more fits.
        assertEquals("not enough power points", ed.powerProblem(power("Adrenaline Boost")))
        val reflexes = c.root.child("powers")!!.children("power").first { it.text("name") == "Improved Reflexes" }.text("guid")!!.trim()
        assertFalse(ed.changePowerLevel(reflexes, 1))
        // Taking a power out frees its points, and it can be taken again.
        val before = PowerPoints.of(c, settings(c), data).left
        assertTrue(ed.removePower(reflexes))
        assertTrue(PowerPoints.of(c, settings(c), data).left > before)
        assertNull(ed.powerProblem(power("Improved Reflexes")))
        val again = ed.addPower(power("Improved Reflexes"), rating = 1)
        assertNotNull(c.root.child("powers")!!.children("power").firstOrNull { it.text("guid")?.trim() == again })
        assertEquals("already taken", ed.powerProblem(power("Improved Reflexes")))
    }

    @Test
    fun levelsChangeTheImprovementsAndStayWithinTheLimit() {
        val c = load("Ocelot2.0.chum5")
        val s = settings(c)
        val ed = CharacterEditor(c, data, settings = s)
        val reflexes = c.root.child("powers")!!.children("power").firstOrNull { it.text("name") == "Improved Reflexes" }
        // Make room, then add Improved Reflexes at level 1 and raise it.
        c.root.child("powers")!!.children("power").filter { it.text("name") != "Improved Reflexes" }.forEach { ed.removePower(it.text("guid")!!.trim()) }
        if (reflexes == null) ed.addPower(power("Improved Reflexes"), 1)
        val guid = c.root.child("powers")!!.children("power").first { it.text("name") == "Improved Reflexes" }.text("guid")!!.trim()
        val base = (c.root.child("powers")!!.children("power").first { it.text("guid")?.trim() == guid }.text("rating")?.trim()?.toIntOrNull() ?: 1)
        fun dice() = Improvement.parseAll(c.root).filter { it.sourceName == guid && it.type == "InitiativeDice" }.sumOf { it.value * it.rating }
        val max = PowerPoints.of(c, s, data).powers.first { it.guid == guid }.maxLevels
        assertTrue(ed.changePowerLevel(guid, -1) || base == 1)
        val now = c.root.child("powers")!!.children("power").first { it.text("guid")?.trim() == guid }.text("rating")!!.trim().toInt()
        assertEquals(now.toDouble(), dice(), 0.001)
        // Past the limit it stops.
        repeat(max + 2) { ed.changePowerLevel(guid, 1) }
        assertTrue(c.root.child("powers")!!.children("power").first { it.text("guid")?.trim() == guid }.text("rating")!!.trim().toInt() <= max)
    }

    @Test
    fun aNonAdeptCannotTakePowers() {
        val c = load("Wesson.chum5")
        c.root.child("adept")!!.textContent = "False"
        assertEquals("the character is not an adept", CharacterEditor(c, data, settings = settings(c)).powerProblem(power("Combat Sense")))
    }
}
