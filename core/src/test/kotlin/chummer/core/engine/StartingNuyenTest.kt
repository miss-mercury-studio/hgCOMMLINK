package chummer.core.engine

import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** Issue #2: the priority nuyen must be spendable, and stored, as soon as the character is created. */
class StartingNuyenTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun id(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    private fun fresh(): chummer.core.CharacterFile {
        val choice = PriorityChoice("E", "A", "B", "C", "D", "Magician", true) // resources D: 50,000
        return NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "Test", metatypeId = id("metatypes.xml", "metatype", "Human")), choice)
    }

    @Test
    fun theStoredNuyenIsTheBudgetFromTheStart() {
        val c = fresh()
        assertEquals(50000.0, NuyenLedger.of(c, settings, data).budget, 0.001)
        assertEquals("50000", c.root.text("nuyen"))
    }

    @Test
    fun theSheetShowsTheBudgetBeforeAnyEdit() {
        val c = fresh()
        assertEquals(50000.0, ExpenseLog(c).nuyen, 0.001)
    }

    @Test
    fun gearCanBeBoughtWithoutEditingTheBudgetFirst() {
        val c = fresh()
        val adv = CareerAdvancement(c, data, settings)
        assertNotNull(adv.buyGear(id("gear.xml", "gear", "Flashlight")))
        assertEquals("49975", c.root.text("nuyen"))
    }
}
