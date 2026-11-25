package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/**
 * Gaps left by the per-feature tests: what wearing armor does to the computed total, undoing a purchase after its stack was merged, the armor
 * mod picker's other rules, ammunition that needs no weapon type, the file layout of a new set, switching a plug-in's improvements, and what the sheet says.
 */
class GearArmorCoverageTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }

    private fun gearNamed(c: CharacterFile, name: String) = c.root.child("gears")!!.children("gear").first { it.text("name")?.trim() == name }
    private fun entryId(name: String, category: String? = null) =
        data.byId.entries.first { it.value.file == "gear.xml" && it.value.element.tagName == "gear" && it.value.name == name && (category == null || it.value.category == category) }.key
    private fun armorEntry(name: String) = data.byId.values.first { it.file == "armor.xml" && it.element.tagName == "armor" && it.name == name }.element

    // ---- wearing armor changes the figures

    private fun armorTotal(c: CharacterFile): Int {
        val rules = settings.toRuleSettings()
        val set = ImprovementSet(Improvement.parseAll(c.root), isCareer = true)
        val str = AttributeEngine.forSet(c, set, rules).getValue("STR").totalValue
        return ArmorEngine(
            c.armors(), set, str,
            uncappedAccessories = settings.bool("uncappedarmoraccessorybonuses"),
            noEncumbrance = settings.bool("noarmorencumbrance"),
            armorDegradation = settings.bool("armordegredation"),
        ).rating("Armor")
    }

    @Test
    fun wearingAndTakingOffArmorMovesTheArmorTotal() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val start = armorTotal(c)
        val worn = c.armors().filter { it.equipped }.map { it.guid }
        assertTrue("Viper wears armor as saved", start > 0 && worn.isNotEmpty())
        for (a in c.armors()) ed.setArmorEquipped(a.guid, false)
        // What is left with nothing worn comes from elsewhere (ware, qualities); it is less than with the armor on.
        val bare = armorTotal(c)
        assertTrue("less armor with nothing worn", bare < start)
        val jacket = c.armors().first { it.name == "Armor Jacket" }
        ed.setArmorEquipped(jacket.guid, true)
        assertTrue("the jacket alone adds its armor", armorTotal(c) >= bare + 12)
        for (g in worn) ed.setArmorEquipped(g, true)
        assertEquals("back as saved", start, armorTotal(c))
    }

    // ---- undo after a merge

    @Test
    fun undoingAPurchaseWhoseStackWasMergedAwayLeavesTheCharacterAlone() {
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val chip = entryId("Datachip")
        // Viper already carries a Datachip stack; a merge folds it in too.
        val carried = GearStacks.quantity(gearNamed(c, "Datachip"))
        val first = a.buyGear(chip, null, 10.0)!!
        val second = a.buyGear(chip, null, 10.0)!!
        assertNotEquals("a datachip is not ammunition, so each purchase is its own line", first, second)
        assertTrue(a.mergeGear(first))
        val entries = a.log.entries()
        val nuyen = a.log.nuyen
        // The second purchase's line is gone, so its entry cannot be taken back; nothing changes.
        assertFalse(a.log.undo(entries.last().guid))
        assertEquals(entries.size, a.log.entries().size)
        assertEquals(nuyen, a.log.nuyen, 0.001)
        assertEquals(carried + 20.0, GearStacks.quantity(GearEditor(c, data, settings).anywhere(first)!!), 0.0)
        // The first purchase's entry still works, and takes back only its own quantity.
        assertTrue(a.log.undo(entries[entries.size - 2].guid))
        assertEquals(carried + 10.0, GearStacks.quantity(GearEditor(c, data, settings).anywhere(first)!!), 0.0)
    }

    // ---- the armor mod picker's other rules

    @Test
    fun forcedModCategoryReplacesTheUsualOnes() {
        val jacket = armorEntry("Armor Jacket").cloneNode(true) as Element
        jacket.appendChild(jacket.ownerDocument.createElement("forcemodcategory").also { it.textContent = "Clothing" })
        val offered = ArmorWeaponCatalog.armorModsFor(data, jacket, emptyList())
        assertTrue(offered.isNotEmpty())
        assertTrue("only the forced category, not General", offered.all { it.category == "Clothing" })
    }

    @Test
    fun anArmorsOwnModCategoryIsOfferedOnlyOnThatArmor() {
        val armor = data.byId.values.first { it.file == "armor.xml" && it.element.tagName == "armor" && it.element.child("addmodcategory") != null }.element
        val own = armor.child("addmodcategory")!!.textContent.trim()
        val onIt = ArmorWeaponCatalog.armorModsFor(data, armor, emptyList())
        assertTrue("the extra category's mods are offered on its armor", onIt.any { it.category == own })
        val elsewhere = ArmorWeaponCatalog.armorModsFor(data, armorEntry("Armor Jacket"), emptyList())
        assertTrue("and not on another", elsewhere.none { it.category == own })
    }

    @Test
    fun aModThatNeedsAnotherModIsOfferedOnlyOnceItIsFitted() {
        val jacket = armorEntry("Armor Jacket")
        val helmet = "Responsive Interface Gear: Helmet"
        val need = "Responsive Interface Gear: Armor"
        assertFalse(ArmorWeaponCatalog.armorModsFor(data, jacket, emptyList()).any { it.name == helmet })
        assertTrue(ArmorWeaponCatalog.armorModsFor(data, jacket, listOf(need)).any { it.name == helmet })
    }

    // ---- ammunition

    @Test
    fun ammunitionThatNeedsNoWeaponTypeIsNotAskedForOne() {
        val options = GearCatalog.list(data)
        assertTrue("grenades are not bought for a kind of gun", options.first { it.name == "Minigrenade: Gas" }.ammoCategories.isEmpty())
        assertTrue(options.first { it.name == "Ammo: Regular Ammo" }.ammoCategories.isNotEmpty())
    }

    // ---- what a new set looks like in the file

    @Test
    fun aNewSetHasTheFieldsChummerWrites() {
        val c = viper()
        val existing = c.root.child("armorlocations")!!.children("location").first()
        val guid = ArmorWeaponEditor(c, data).addArmorSet("Coverage Set")!!
        val made = c.root.child("armorlocations")!!.children("location").first { it.text("guid")?.trim() == guid }
        assertEquals(existing.children().map { it.tagName }, made.children().map { it.tagName })
        assertEquals("Coverage Set", made.text("name")?.trim())
    }

    // ---- plug-ins and their improvements

    private fun addGearImprovement(c: CharacterFile, sourceName: String, enabled: String): Element {
        val imps = c.root.child("improvements")!!
        val i = c.doc.createElement("improvement")
        for ((k, v) in listOf("improvementsource" to "Gear", "sourcename" to sourceName, "improvementttype" to "Skill", "enabled" to enabled)) i.appendChild(c.doc.createElement(k).also { it.textContent = v })
        imps.appendChild(i)
        return i
    }

    @Test
    fun switchingAPlugInSwitchesItsImprovementsAndAnUnbondedFocusStaysOff() {
        val c = viper()
        val ed = GearEditor(c, data, settings)
        val part = gearNamed(c, "Novacoke").child("children")!!.children("gear").first()
        val id = part.text("guid")!!.trim()
        val imp = addGearImprovement(c, id, "1")
        assertTrue(ed.setEquipped(id, false))
        assertEquals("0", imp.text("enabled")?.trim())
        assertTrue(ed.setEquipped(id, true))
        assertEquals("1", imp.text("enabled")?.trim())
        // A focus that is not bonded gives nothing when it is switched on.
        part.child("category")!!.textContent = "Enchanting Foci"
        part.child("bonded")!!.textContent = "False"
        imp.child("enabled")!!.textContent = "0"
        assertTrue(ed.setEquipped(id, true))
        assertEquals("0", imp.text("enabled")?.trim())
    }

    // ---- the sheet's view of all this

    @Test
    fun theSheetShowsPartsPlugInsAndEmptySets() {
        val c = viper()
        ArmorWeaponEditor(c, data).addArmorSet("Empty Set")
        val chipId = entryId("Datachip")
        GearEditor(c, data, settings).addGear(chipId, null, 5.0)
        GearEditor(c, data, settings).addGear(chipId, null, 5.0)
        val s = Sheet.build(c, data, settings).sections
        val sin = s.gear.first { it.name == "Fake SIN" }
        assertEquals(8, sin.parts.size)
        assertTrue(sin.pluggable)
        assertFalse(s.gear.first { it.name == "Medkit" }.pluggable)
        assertTrue("an empty set still shows", s.armorSets.any { it.name == "Empty Set" && it.items.isEmpty() && it.guid.isNotEmpty() })
        assertTrue("identical stacks offer a merge", s.gear.filter { it.name == "Datachip" }.let { it.size >= 2 && it.all { l -> l.mergeable } })
        assertEquals("the Extra shows on ammunition", "Assault Rifles", s.gear.first { it.name == "Ammo: Regular Ammo" && it.extra == "Assault Rifles" }.extra)
    }

    // ---- gear that asks which license it is for

    @Test
    fun aFakeLicenseAsksWhichLicenseAndKeepsTheAnswer() {
        assertTrue("Chummer's license names are kept", data.licenses.containsAll(listOf("Medical License", "Concealed Carry Permit")))
        val c = viper()
        val a = CareerAdvancement(c, data, settings)
        val sin = gearNamed(c, "Fake SIN")
        val parent = sin.text("guid")!!.trim()
        val license = a.gearPluginOptions(parent).first { it.name == "Fake License" }
        assertTrue(license.needsRestricted)
        assertTrue(license.restrictedChoices.contains("Medical License"))
        assertFalse("a credstick asks nothing", a.gearPluginOptions(parent).first { it.name.startsWith("Certified Credstick, Standard") }.needsRestricted)
        val before = sin.child("children")!!.children("gear").size
        val guid = a.buyGearPlugin(parent, license.id, 3, 1.0, "Pilot License")!!
        val bought = sin.child("children")!!.children("gear").first { it.text("guid")?.trim() == guid }
        assertEquals(before + 1, sin.child("children")!!.children("gear").size)
        assertEquals("Pilot License", bought.text("extra")?.trim())
        // Like Viper's own licenses, it also records a Restricted improvement named for the license.
        val imp = c.root.child("improvements")!!.children("improvement").first { it.text("sourcename")?.trim() == guid }
        assertEquals("Restricted", imp.text("improvementttype")?.trim())
        assertEquals("Pilot License", imp.text("improvedname")?.trim())
        assertTrue(a.log.undo(a.log.entries().last().guid))
        assertEquals(before, sin.child("children")!!.children("gear").size)
    }

    @Test
    fun theIncludesLineCountsRepeatedParts() {
        val c = viper()
        val s = Sheet.build(c, data, settings).sections
        val sin = s.gear.first { it.name == "Fake SIN" }
        assertEquals(listOf("Fake License x8"), sin.children)
        assertEquals(8, sin.parts.size)
        assertEquals("one part keeps its name and what it is for", listOf("Pharmaceutical"), s.gear.first { it.name == "Woad" }.children)
    }
}
