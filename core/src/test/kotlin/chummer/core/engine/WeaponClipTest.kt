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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Clips: the slots a weapon and its Spare Clip accessories have, loading and topping up from loose ammunition, firing, unloading and switching the active clip. */
class WeaponClipTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }

    private fun weapon(c: CharacterFile, name: String) = c.root.child("weapons")!!.children("weapon").first { it.text("name") == name }
    private fun guid(w: org.w3c.dom.Element) = w.text("guid")!!.trim()
    private fun totals(c: CharacterFile, name: String): WeaponTotals =
        WeaponEngine(c.root, ImprovementSet(Improvement.parseAll(c.root), isCareer = true), 5, 5).all().first { it.name == name }
    private fun gears(c: CharacterFile) = c.root.child("gears")!!.children("gear")
    private fun rounds(c: CharacterFile, ammoName: String) = gears(c).filter { it.text("name") == ammoName }.sumOf { it.text("qty")!!.trim().toDouble() }
    private val spareClip by lazy { data.byId.entries.first { it.value.file == "weapons.xml" && it.value.element.tagName == "accessory" && it.value.name == "Spare Clip" }.key }

    /** Viper's Ingram Smartgun X (one 32-round clip, loose Regular, Subsonic and Stick-n-Shock ammunition for it), with its clips emptied. */
    private fun emptiedIngram(): Triple<CharacterFile, ArmorWeaponEditor, String> {
        val c = viper()
        val w = weapon(c, "Ingram Smartgun X")
        w.child("clips")?.let { w.removeChild(it) }
        // Start from the weapon's own single slot: take off the Spare Clips Viper's file gives it.
        w.child("accessories")?.children("accessory")?.filter { (it.text("ammoslots")?.trim()?.toIntOrNull() ?: 0) > 0 }?.forEach { it.parentNode.removeChild(it) }
        w.child("activeammoslot")!!.textContent = "1"
        return Triple(c, ArmorWeaponEditor(c, data), guid(w))
    }

    private fun looseFor(ed: ArmorWeaponEditor, weapon: String, name: String = "Ammo: Regular Ammo") = ed.compatibleAmmo(weapon).first { it.name == name }

    @Test
    fun theSlotsAreTheWeaponsOwnAndThoseSpareClipsAdd() {
        val c = viper()
        val alpha = weapon(c, "Ares Alpha")
        val spares = alpha.child("accessories")!!.children("accessory").sumOf { it.text("ammoslots")?.trim()?.toIntOrNull() ?: 0 }
        val t = totals(c, "Ares Alpha")
        assertEquals(1 + spares, t.clips.size)
        assertEquals((1..t.clips.size).toList(), t.clips.map { it.slot })
        assertEquals("", t.clips.first().owner)
        assertTrue(t.clips.drop(1).all { it.owner == "Spare Clip" })
        assertEquals(1, t.clips.count { it.active })
        assertEquals(42, t.clips.first().capacity)
        // What the file says is loaded is what the clips report.
        val filled = alpha.child("clips")!!.children("clip").map { it.text("count")!!.trim().toInt() }
        assertEquals(filled, t.clips.map { it.rounds }.take(filled.size))
        assertTrue(t.clips.first().ammo.startsWith("Ammo: "))
    }

    @Test
    fun aWeaponWithNoSpareClipHasOneSlot() {
        val t = totals(viper(), "Steyr TMP")
        assertEquals(1, t.clips.size)
        assertEquals(30, t.clips[0].rounds)
        assertEquals(30, t.clips[0].capacity)
    }

    @Test
    fun addingASpareClipAddsAnEmptySlotAndRemovingItTakesTheSlotAway() {
        val (c, ed, w) = emptiedIngram()
        assertEquals(1, ed.clipSlotCount(w))
        val acc = ed.addAccessory(w, spareClip)
        assertEquals(2, ed.clipSlotCount(w))
        val t = totals(c, "Ingram Smartgun X")
        assertEquals(2, t.clips.size)
        assertEquals("Spare Clip", t.clips[1].owner)
        assertEquals(0, t.clips[1].rounds)
        // The new slot takes ammunition.
        val stack = looseFor(ed, w)
        assertTrue(ed.loadAmmo(w, stack.guid, 2))
        assertEquals(32, totals(c, "Ingram Smartgun X").clips[1].rounds)
        assertTrue(ed.setActiveClip(w, 2))
        // Removing the accessory gives the rounds back and puts the active clip on the first.
        val before = rounds(c, stack.name)
        assertTrue(ed.removeAccessory(acc))
        assertEquals(1, ed.clipSlotCount(w))
        assertEquals(1, totals(c, "Ingram Smartgun X").clips.size)
        assertEquals(1, totals(c, "Ingram Smartgun X").clips.single { it.active }.slot)
        assertEquals(before, rounds(c, stack.name), 0.001) // rounds are moved, not lost
    }

    @Test
    fun removingAnEarlierSpareClipMovesLaterClipsUp() {
        val (c, ed, w) = emptiedIngram()
        val first = ed.addAccessory(w, spareClip)
        ed.addAccessory(w, spareClip)
        assertEquals(3, ed.clipSlotCount(w))
        val stack = looseFor(ed, w)
        assertTrue(ed.loadAmmo(w, stack.guid, 3, rounds = 7))
        assertTrue(ed.setActiveClip(w, 3))
        assertTrue(ed.removeAccessory(first))
        val t = totals(c, "Ingram Smartgun X")
        assertEquals(2, t.clips.size)
        // The seven rounds were in slot 3; with slot 2 gone they are in slot 2, and the active clip followed them.
        assertEquals(7, t.clips[1].rounds)
        assertEquals(2, t.clips.single { it.active }.slot)
    }

    @Test
    fun onlyAmmunitionThatFitsTheWeaponIsOffered() {
        val (_, ed, w) = emptiedIngram()
        val offered = ed.compatibleAmmo(w)
        assertTrue(offered.isNotEmpty())
        assertTrue("every offer is ammunition", offered.all { it.name.startsWith("Ammo: ") })
        // A machine pistol takes no arrows and no grenades.
        assertTrue(offered.none { it.name.startsWith("Arrow") || it.name.startsWith("Bolt") || it.name.contains("renade") })
        // Ammunition already in a clip is not loose: a clip holding some means it is not offered.
        val c = viper()
        val loadedIds = c.root.child("weapons")!!.children("weapon").flatMap { it.child("clips")?.children("clip").orEmpty() }.map { it.text("id")!!.trim().lowercase() }.toSet()
        val edAll = ArmorWeaponEditor(c, data)
        assertTrue(edAll.compatibleAmmo(guid(weapon(c, "Ares Alpha"))).none { it.guid.lowercase() in loadedIds })
    }

    @Test
    fun ammunitionMadeForAnotherKindOfWeaponIsRefused() {
        val (c, ed, w) = emptiedIngram()
        val other = gears(c).first { it.text("category") == "Ammunition" && it.text("extra") == "Assault Rifles" }
        assertFalse(ed.loadAmmo(w, other.text("guid")!!.trim(), 1))
    }

    @Test
    fun loadingTakesTheAskedRoundsAndSplitsTheStack() {
        val (c, ed, w) = emptiedIngram()
        val stack = looseFor(ed, w)
        val total = rounds(c, stack.name)
        assertTrue(ed.loadAmmo(w, stack.guid, 1, rounds = 12))
        val t = totals(c, "Ingram Smartgun X")
        assertEquals(12, t.clips[0].rounds)
        assertEquals(stack.name, t.clips[0].ammo)
        assertEquals("the same rounds, split into a loaded item and the stack", total, rounds(c, stack.name), 0.001)
        // More than the clip holds, or more than the stack has, is cut down to what fits.
        assertFalse("a different slot count", ed.loadAmmo(w, stack.guid, 5))
    }

    @Test
    fun aPartlyFilledClipIsToppedUpWithTheSameAmmunitionOnly() {
        val (c, ed, w) = emptiedIngram()
        val regular = looseFor(ed, w)
        assertTrue(ed.loadAmmo(w, regular.guid, 1, rounds = 12))
        assertTrue(ed.loadAmmo(w, looseFor(ed, w, regular.name).guid, 1, rounds = 10))
        assertEquals(22, totals(c, "Ingram Smartgun X").clips[0].rounds)
        // Filling to capacity stops at 32, however much is offered.
        assertTrue(ed.loadAmmo(w, looseFor(ed, w, regular.name).guid, 1))
        assertEquals(32, totals(c, "Ingram Smartgun X").clips[0].rounds)
        assertFalse("the clip is full", ed.loadAmmo(w, looseFor(ed, w, regular.name).guid, 1))
        // A different ammunition cannot go into a clip that holds something.
        assertTrue(ed.unloadAmmo(w, 1))
        assertTrue(ed.loadAmmo(w, looseFor(ed, w, regular.name).guid, 1, rounds = 5))
        assertFalse(ed.loadAmmo(w, looseFor(ed, w, "Ammo: Subsonic").guid, 1))
        assertEquals(5, totals(c, "Ingram Smartgun X").clips[0].rounds)
    }

    @Test
    fun unloadingReturnsTheRoundsToTheStack() {
        val (c, ed, w) = emptiedIngram()
        val stack = looseFor(ed, w)
        val total = rounds(c, stack.name)
        assertTrue(ed.loadAmmo(w, stack.guid, 1, rounds = 20))
        assertTrue(ed.unloadAmmo(w, 1))
        assertEquals(0, totals(c, "Ingram Smartgun X").clips[0].rounds)
        assertEquals("", totals(c, "Ingram Smartgun X").clips[0].ammo)
        assertEquals(total, rounds(c, stack.name), 0.001)
        assertFalse(ed.unloadAmmo(w, 1))
    }

    @Test
    fun firingSpendsRoundsFromTheClipAndTheLoadedAmmunition() {
        val (c, ed, w) = emptiedIngram()
        val stack = looseFor(ed, w)
        assertTrue(ed.loadAmmo(w, stack.guid, 1, rounds = 10))
        val loaded = rounds(c, stack.name)
        assertTrue(ed.fireRounds(w, 3))
        assertEquals(7, totals(c, "Ingram Smartgun X").clips[0].rounds)
        assertEquals(loaded - 3, rounds(c, stack.name), 0.001)
        assertFalse("more than is in the clip", ed.fireRounds(w, 8))
        assertTrue(ed.fireRounds(w, 7))
        val t = totals(c, "Ingram Smartgun X")
        assertEquals(0, t.clips[0].rounds)
        assertEquals("an emptied clip is cleared", "", t.clips[0].ammo)
        assertFalse(ed.fireRounds(w, 1))
        assertFalse(ed.fireRounds(w, 0))
    }

    @Test
    fun switchingTheActiveClipChangesTheAmmunitionThatCounts() {
        val (c, ed, w) = emptiedIngram()
        ed.addAccessory(w, spareClip)
        val regular = looseFor(ed, w)
        val special = ed.compatibleAmmo(w).first { it.name == "Ammo: Subsonic" }
        assertTrue(ed.loadAmmo(w, regular.guid, 1, rounds = 10))
        assertTrue(ed.loadAmmo(w, special.guid, 2, rounds = 10))
        val first = totals(c, "Ingram Smartgun X")
        assertEquals(regular.name, first.loadedAmmo)
        assertTrue(ed.setActiveClip(w, 2))
        val second = totals(c, "Ingram Smartgun X")
        assertEquals(special.name, second.loadedAmmo)
        assertTrue("the special ammunition changes AP or damage", second.ap != first.ap || second.damage != first.damage)
        assertFalse(ed.setActiveClip(w, 3))
        assertFalse(ed.setActiveClip(w, 0))
    }

    @Test
    fun capacityFollowsAnExtendedClip() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val alpha = guid(weapon(c, "Ares Alpha"))
        assertEquals(42, ed.clipCapacity(alpha))
        assertEquals(42, totals(c, "Ares Alpha").clips.first().capacity)
    }

    // ---- which accessories fit which weapon

    private fun weaponEntry(name: String) = data.byId.values.first { it.file == "weapons.xml" && it.element.tagName == "weapon" && it.name == name }.element
    private fun accessoryEntry(name: String, nth: Int = 0) = data.byId.values.filter { it.file == "weapons.xml" && it.element.tagName == "accessory" && it.name == name }[nth].element
    private fun fits(accessory: String, weapon: String, nth: Int = 0) = WeaponFilter.accessoryFits(accessoryEntry(accessory, nth), weaponEntry(weapon))

    @Test
    fun aPlainSpareClipFitsAGunThatTakesClipsAndNotARevolver() {
        assertTrue(fits("Spare Clip", "Steyr TMP"))
        assertTrue(fits("Spare Clip", "Ares Alpha"))
        // The Ruger Super Warhawk holds its rounds in a cylinder: (cy), not (c) or (d).
        assertFalse(fits("Spare Clip", "Ruger Super Warhawk"))
    }

    @Test
    fun aClipMadeForOneWeaponFitsOnlyThatWeapon() {
        assertTrue(fits("Spare Clip (Hammerli Gemini)", "Hammerli Gemini"))
        assertFalse(fits("Spare Clip (Hammerli Gemini)", "Steyr TMP"))
        // The generic clip is not for the weapons that have a clip of their own.
        assertFalse(fits("Spare Clip", "Hammerli Gemini"))
    }

    @Test
    fun theCatalogOffersOnlyWhatFits() {
        val pack = data
        val forSteyr = ArmorWeaponCatalog.accessoriesFor(pack, weaponEntry("Steyr TMP")).map { it.name }
        assertTrue("Spare Clip" in forSteyr)
        assertTrue("no clip made for another weapon: ${forSteyr.filter { it.startsWith("Spare Clip") }}", forSteyr.none { it == "Spare Clip (Hammerli Gemini)" || it.startsWith("Spare Clip (HK Urban") })
        val forRuger = ArmorWeaponCatalog.accessoriesFor(pack, weaponEntry("Ruger Super Warhawk")).map { it.name }
        assertTrue("Spare Clip" !in forRuger)
        // Fewer than all of them, for every weapon.
        assertTrue(forSteyr.size < ArmorWeaponCatalog.accessories(pack).size)
    }

    @Test
    fun buyingAnAccessoryThatDoesNotFitIsRefused() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(100000.0, "Test reward")
        val ruger = guid(weapon(c, "Ruger Super Warhawk"))
        val before = adv.log.nuyen
        assertEquals(null, adv.buyAccessory(ruger, spareClip))
        assertEquals(before, adv.log.nuyen, 0.001)
        val steyr = guid(weapon(c, "Steyr TMP"))
        assertTrue(adv.buyAccessory(steyr, spareClip) != null)
        assertEquals(2, ArmorWeaponEditor(c, data).clipSlotCount(steyr))
    }

    @Test
    fun theFilterPortFollowsChummersOperations() {
        fun filter(xml: String): org.w3c.dom.Element =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        val weapon = filter("<weapon><name>Alpha</name><category>Assault Rifles</category><ammo>42(c)</ammo><rating>3</rating></weapon>")
        fun passes(f: String) = WeaponFilter.passes(weapon, filter("<weapondetails>$f</weapondetails>"))
        assertTrue(passes("<name>Alpha</name>"))
        assertFalse(passes("<name>alpha</name>")) // equals is exact
        assertTrue(passes("<ammo operation=\"contains\">(C)</ammo>")) // contains ignores case
        assertFalse(passes("<ammo operation=\"contains\">(d)</ammo>"))
        assertTrue(passes("<name>Alpha</name><category>Assault Rifles</category>")) // all must hold
        assertFalse(passes("<name>Alpha</name><category>Shotguns</category>"))
        assertTrue(passes("<OR><category>Shotguns</category><category>Assault Rifles</category></OR>"))
        assertFalse(passes("<OR><category>Shotguns</category><category>Pistols</category></OR>"))
        assertTrue(passes("<name NOT=\"\">Beta</name>"))
        assertFalse(passes("<name NOT=\"\">Alpha</name>"))
        assertTrue(passes("<rating operation=\"greaterthan\">2</rating>"))
        assertFalse(passes("<rating operation=\"greaterthan\">3</rating>"))
        assertTrue(passes("<rating operation=\"lessthanequals\">3</rating>"))
        assertTrue(passes("<rating operation=\"greaterthanequals\">3</rating>"))
        assertTrue(passes("<ammo operation=\"exists\" />"))
        assertFalse(passes("<reach operation=\"exists\" />"))
        assertTrue(passes("<NOR><name>Beta</name><name>Gamma</name></NOR>"))
        assertFalse(passes("<NOR><name>Alpha</name><name>Gamma</name></NOR>"))
        assertTrue(passes("<name operation=\"notequals\">Beta</name>"))
        assertFalse(passes("<name operation=\"notequals\">Alpha</name>"))
        assertTrue("an empty filter holds", passes(""))
    }

    // ---- managing the fitted accessories

    @Test
    fun everyFittedAccessoryIsListedWithItsGuidMountAndClipSlots() {
        val c = viper()
        val alpha = totals(c, "Ares Alpha")
        val xml = weapon(c, "Ares Alpha").child("accessories")!!.children("accessory")
        assertEquals(xml.size, alpha.fitted.size)
        assertEquals(xml.map { it.text("guid")!!.trim() }, alpha.fitted.map { it.guid })
        val spares = alpha.fitted.filter { it.name == "Spare Clip" }
        assertTrue(spares.isNotEmpty())
        assertTrue(spares.all { it.clipSlots == 1 && !it.included })
        assertEquals(alpha.clips.size - 1, alpha.fitted.sumOf { it.clipSlots })
        assertTrue("the Alpha's smartgun system comes with it", alpha.fitted.any { it.name.startsWith("Smartgun") })
    }

    @Test
    fun switchingAnAccessoryOffRemovesItsBonusButKeepsItFitted() {
        // Viper's Steyr TMP has a Laser Sight and nothing that outranks it (a smartgun system would: only the best sight counts).
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val sight = totals(c, "Steyr TMP").fitted.first { it.name == "Laser Sight" }
        val on = totals(c, "Steyr TMP")
        assertTrue(sight.equipped)
        assertTrue(ed.setAccessoryEquipped(sight.guid, false))
        val off = totals(c, "Steyr TMP")
        assertTrue(off.fitted.any { it.guid == sight.guid && !it.equipped })
        assertTrue("still fitted, but not counted", "Laser Sight" !in off.accessories)
        assertTrue("its accuracy bonus is gone: ${on.accuracy} then ${off.accuracy}", on.accuracy != off.accuracy)
        assertTrue(ed.setAccessoryEquipped(sight.guid, true))
        assertEquals(on.accuracy, totals(c, "Steyr TMP").accuracy)
        assertFalse(ed.setAccessoryEquipped("no-such-accessory", false))
    }

    @Test
    fun anAccessoryBoughtInCareerCanBeSoldForAShareOfItsPrice() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualNuyen(100000.0, "Test reward")
        val steyr = guid(weapon(c, "Steyr TMP"))
        val laser = data.byId.entries.first { it.value.file == "weapons.xml" && it.value.name == "Laser Sight" }.key
        val acc = adv.buyAccessory(steyr, laser)!!
        val paid = adv.log.nuyen
        assertTrue(totals(c, "Steyr TMP").fitted.any { it.guid == acc })
        val got = adv.sellPurchase(acc, 50)!!
        assertTrue("sold for something: $got", got > 0.0)
        assertEquals(paid + got, adv.log.nuyen, 0.01)
        assertTrue(totals(c, "Steyr TMP").fitted.none { it.guid == acc })
        assertTrue(adv.log.balanced())
    }

    @Test
    fun sellingASpareClipReturnsItsRoundsAndRemovesTheSlot() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val ed = ArmorWeaponEditor(c, data)
        val w = guid(weapon(c, "Ingram Smartgun X"))
        // Viper's Ingram has Spare Clips with ammunition in them.
        val before = totals(c, "Ingram Smartgun X")
        val loadedSpare = before.fitted.first { it.clipSlots > 0 && !it.included }
        val slots = before.clips.size
        val rounds = before.clips.sumOf { it.rounds }
        val looseBefore = ed.compatibleAmmo(w).sumOf { it.quantity }
        assertTrue(adv.discardPurchase(loadedSpare.guid))
        val after = totals(c, "Ingram Smartgun X")
        assertEquals(slots - 1, after.clips.size)
        assertEquals(before.fitted.size - 1, after.fitted.size)
        // Whatever was in that clip is loose again, so nothing is lost.
        val moved = rounds - after.clips.sumOf { it.rounds }
        assertEquals(looseBefore + moved, ed.compatibleAmmo(w).sumOf { it.quantity })
    }

    @Test
    fun anAccessoryInCreationIsRemovedForNothing() {
        val c = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
        val editor = CharacterEditor(c, data, settings = settings)
        repeat(5) { editor.changeNuyenBp(1) }
        val adv = CareerAdvancement(c, data, settings)
        val gun = data.byId.entries.first { it.value.file == "weapons.xml" && it.value.element.tagName == "weapon" && it.value.name == "Colt America L36" }.key
        val weaponGuid = adv.buyWeapon(gun)!!
        val acc = adv.buyAccessory(weaponGuid, spareClip)!!
        val ed = ArmorWeaponEditor(c, data)
        assertEquals(2, ed.clipSlotCount(weaponGuid))
        val before = NuyenLedger.of(c, settings, data).remaining
        assertTrue(adv.removePurchase(acc))
        assertEquals(1, ed.clipSlotCount(weaponGuid))
        assertEquals(before + 5.0, NuyenLedger.of(c, settings, data).remaining, 0.01)
    }

    @Test
    fun onlyAccessoriesWithFiguresToTurnOffHaveASwitch() {
        val c = viper()
        val alpha = totals(c, "Ares Alpha").fitted
        // A Spare Clip adds a clip slot and no figures, so being off would change nothing.
        assertTrue(alpha.filter { it.name == "Spare Clip" }.all { !it.switchable })
        // Nor do a sling, a silencer or a Gecko Grip: the game data gives them no figure this app calculates.
        assertTrue(alpha.filter { it.name == "Sling" || it.name.startsWith("Silencer") || it.name == "Gecko Grip" }.all { !it.switchable })
        // A smartgun system and a personalized grip add accuracy, and a shock pad recoil compensation, so they can be switched.
        assertTrue(alpha.filter { it.name.startsWith("Smartgun") || it.name == "Personalized Grip" || it.name == "Shock Pad" }.let { it.size == 3 && it.all { a -> a.switchable } })
        // The Steyr's Laser Sight gives accuracy.
        assertTrue(totals(c, "Steyr TMP").fitted.single { it.name == "Laser Sight" }.switchable)
    }

    @Test
    fun anAccessoryThatIsAlreadyOffCanAlwaysBeSwitchedBackOn() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val sling = totals(c, "Ares Alpha").fitted.first { it.name == "Sling" }
        assertFalse(sling.switchable)
        // Switched off by an older version of the app, it must not be stuck off.
        ed.setAccessoryEquipped(sling.guid, false)
        assertTrue(totals(c, "Ares Alpha").fitted.first { it.guid == sling.guid }.switchable)
    }
}
