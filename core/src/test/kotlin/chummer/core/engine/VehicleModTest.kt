package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mods and gear for a vehicle bought after the vehicle: which mods a vehicle is offered, what they cost (the formulas name the vehicle's own
 * figures), buying in creation and in career, taking them back or selling them. Needs only Chummer's public data.
 */
class VehicleModTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key
    private fun vehicleId(name: String) = data.byId.entries.first { it.value.file == "vehicles.xml" && it.value.element.tagName == "vehicle" && it.value.name == name }.key
    private fun modId(name: String) = data.byId.entries.first { it.value.file == "vehicles.xml" && it.value.element.tagName == "mod" && it.value.name == name }.key
    private fun gearId(name: String) = data.byId.entries.first { it.value.file == "gear.xml" && it.value.element.tagName == "gear" && it.value.name == name }.key

    /** A character with lots of nuyen in creation (priority A for resources), holding one vehicle. */
    private fun creation(vehicle: String): Pair<CharacterFile, String> {
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "Test", metatypeId = human()), PriorityChoice("C", "B", "E", "D", "A", "Mundane", true))
        val guid = VehicleEditor(c, data).add(vehicleId(vehicle))
        NuyenLedger.sync(c, settings, data)
        return c to guid
    }

    /** The same character as a career character with ¥500,000 in the log. */
    private fun career(vehicle: String): Triple<CharacterFile, String, ExpenseLog> {
        val (c, guid) = creation(vehicle)
        c.root.child("created")!!.textContent = "True"
        val log = ExpenseLog(c, data, settings)
        log.manualNuyen(500000.0, "test money")
        return Triple(c, guid, log)
    }

    private fun mods(c: CharacterFile, guid: String) = c.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == guid }.child("mods")!!.children("mod")

    @Test
    fun aDroneIsOfferedModsThatFitItAndNoHiddenOnes() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")
        val options = VehicleEditor(c, data).modOptions(guid, droneMods = false)
        assertTrue("some mods fit a drone", options.size > 20)
        val names = options.map { it.name }
        assertTrue("no hidden mods", "Speakers" !in names && "Bathroom" !in names)
        assertTrue("no weapon mounts here (they are not a plain mod)", names.none { it.endsWith("Weapon Mount (Drone)") })
        assertTrue("a mod for one named vehicle is not offered to another", "Tracked Propulsion" !in names && "Signature Dampening" !in names)
        assertTrue("a general mod is offered", "Off-Road Tires" in names || "Rigger Interface" in names || options.any { it.category == "All" })
    }

    @Test
    fun aModForOneVehicleIsOfferedToThatVehicle() {
        val (c, guid) = creation("BMW Teufelkatze")
        val names = VehicleEditor(c, data).modOptions(guid, false).map { it.name }
        assertTrue("Signature Dampening" in names)
    }

    @Test
    fun aVehicleForbiddenAModDoesNotGetIt() {
        val (c, guid) = creation("Shiawase Motors Shuriken")
        val names = VehicleEditor(c, data).modOptions(guid, false).map { it.name }
        assertTrue("Off-Road Tires" !in names)
    }

    @Test
    fun everyOfferedModHasAPriceOnEveryVehicle() {
        // The price formulas name Body, Handling, Speed, Acceleration, Armor and Vehicle Cost; none may be left unreadable. (A price the player sets, Variable(10-10000), is the exception.)
        val bad = ArrayList<String>()
        for (v in data.byId.entries.filter { it.value.file == "vehicles.xml" && it.value.element.tagName == "vehicle" }.take(60)) {
            val (c, guid) = creation(v.value.name!!)
            for (o in VehicleEditor(c, data).modOptions(guid, droneMods = true)) if (o.cost == null && !(data[o.id]!!.element.child("cost")?.textContent.orEmpty().trim().startsWith("Variable("))) bad += "${v.value.name} / ${o.name}"
        }
        bad.take(20).forEach { println("  UNPRICED $it") }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun priceFormulasUseTheVehiclesOwnFigures() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")
        val ed = VehicleEditor(c, data)
        val vehicle = c.root.child("vehicles")!!.children("vehicle").first()
        val body = vehicle.text("body")!!.trim().toDouble()
        // "Armor (Standard)": Rating * 500, rating up to Body.
        val armor = modId("Armor (Standard)")
        val opt = ed.modOptions(guid, true).firstOrNull { it.id == armor }
        if (opt != null) {
            assertEquals(body.toInt(), opt.maxRating)
            assertEquals(500.0 * 2, ed.modCost(guid, armor, 2)!!, 0.001)
        }
        // A drone upgrade is priced from Body: Rating * (Body * 200) once the drone has a Body.
        val handling = modId("Handling (Drone)")
        val h = vehicle.text("handling")!!.trim().substringBefore("/").toInt()
        val rating = h + 1
        assertEquals(rating * (body * 200), ed.modCost(guid, handling, rating)!!, 0.001)
        // The cheapest rating is one above the figure it raises.
        assertEquals(h + 1, ed.modOptions(guid, true).first { it.id == handling }.minRating)
    }

    @Test
    fun aModBoughtInCreationIsPaidFromTheBudgetAndTakenBackForFree() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val before = NuyenLedger.of(c, settings, data).remaining
        val id = adv.vehicleModOptions(guid).first { it.cost != null && it.cost!! > 0 && it.maxRating == 0 }.id
        val cost = adv.vehicleModCost(guid, id)!!
        val mod = adv.buyVehicleMod(guid, id)
        assertNotNull(mod)
        assertEquals(before - cost, NuyenLedger.of(c, settings, data).remaining, 0.01)
        val stored = mods(c, guid).first { it.text("guid")?.trim() == mod }
        assertEquals("False", stored.text("included"))
        assertTrue(ExpenseLog(c).entries().isEmpty())
        assertTrue(adv.removePurchase(mod!!))
        assertEquals(before, NuyenLedger.of(c, settings, data).remaining, 0.01)
    }

    @Test
    fun aModBoughtInCareerIsLoggedUndoneAndSold() {
        val (c, guid, log) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val start = log.nuyen
        val id = adv.vehicleModOptions(guid).first { it.cost != null && it.cost!! >= 100 && it.maxRating == 0 }.id
        val cost = adv.vehicleModCost(guid, id)!!
        val mod = adv.buyVehicleMod(guid, id)!!
        assertEquals(start - cost, log.nuyen, 0.01)
        assertEquals("AddVehicleMod", log.entries().last().undoKind)
        // Undo takes it back and refunds.
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(start, log.nuyen, 0.01)
        assertTrue(mods(c, guid).none { it.text("guid")?.trim() == mod })
        // Buy again and sell for half.
        val again = adv.buyVehicleMod(guid, id)!!
        val got = adv.sellPurchase(again, 50)!!
        assertEquals(cost / 2, got, 0.01)
        assertTrue(mods(c, guid).none { it.text("guid")?.trim() == again })
    }

    @Test
    fun aRatedModTakesItsRating() {
        val (c, guid, _) = career("Dodge Xenon")
        val adv = CareerAdvancement(c, data, settings)
        val tires = modId("Off-Road Tires")
        assertTrue(adv.vehicleModOptions(guid).any { it.id == tires })
        val cost = adv.vehicleModCost(guid, tires, 4)!!
        assertEquals(4 * 400.0, cost, 0.001)
        val mod = adv.buyVehicleMod(guid, tires, 4)!!
        assertEquals("4", mods(c, guid).first { it.text("guid")?.trim() == mod }.text("rating"))
    }

    @Test
    fun aModThatCostsMoreThanYouHaveOrDoesNotFitIsRefused() {
        val (c, guid, log) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        log.manualNuyen(-log.nuyen, "spend it all")
        val id = adv.vehicleModOptions(guid).first { it.cost != null && it.cost!! > 0 && it.maxRating == 0 }.id
        assertNull(adv.buyVehicleMod(guid, id))
        assertEquals("Not enough nuyen", adv.vehicleModBlockReason(guid, id))
        log.manualNuyen(1000000.0, "money")
        assertNull(adv.buyVehicleMod(guid, modId("Tracked Propulsion")))
        assertNotNull(adv.vehicleModBlockReason(guid, modId("Tracked Propulsion")))
    }

    @Test
    fun theModsThatComeWithAVehicleStayOnIt() {
        val (c, guid) = creation("Daihatsu-Caterpillar Horseman")
        val included = mods(c, guid).first()
        assertEquals("True", included.text("included"))
        assertFalse(VehicleEditor(c, data).removeMod(included.text("guid")!!.trim()))
        assertTrue(mods(c, guid).contains(included))
    }

    @Test
    fun slotsCountOnlyTheModsYouAdded() {
        val (c, guid, _) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val vehicle = c.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == guid }
        val (used0, total) = VehicleFormula.slots(vehicle)
        assertEquals(0, used0)
        assertTrue(total > 0)
        val twoSlots = adv.vehicleModOptions(guid).first { it.detail == "slots 2" && it.maxRating == 0 && it.cost != null }.id
        adv.buyVehicleMod(guid, twoSlots)!!
        assertEquals(2, VehicleFormula.slots(vehicle).first)
    }

    @Test
    fun gearBoughtForAVehicleGoesInItsOwnGearAndPricesIntoTheLedger() {
        val (c, guid, log) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val id = gearId("Flashlight")
        val vehicle = c.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == guid }
        val before = vehicle.child("gears")!!.children("gear").size
        val spentBefore = NuyenLedger.of(c, settings, data).spent
        val start = log.nuyen
        val g = adv.buyVehicleGear(guid, id, null, 1.0)!!
        assertEquals(before + 1, vehicle.child("gears")!!.children("gear").size)
        assertEquals(guid, vehicle.child("gears")!!.children("gear").first { it.text("guid")?.trim() == g }.text("parentid")?.trim())
        assertTrue("not loose gear", c.root.child("gears")?.children("gear").orEmpty().none { it.text("guid")?.trim() == g })
        val price = adv.gearCost(id)!!
        assertEquals(start - price, log.nuyen, 0.01)
        assertEquals(spentBefore + price, NuyenLedger.of(c, settings, data).spent, 0.01)
        // Undo takes it out of the vehicle.
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(before, vehicle.child("gears")!!.children("gear").size)
        // Selling goes through the same path.
        val again = adv.buyVehicleGear(guid, id, null, 1.0)!!
        assertEquals(price / 2, adv.sellPurchase(again, 50)!!, 0.01)
    }

    @Test
    fun theSheetListsAVehiclesModsAndGear() {
        val (c, guid, _) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val id = adv.vehicleModOptions(guid).first { it.cost != null && it.maxRating == 0 }.id
        adv.buyVehicleMod(guid, id)
        adv.buyVehicleGear(guid, gearId("Flashlight"), null, 1.0)
        val v = SheetSections.read(c, emptyList(), EssenceEngine(ImprovementSet(Improvement.parseAll(c.root), true), data, true, 2, false), data).vehicles.first { it.guid == guid }
        assertTrue(v.modLines.any { it.included })
        assertTrue(v.modLines.any { !it.included })
        assertTrue(v.gearLines.any { it.name == "Flashlight" })
        assertTrue(v.slotsTotal > 0)
    }
}
