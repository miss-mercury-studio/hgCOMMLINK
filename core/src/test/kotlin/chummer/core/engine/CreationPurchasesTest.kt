package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Buying gear in creation: the karma-to-nuyen budget, the availability limit, removal, and what carries into the career. */
class CreationPurchasesTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
    private fun fresh() = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
    private fun named(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    @Test
    fun karmaBuysNuyenAndPurchasesSpendIt() {
        val c = fresh()
        val editor = CharacterEditor(c, data, settings = settings)
        val adv = CareerAdvancement(c, data, settings)
        val flash = named("gear.xml", "gear", "Flashlight")
        // No karma in nuyen yet: nothing to spend.
        assertNull(adv.buyGear(flash))
        assertTrue(editor.changeNuyenBp(1))
        assertEquals(2000.0, NuyenLedger.of(c, settings, data).budget, 0.001)
        assertEquals("2000", c.root.text("nuyen"))
        // It counts against the karma budget too.
        assertEquals(1, KarmaLedger.of(c, settings, data).nuyen)
        val guid = adv.buyGear(flash, qty = 4.0)!!
        assertEquals(1900.0, NuyenLedger.of(c, settings, data).remaining, 0.001)
        assertEquals("1900", c.root.text("nuyen"))
        // No expense entries in creation.
        assertTrue(ExpenseLog(c).entries().isEmpty())
        // Taking the item out gives the nuyen back.
        assertTrue(adv.removePurchase(guid))
        assertEquals(2000.0, NuyenLedger.of(c, settings, data).remaining, 0.001)
    }

    @Test
    fun nuyenKarmaStopsAtTheSettingsMaximum() {
        val c = fresh()
        val editor = CharacterEditor(c, data, settings = settings)
        val max = settings.int("nuyenmaxbp", 10)
        repeat(max) { assertTrue(editor.changeNuyenBp(1)) }
        assertFalse(editor.changeNuyenBp(1))
        repeat(max) { assertTrue(editor.changeNuyenBp(-1)) }
        assertFalse(editor.changeNuyenBp(-1))
    }

    @Test
    fun availabilityOverTheLimitCannotBeBought() {
        val c = fresh()
        CharacterEditor(c, data, settings = settings).also { repeat(10) { _ -> it.changeNuyenBp(1) } }
        val adv = CareerAdvancement(c, data, settings)
        val limit = settings.int("availability", 12)
        val tooRare = data.byId.entries.firstOrNull { (_, e) ->
            e.file == "gear.xml" && e.element.tagName == "gear" && (Availability.parse(e.element.getElementsByTagName("avail").item(0)?.textContent.orEmpty(), 1)?.number ?: 0) > limit &&
                (chummer.core.engine.GearEditor.unitCost(e.element, 1) ?: 1e12) < 15000.0
        }
        assertNotNull("some gear is over the limit", tooRare)
        assertNull(adv.buyGear(tooRare!!.key))
    }

    @Test
    fun overspendingShowsInTheReviewAndFinishKeepsWhatIsLeft() {
        val c = fresh()
        val editor = CharacterEditor(c, data, settings = settings)
        val adv = CareerAdvancement(c, data, settings)
        editor.changeNuyenBp(1)
        adv.buyGear(named("gear.xml", "gear", "Flashlight"), qty = 2.0)
        assertEquals(1950.0, NuyenLedger.of(c, settings, data).remaining, 0.001)
        // Taking the karma back leaves more spent than the budget gives.
        editor.changeNuyenBp(-1)
        assertTrue(CreationReview.of(c, settings, data).problems.any { it.startsWith("Nuyen overspent") })
        editor.changeNuyenBp(1)
        assertFalse(CreationReview.of(c, settings, data).problems.any { it.startsWith("Nuyen overspent") })
        // Finishing keeps the leftover up to the carry-over (200 by default), and the lifestyle roll comes on top.
        val plan = CharacterFinisher.plan(c, settings, data)
        assertEquals(minOf(1950, settings.int("nuyencarryover", 200)), plan.nuyenKept)
    }
}
