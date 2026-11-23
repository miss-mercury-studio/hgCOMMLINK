package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Selling a bought item in career: it leaves the character and the nuyen comes back in the log. */
class SellItemTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
    private fun named(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    @Test
    fun soldGearGivesBackItsShareOfThePrice() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c, data, settings)
        val start = log.nuyen
        val guid = adv.buyGear(named("gear.xml", "gear", "Flashlight"), qty = 4.0)!!
        val paid = start - log.nuyen
        assertTrue(paid > 0)
        val got = adv.sellPurchase(guid, 50)!!
        assertEquals(paid / 2, got, 0.01)
        assertEquals(start - paid + got, log.nuyen, 0.01)
        assertTrue(c.root.child("gears")!!.children("gear").none { it.text("guid")?.trim() == guid })
        val entry = log.entries().last()
        assertEquals("SellItem", entry.undoKind)
        assertFalse(log.canUndo(entry))
        // Nothing left to sell.
        assertNull(adv.sellPurchase(guid, 50))
    }

    @Test
    fun discardingTakesAnItemOutWithNoNuyenOrLogEntry() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c, data, settings)
        val guid = adv.buyGear(named("gear.xml", "gear", "Flashlight"))!!
        val nuyen = log.nuyen
        val entries = log.entries().size
        assertTrue(adv.discardPurchase(guid))
        assertEquals(nuyen, log.nuyen, 0.001)
        assertEquals(entries, log.entries().size)
        assertTrue(c.root.child("gears")!!.children("gear").none { it.text("guid")?.trim() == guid })
    }

    @Test
    fun nothingIsSoldInCreation() {
        val c = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
        assertNull(CareerAdvancement(c, data, settings).sellPurchase("00000000-0000-0000-0000-000000000001", 100))
    }

    @Test
    fun fixedValuesCapacityReadsAsARange() {
        assertEquals("[1] to [6] by rating", ArmorWeaponCatalog.readableCapacity("FixedValues([1],[2],[3],[4],[5],[6])"))
        assertEquals("[3]", ArmorWeaponCatalog.readableCapacity("[3]"))
    }
}
