package chummer.core.engine

import chummer.core.CharacterFile
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

/** Changing the grade and rating of cyberware and bioware already bought in creation: the price follows, and what cannot be paid is refused. */
class WareEditTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
    private fun fresh() = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
    private fun id(name: String, category: String? = null) =
        data.byId.entries.first { (it.value.file == "cyberware.xml" || it.value.file == "bioware.xml") && it.value.name == name && (category == null || it.value.element.child("category")?.textContent?.trim() == category) }.key

    /** A creation character with [karmaIntoNuyen] karma turned into nuyen (2000 each). */
    private fun withNuyen(karmaIntoNuyen: Int): Pair<CharacterFile, CareerAdvancement> {
        val c = fresh()
        val editor = CharacterEditor(c, data, settings = settings)
        repeat(karmaIntoNuyen) { assertTrue(editor.changeNuyenBp(1)) }
        return c to CareerAdvancement(c, data, settings)
    }

    private fun item(c: CharacterFile, guid: String) = CyberwareEditor(c, data).findWare(guid)!!
    private fun left(c: CharacterFile) = NuyenLedger.of(c, settings, data).remaining

    @Test
    fun editingIsOnlyForCreation() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val vs = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings")))
        val guid = c.root.child("cyberwares")!!.children("cyberware").first { it.text("name") == "Datajack" }.text("guid")!!.trim()
        val adv = CareerAdvancement(c, data, vs)
        assertNull(adv.wareEditInfo(guid))
        assertFalse(adv.editWare(guid, "Alphaware", null))
    }

    @Test
    fun changingTheGradeChangesThePriceAndTheEssence() {
        val (c, adv) = withNuyen(5)
        val guid = adv.buyWare(id("Datajack"), "Standard")!!
        val info = adv.wareEditInfo(guid)!!
        assertTrue(info.gradeEditable)
        assertEquals("Standard", info.grade)
        assertEquals(0, info.maxRating) // a datajack has no rating
        val before = left(c)
        val essence = adv.essenceNow()
        val preview = adv.wareEditPreview(guid, "Alphaware", null)!!
        assertNull(preview.problem)
        assertEquals(1000.0, preview.cost, 0.01)
        assertEquals(1200.0, preview.newCost, 0.01)
        assertTrue(adv.editWare(guid, "Alphaware", null))
        assertEquals("Alphaware", item(c, guid).text("grade"))
        assertEquals(before - 200.0, left(c), 0.01)
        assertTrue("less essence is used", adv.essenceNow() > essence)
        assertEquals((before - 200.0).toLong().toString(), c.root.text("nuyen"))
        // And back down: the nuyen returns.
        assertTrue(adv.editWare(guid, "Standard", null))
        assertEquals(before, left(c), 0.01)
        assertTrue("no log in creation", ExpenseLog(c).entries().isEmpty())
    }

    @Test
    fun changingTheRatingChangesThePrice() {
        val (c, adv) = withNuyen(10)
        val guid = adv.buyWare(id("Orthoskin"), "Standard", 1)!!
        val info = adv.wareEditInfo(guid)!!
        assertEquals(1, info.rating)
        assertEquals(1, info.minRating)
        assertTrue("max rating ${info.maxRating}", info.maxRating >= 3)
        val oneRating = left(c)
        assertTrue(adv.editWare(guid, "Standard", 2))
        assertEquals("2", item(c, guid).text("rating"))
        // Orthoskin costs 6000 a rating.
        assertEquals(oneRating - 6000.0, left(c), 0.01)
        assertTrue(adv.editWare(guid, "Standard", 1))
        assertEquals(oneRating, left(c), 0.01)
    }

    @Test
    fun aRatingOverTheAvailabilityLimitIsRefused() {
        val (c, adv) = withNuyen(10)
        val guid = adv.buyWare(id("Orthoskin"), "Standard", 1)!!
        // The profile's limit is 8, and Orthoskin is (Rating * 4)R: rating 3 is 12.
        assertEquals("availability is over the limit", adv.wareEditPreview(guid, "Standard", 3)!!.problem)
        assertFalse(adv.editWare(guid, "Standard", 3))
        assertEquals("1", item(c, guid).text("rating"))
    }

    @Test
    fun changingTheRatingRewritesTheBonusAtTheNewRating() {
        val (c, adv) = withNuyen(10)
        val guid = adv.buyWare(id("Bone Density Augmentation"), "Standard", 1)!!
        fun mine() = c.root.child("improvements")?.children("improvement")?.filter { it.text("sourcename")?.trim().equals(guid, true) }.orEmpty()
        val before = mine()
        assertTrue("the item writes improvements", before.isNotEmpty())
        val atOne = before.map { it.text("val")?.trim() }
        assertTrue(adv.editWare(guid, "Standard", 2))
        val after = mine()
        assertEquals("one set of improvements, not two", before.size, after.size)
        assertTrue("the bonus follows the rating: $atOne then ${after.map { it.text("val")?.trim() }}", after.map { it.text("val")?.trim() } != atOne)
        assertTrue(adv.editWare(guid, "Standard", 1))
        assertEquals(atOne, mine().map { it.text("val")?.trim() })
    }

    @Test
    fun aChangeThatCannotBePaidIsRefusedAndLeavesTheItemAlone() {
        val (c, adv) = withNuyen(3) // 6000: exactly one rating of Orthoskin
        val guid = adv.buyWare(id("Orthoskin"), "Standard", 1)!!
        val remaining = left(c)
        assertEquals(0.0, remaining, 0.01)
        // Rating 2 would cost another 6000: more than is left.
        assertEquals("not enough nuyen", adv.wareEditPreview(guid, "Standard", 2)!!.problem)
        assertFalse(adv.editWare(guid, "Standard", 2))
        assertEquals("1", item(c, guid).text("rating"))
        assertEquals("Standard", item(c, guid).text("grade"))
        assertEquals(remaining, left(c), 0.01)
    }

    @Test
    fun plugInsAreInTheParentsPriceAndFollowItsGrade() {
        val (c, adv) = withNuyen(10)
        val eyes = adv.buyWare(id("Cybereyes Basic System"), "Standard", 1)!!
        val smartlink = adv.buyWare(id("Smartlink", "Eyeware"), "Standard", null, parentGuid = eyes)!!
        assertNull("a plug-in with no rating has nothing to edit", adv.wareEditInfo(smartlink))
        val parentInfo = adv.wareEditInfo(eyes)!!
        assertTrue(parentInfo.gradeEditable)
        val before = left(c)
        val preview = adv.wareEditPreview(eyes, "Alphaware", parentInfo.rating)!!
        // The plug-in is priced into the change: (item + Smartlink) at 1.2 instead of 1.
        assertTrue(preview.newCost - preview.cost > 0.2 * 4000.0)
        assertTrue(adv.editWare(eyes, "Alphaware", parentInfo.rating))
        assertEquals("Alphaware", item(c, smartlink).text("grade"))
        assertEquals(before - (preview.newCost - preview.cost), left(c), 0.01)
    }

    @Test
    fun anItemThatForcesItsGradeHasNoGradeToChoose() {
        val (c, adv) = withNuyen(10)
        val guid = adv.buyWare(id("Datajack"), "Standard")!!
        item(c, guid).child("forcegrade")!!.textContent = "Standard"
        assertNull(adv.wareEditInfo(guid))
        assertFalse(adv.editWare(guid, "Alphaware", null))
        assertEquals("Standard", item(c, guid).text("grade"))
    }

    @Test
    fun theSettingsBannedGradesStillApplyInCreation() {
        val (c, adv) = withNuyen(10)
        val guid = adv.buyWare(id("Datajack"), "Standard")!!
        val banned = settings.bannedWareGrades.first()
        assertEquals("that grade is not allowed", adv.wareEditPreview(guid, banned, null)!!.problem)
        assertFalse(adv.editWare(guid, banned, null))
        assertEquals("Standard", item(c, guid).text("grade"))
    }
}
