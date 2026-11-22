package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The quality list says which qualities need a choice and offers the options the data knows. */
class QualityChoiceTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find(CharacterIo.readFile(File(ref, "Viper_career.chum5")).root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    // The plain game data: Viper's profile leaves some books out.
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }

    @Test
    fun mentorSpiritOffersMentorsAndPrototypeTranshumanItsFourQualities() {
        val all = QualityCatalog.list(data)
        val mentor = all.first { it.name == "Mentor Spirit" }
        assertTrue(mentor.needsText)
        assertTrue("Bear" in mentor.choices)
        val proto = all.first { it.name == "Prototype Transhuman" }
        assertTrue(proto.needsText)
        assertEquals(listOf("Wanted", "Allergy (Common, Mild)", "Astral Beacon", "Insomnia (Basic)"), proto.choices)
    }

    @Test
    fun prototypeTranshumanAddsTheChosenQualityAndRecordsIt() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val id = data.byId.entries.first { it.value.file == "qualities.xml" && it.value.name == "Prototype Transhuman" }.key
        val q = CharacterEditor(c, data, settings = null).addQuality(id, extra = "Wanted")
        val imp = Improvement.parseAll(c.root).filter { it.sourceName == q && it.type == "SpecificQuality" }
        assertEquals(1, imp.size)
        val added = c.root.child("qualities")!!.children("quality").first { it.text("guid")?.trim() == imp.single().improvedName }
        assertEquals("Wanted", added.text("name"))
    }
}
