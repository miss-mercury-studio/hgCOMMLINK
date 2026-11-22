package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mentor spirit choices: what Dolphin offers, and the improvements a choice makes and unmakes. */
class MentorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }

    @Test
    fun aMentorSpiritsChoicesAreAppliedAndCanBeChanged() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val id = data.byId.entries.first { it.value.file == "qualities.xml" && it.value.name == "Mentor Spirit" }.key
        val quality = CharacterEditor(c, data, settings = null).addQuality(id, extra = "Dolphin")
        val ed = MentorEditor(c, data)
        val dolphin = ed.mentors().single()
        assertEquals("Dolphin", dolphin.name)
        // Two sets: skill (Artisan or Pilot Watercraft) and magician or adept; nothing is chosen yet.
        assertEquals(listOf(1, 2), dolphin.sets.map { it.set })
        assertTrue(dolphin.sets.all { it.chosen.isEmpty() })
        fun mine() = Improvement.parseAll(c.root).filter { it.source == "MentorSpirit" && it.sourceName == dolphin.guid && it.type != "MentorSpirit" }
        assertTrue(mine().isEmpty())
        assertTrue(ed.choose(dolphin.guid, 1, "+2 dice on Artisan tests"))
        assertEquals(listOf("Artisan"), mine().map { it.improvedName })
        assertEquals("+2 dice on Artisan tests", ed.mentors().single().sets.first { it.set == 1 }.chosen)
        // Changing the choice replaces what it made; the second set adds its own.
        assertTrue(ed.choose(dolphin.guid, 1, "+2 on Pilot Watercraft Tests"))
        assertEquals(listOf("Pilot Watercraft"), mine().map { it.improvedName })
        assertTrue(ed.choose(dolphin.guid, 2, "Magician: +2 for health spells, preparations, and spell rituals."))
        assertEquals(setOf("Pilot Watercraft", "Health"), mine().map { it.improvedName }.toSet())
        // A choice the spirit does not offer is refused; clearing a set removes what it made.
        assertFalse(ed.choose(dolphin.guid, 1, "No such choice"))
        assertTrue(ed.choose(dolphin.guid, 2, ""))
        assertEquals(listOf("Pilot Watercraft"), mine().map { it.improvedName })
        // The stored layout keeps the chosen bonus in choice1.
        val m = c.root.child("mentorspirits")!!.children("mentorspirit").single()
        assertEquals("specificskill", m.child("choice1")!!.children().single().tagName)
        // Taking the quality away takes the spirit and everything it made.
        assertTrue(CharacterEditor(c, data, settings = null).removeItem(quality))
        assertTrue(ed.mentors().isEmpty())
        assertTrue(Improvement.parseAll(c.root).none { it.source == "MentorSpirit" })
    }
}
