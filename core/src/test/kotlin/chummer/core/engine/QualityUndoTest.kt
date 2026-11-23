package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Buying off a negative quality can be undone: the quality comes back with its improvements and the karma returns. */
class QualityUndoTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }

    @Test
    fun removingANegativeQualityCanBeUndone() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c, data, settings)
        log.manualKarma(1000, "test")
        val negative = c.root.child("qualities")!!.children("quality").first { it.text("qualitytype")?.trim() == "Negative" && it.text("sourceid")?.trim().orEmpty().isNotEmpty() }
        val name = negative.text("name")!!.trim()
        val guid = negative.text("guid")!!.trim()
        val before = ExpenseLog(c, data, settings).karma
        val countBefore = c.root.child("qualities")!!.children("quality").count { it.text("name")?.trim() == name }
        val improvementsBefore = Improvement.parseAll(c.root).size
        assertTrue(adv.removeQuality(guid))
        assertTrue(c.root.child("qualities")!!.children("quality").none { it.text("guid")?.trim() == guid })
        assertEquals(countBefore - 1, c.root.child("qualities")!!.children("quality").count { it.text("name")?.trim() == name })
        val entry = log.entries().last()
        assertEquals("RemoveQuality", entry.undoKind)
        assertTrue(log.canUndo(entry))
        assertTrue(log.undo(entry.guid))
        assertEquals(countBefore, c.root.child("qualities")!!.children("quality").count { it.text("name")?.trim() == name })
        assertEquals(before, ExpenseLog(c, data, settings).karma)
        assertEquals(improvementsBefore, Improvement.parseAll(c.root).size)
    }
}
