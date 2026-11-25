package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cyberware and bioware added from the data must be what Chummer wrote for the same items in Viper's file. */
class CyberwareEditorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private val ws = Regex("""\s+""")
    private val skip = setOf("guid", "notes", "notesColor", "sortorder", "overclocked", "extraessmultiplicativemultiplier", "parentid", "location", "extra", "wirelesson", "isgeneware", "cost", "maxrating", "subsystems") // Viper was saved with older data: Narco was not geneware yet, and Cybereyes had a FixedValues price, max rating 6 and different subsystems

    @Test
    fun topLevelWareMatchesViper() {
        var checked = 0
        val bad = ArrayList<String>()
        for (g in viper().root.child("cyberwares")!!.children("cyberware")) {
            val id = g.text("sourceid")?.trim().orEmpty()
            val entry = data[id]?.element ?: continue
            if (CyberwareEditor.problem(entry) != null || g.child("gears")?.children()?.isNotEmpty() == true) continue
            val scratch = viper()
            val ed = CyberwareEditor(scratch, data)
            val guid = ed.add(id, g.text("grade")?.trim().orEmpty(), g.text("rating")?.trim()?.toIntOrNull())
            val made = scratch.root.child("cyberwares")!!.children("cyberware").first { it.text("guid")?.trim() == guid }
            checked++
            for (k in g.children()) {
                if (k.tagName in skip || k.children().isNotEmpty()) continue
                val want = k.textContent.trim().replace(ws, " ")
                val got = made.child(k.tagName)?.textContent?.trim()?.replace(ws, " ")
                if (want != got) bad += "${g.text("name")}: ${k.tagName} Chummer=[$want] ours=[$got]"
            }
        }
        println("Ware checked $checked, ${bad.size} differences")
        bad.forEach { println("  WAREDIFF $it") }
        assertTrue(checked >= 5)
        assertTrue("ware differences: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun essenceAndCostFollowGradeAndRating() {
        val ed = CyberwareEditor(viper(), data)
        val dj = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Datajack" }.key
        assertEquals(1000.0, ed.cost(dj, "Standard", null)!!, 0.001)
        assertEquals(0.1, ed.essence(dj, "Standard", null)!!, 0.001)
        // Alphaware: more expensive, less essence.
        assertTrue(ed.cost(dj, "Alphaware", null)!! > 1000.0)
        assertTrue(ed.essence(dj, "Alphaware", null)!! < 0.1)
    }

    @Test
    fun buyingWareSpendsNuyenChangesEssenceAndUndoRestores() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val dj = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Biomonitor" }.key
        adv.log.manualNuyen(50000.0, "Test reward")
        val nuyen = adv.log.nuyen
        val ess = adv.essenceNow()
        val guid = adv.buyWare(dj, "Standard")!!
        assertEquals(nuyen - 500.0, adv.log.nuyen, 0.001)
        assertEquals(ess - 0.1, adv.essenceNow(), 0.011)
        assertTrue(adv.log.balanced())
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(nuyen, adv.log.nuyen, 0.001)
        assertEquals(ess, adv.essenceNow(), 0.001)
        assertTrue(!c.root.textContent.contains(guid))
    }

    @Test
    fun essenceBelowZeroIsRefused() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(100000000.0, "Test reward")
        val mr = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Muscle Replacement" }.key
        // Muscle Replacement costs essence equal to its rating; more than the character has left.
        var refused = false
        repeat(8) { if (adv.buyWare(mr, "Standard", 4) == null) refused = true }
        assertTrue(refused)
        assertTrue(adv.essenceNow() >= 0.0)
    }

    @Test
    fun plugInsAndDefaultPartsMatchViper() {
        val c = viper()
        val parentWare = c.root.child("cyberwares")!!.children("cyberware").first { it.text("name") == "Cybereyes Basic System" }
        val scratch = viper()
        val ed = CyberwareEditor(scratch, data)
        val parent = ed.add(parentWare.text("sourceid")!!.trim(), "Standard", 2)
        val made = ed.findWare(parent)!!
        // Image Link comes installed with the system.
        val defaults = made.child("children")!!.children("cyberware").map { it.text("name") }
        assertEquals(listOf("Image Link"), defaults)
        assertEquals(parent, made.child("children")!!.children("cyberware").first().text("parentid")?.trim())
        // The plug-ins Viper's character has are accepted, with their capacity counted.
        for (k in parentWare.child("children")!!.children("cyberware").filter { it.text("parentid")?.trim().isNullOrEmpty() }) {
            val id = k.text("sourceid")!!.trim()
            assertEquals(null, ed.plugProblem(parent, id))
            val childGuid = ed.add(id, "Standard", null, parentGuid = parent)
            val child = ed.findWare(childGuid)!!
            for (tag in listOf("name", "category", "ess", "capacity", "avail", "cost", "grade", "addtoparentess", "improvementsource")) {
                assertEquals("${k.text("name")} $tag", k.text(tag)?.trim(), child.text(tag)?.trim())
            }
        }
        assertEquals(8.0, ed.capacityOf(made), 0.001)
        assertEquals(8.0, ed.capacityUsed(made), 0.001) // Smartlink 3 + Flare 1 + Thermographic 2 + Eye Protectors 2
        // The system is now full: one more plug-in does not fit.
        val extra = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Low-Light Vision" }.key
        assertEquals("not enough capacity", ed.plugProblem(parent, extra))
        // Removing the system takes its plug-ins with it.
        assertTrue(ed.remove(parent))
        assertTrue(scratch.root.textContent.let { t -> parentWare.child("children")!!.children("cyberware").none { it.text("name") == "Smartlink" } || !t.contains(parent) })
    }

    @Test
    fun gearThatComesWithWareIsInstalled() {
        val scratch = viper()
        val ed = CyberwareEditor(scratch, data)
        val dj = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Datajack" }.key
        val w = ed.findWare(ed.add(dj, "Standard"))!!
        // Viper's older file names it "Universal Connector Cord (Meter)".
        assertEquals(listOf("Universal Connector Cord"), w.child("gears")!!.children("gear").map { it.text("name") })
    }

    @Test
    fun buyingAPlugInChecksCapacityAndPaysAtTheParentsGrade() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        val eyes = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Cybereyes Basic System" }.key
        val smartlink = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == "Smartlink" && it.value.category == "Eyeware" }.key
        val parent = adv.buyWare(eyes, "Alphaware", 2)!!
        val cap = adv.pluginCapacity()
        assertEquals(8.0, cap[parent]!!, 0.001)
        val before = adv.log.nuyen
        val child = adv.buyWare(smartlink, "Standard", null, parentGuid = parent)!!
        // Paid at the parent's grade (Alphaware costs 2.5 times), and the capacity is used.
        assertTrue(before - adv.log.nuyen > 4000.0)
        assertEquals(5.0, adv.pluginCapacity()[parent]!!, 0.001)
        assertEquals("Alphaware", CyberwareEditor(c, data).findWare(child)!!.text("grade"))
        assertTrue(adv.log.balanced())
        // Undoing the plug-in frees its capacity and refunds it.
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(before, adv.log.nuyen, 0.001)
        assertEquals(8.0, adv.pluginCapacity()[parent]!!, 0.001)
    }

    @Test
    fun limbUpgradesGetTheirRatingRangeFromTheLimbAndTheCharacter() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        fun id(name: String) = data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == name }.key
        val arm = adv.buyWare(id("Synthetic Full Arm"), "Used")!!
        val ed = CyberwareEditor(c, data)
        val entry = data[id("Customized Agility")]!!.element
        val (min, max) = ed.range(entry, ed.findWare(arm))
        assertEquals(4, min) // the limb's own 3, plus 1
        assertTrue("max $max", max >= 6)
        // The picker offers the limits; the price follows from them: (Rating - MinRating + 1) * 5000.
        val option = adv.pluginOptions(arm).first { it.name == "Customized Agility" }
        assertEquals(4, option.minRating)
        assertEquals(max, option.maxRating)
        assertEquals(5000.0, adv.wareCost(id("Customized Agility"), "Standard", 4, arm)!!, 0.01)
        assertEquals(15000.0, adv.wareCost(id("Customized Agility"), "Standard", 6, arm)!!, 0.01)
        val before = adv.log.nuyen
        val guid = adv.buyWare(id("Customized Agility"), "Used", 6, arm)!!
        assertEquals(before - adv.wareCost(id("Customized Agility"), "Used", 6, arm)!!, adv.log.nuyen, 0.01)
        val made = ed.findWare(guid)!!
        assertEquals("6", made.text("rating"))
        assertEquals("MinimumAGI+1", made.text("minrating"))
        assertEquals("MaximumAGI", made.text("maxrating"))
    }

    // ---- grade upgrades in career: only the difference in price is paid

    private fun wareId(name: String, category: String? = null) =
        data.byId.entries.first { it.value.file == "cyberware.xml" && it.value.name == name && (category == null || it.value.element.child("category")?.textContent?.trim() == category) }.key

    private fun gradeOf(c: CharacterFile, guid: String) = CyberwareEditor(c, data).findWare(guid)!!.text("grade")?.trim()

    @Test
    fun upgradingWareChargesOnlyTheDifferenceBetweenTheGrades() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        val ed = CyberwareEditor(c, data)
        val dj = wareId("Datajack")
        val guid = adv.buyWare(dj, "Standard")!!
        val offer = adv.wareUpgradeOffer(guid)!!
        assertEquals("Standard", offer.from)
        assertEquals("Alphaware", offer.to)
        assertEquals(ed.cost(dj, "Alphaware", null)!! - ed.cost(dj, "Standard", null)!!, offer.cost, 0.01)
        assertTrue(offer.affordable)
        val essenceBefore = adv.essenceNow()
        val before = adv.log.nuyen
        assertTrue(adv.upgradeWare(guid) != null)
        assertEquals("Alphaware", gradeOf(c, guid))
        assertEquals(before - offer.cost, adv.log.nuyen, 0.01)
        assertTrue("an upgrade gives essence back", adv.essenceNow() > essenceBefore)
        assertTrue(adv.log.balanced())
        // The next step is priced from the new grade, not from the item's first purchase.
        val next = adv.wareUpgradeOffer(guid)!!
        assertTrue("a step above Alphaware, got ${next.to}", next.to in setOf("Greyware", "Betaware"))
        assertEquals("Alphaware", next.from)
        assertEquals(ed.cost(dj, next.to, null)!! - ed.cost(dj, "Alphaware", null)!!, next.cost, 0.01)
        assertEquals(next, adv.wareUpgrades()[guid])
    }

    @Test
    fun upgradingStopsAtTheBestGrade() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(5000000.0, "Test reward")
        val guid = adv.buyWare(wareId("Datajack"), "Standard")!!
        val ed = CyberwareEditor(c, data)
        var steps = 0
        var essence = 1.0
        var price = 1.0
        while (true) {
            val offer = adv.wareUpgradeOffer(guid) ?: break
            assertTrue(adv.upgradeWare(guid) != null)
            // Every step gives more essence back and costs more than the one before.
            val e = data.gradeEssence(false, offer.to)!!
            assertTrue("${offer.to} must beat the last grade", e < essence && ed.gradeCostMultiplier(false, offer.to) > price)
            essence = e; price = ed.gradeCostMultiplier(false, offer.to)
            steps++
            assertTrue("runs away", steps < 12)
        }
        assertTrue("at least Alpha, Beta and Delta, got $steps", steps >= 3)
        assertNull(adv.upgradeWare(guid))
        assertFalse(adv.wareUpgrades().containsKey(guid))
    }

    @Test
    fun anUpgradeIsRefusedWhenTheCharacterCannotPay() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        val guid = adv.buyWare(wareId("Datajack"), "Standard")!!
        // Leave 100 nuyen: less than the upgrade costs.
        adv.log.manualNuyen(-(adv.log.nuyen - 100.0), "Spent elsewhere")
        val offer = adv.wareUpgradeOffer(guid)!!
        assertTrue(offer.cost > 100.0)
        assertFalse(offer.affordable)
        val entries = adv.log.entries().size
        assertNull(adv.upgradeWare(guid))
        assertEquals("Standard", gradeOf(c, guid))
        assertEquals(100.0, adv.log.nuyen, 0.01)
        assertEquals(entries, adv.log.entries().size)
    }

    @Test
    fun upgradingAnItemUpgradesItsPlugInsAndPaysForThemToo() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(1000000.0, "Test reward")
        val ed = CyberwareEditor(c, data)
        val eyes = wareId("Cybereyes Basic System")
        val parent = adv.buyWare(eyes, "Alphaware", 2)!!
        val child = adv.buyWare(wareId("Smartlink", "Eyeware"), "Standard", null, parentGuid = parent)!!
        val offer = adv.wareUpgradeOffer(parent)!!
        assertEquals("Alphaware", offer.from)
        // The price is the item's and the plug-in's base prices times the change in the grade multiplier.
        val base = ed.cost(eyes, "Standard", 2)!! + ed.cost(wareId("Smartlink", "Eyeware"), "Standard", null)!!
        assertEquals(base * (ed.gradeCostMultiplier(false, offer.to) - ed.gradeCostMultiplier(false, "Alphaware")), offer.cost, 0.01)
        // A plug-in is not offered an upgrade of its own.
        assertTrue(parent in adv.wareUpgrades().keys)
        assertFalse(child in adv.wareUpgrades().keys)
        assertNull(adv.wareUpgradeOffer(child))
        assertTrue(adv.upgradeWare(parent) != null)
        assertEquals(offer.to, gradeOf(c, parent))
        assertEquals(offer.to, gradeOf(c, child))
        assertTrue(adv.log.balanced())
    }

    @Test
    fun undoingAnUpgradeGivesTheNuyenAndTheOldGradeBack() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        val guid = adv.buyWare(wareId("Datajack"), "Standard")!!
        val before = adv.log.nuyen
        val first = adv.upgradeWare(guid)!!
        val second = adv.upgradeWare(guid)!!
        val top = gradeOf(c, guid)
        assertTrue(adv.log.entries().last().reason.startsWith("Upgraded Cyberware Datajack to $top"))
        assertTrue(adv.log.canUndo(adv.log.entries().last()))
        // The older upgrade cannot be undone while a newer one sits on top of it.
        assertFalse(adv.log.undo(first))
        assertEquals(top, gradeOf(c, guid))
        assertTrue(adv.log.undo(second))
        assertEquals("Alphaware", gradeOf(c, guid))
        assertTrue(adv.log.undo(first))
        assertEquals("Standard", gradeOf(c, guid))
        assertEquals(before, adv.log.nuyen, 0.01)
        assertTrue(adv.log.balanced())
    }

    @Test
    fun anUpgradeCannotBeUndoneOnceTheItemIsGone() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        val guid = adv.buyWare(wareId("Datajack"), "Standard")!!
        val entry = adv.upgradeWare(guid)!!
        val nuyen = adv.log.nuyen
        assertTrue(adv.discardPurchase(guid))
        assertFalse(adv.log.undo(entry))
        assertEquals(nuyen, adv.log.nuyen, 0.01)
    }

    @Test
    fun noUpgradeIsOfferedInCreationOrToAnItemThatForcesItsGrade() {
        val creation = viper()
        creation.root.child("created")!!.textContent = "False"
        assertNull(CareerAdvancement(creation, data, settings).wareUpgradeOffer(creation.root.child("cyberwares")!!.children("cyberware").first().text("guid")!!.trim()))
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(500000.0, "Test reward")
        val guid = adv.buyWare(wareId("Datajack"), "Standard")!!
        CyberwareEditor(c, data).findWare(guid)!!.child("forcegrade")!!.textContent = "Standard"
        assertNull(adv.wareUpgradeOffer(guid))
        assertNull(adv.upgradeWare(guid))
    }
}
