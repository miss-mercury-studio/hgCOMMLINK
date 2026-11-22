package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A new shapeshifter: what the metatype and its Human form give, checked against Mittens Chargen. */
class ShapeshifterTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }

    private fun vulpine(): chummer.core.CharacterFile {
        val meta = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Shapeshifter: Vulpine" }
        val variant = meta.value.element.child("metavariants")!!.children("metavariant").first { it.text("name") == "Human" }.text("id")!!
        return NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = meta.key, metavariantId = variant), PriorityChoice("A", "B", "E", "C", "D", "Mundane", true))
    }

    @Test
    fun aVulpineShapeshifterStartsAsMittensDoes() {
        val c = vulpine()
        val want = CharacterIo.readFile(File(src, "Chummer.Tests/TestFiles/Mittens Chargen.chum5"))
        for (tag in listOf("metatype", "metavariant", "metatypecategory", "metatypebp", "critter")) assertEquals(tag, want.root.text(tag), c.root.text(tag))
        // The bonus switched Magic on and showed the critter tab.
        assertEquals("True", c.root.text("magenabled"))
        // Shift is given free, does not count towards the limit, and stays.
        val shift = c.root.child("critterpowers")!!.children("critterpower").single()
        assertEquals("Shift (Metahuman Form)", shift.text("name")); assertEquals("False", shift.text("counttowardslimit")); assertEquals("0", shift.text("karma"))
        assertFalse(CritterPowerEditor(c, data).remove(shift.text("guid")!!))
        // The bite is a natural weapon in the Critter Powers category, tied to the metatype, and the unarmed attack is still there.
        val weapons = c.root.child("weapons")!!.children("weapon")
        val bite = weapons.first { it.text("name") == "Bite (Vulpine Form)" }
        val wantBite = want.root.child("weapons")!!.children("weapon").first { it.text("name") == "Bite (Vulpine Form)" }
        assertEquals(wantBite.children().map { it.tagName }, bite.children().map { it.tagName })
        for (tag in listOf("category", "type", "reach", "damage", "ap", "useskill", "accuracy", "parentid")) assertEquals(tag, wantBite.text(tag), bite.text(tag))
        assertTrue(weapons.any { it.text("name") == "Unarmed Attack" })
        // The quality the Human form lets you buy off is kept apart.
        val uneducated = c.root.child("qualities")!!.children("quality").first { it.text("name") == "Uneducated" }
        assertEquals("MetatypeRemovable", uneducated.text("qualitysource"))
        // And the sheet shows the critter powers.
        assertEquals(listOf("Shift (Metahuman Form)"), Sheet.build(c, data, settings).critterPowers!!.map { it.name })
    }
}
