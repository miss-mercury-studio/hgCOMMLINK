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

    // ---- cyberware in a drone's limbs

    private fun modGuid(c: CharacterFile, vehicle: String, mod: String, nth: Int = 0) = mods(c, vehicle).filter { it.text("name") == mod }[nth].text("guid")!!.trim()

    @Test
    fun aDroneArmAndLegTakeDifferentWare() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")
        val ed = VehicleEditor(c, data)
        val arm = modGuid(c, guid, "Drone Arm"); val leg = modGuid(c, guid, "Drone Leg")
        assertTrue(ed.takesWare(arm) && ed.takesWare(leg))
        val armNames = ed.wareOptions(arm).map { it.name }
        val legNames = ed.wareOptions(leg).map { it.name }
        println("ARM ${armNames.size} LEG ${legNames.size}")
        assertTrue("Cyberarm Gyromount" in armNames && "Cyberarm Gyromount" !in legNames)
        assertTrue(legNames.any { it == "Digigrade Legs" || it.contains("Foot") || it.contains("Skates") })
        assertTrue("only the Strength and Agility upgrades have limits that name the limb", ed.wareOptions(arm).filter { data[it.id]!!.element.child("rating")?.textContent.orEmpty().contains("{") }.all { it.name.contains("Strength") || it.name.contains("Agility") })
        assertTrue("only ware the data names for the part, and the two attribute upgrades", ed.wareOptions(arm).all { data[it.id]!!.element.child("required")?.child("parentdetails") != null || it.name.contains("Strength") || it.name.contains("Agility") })
        assertTrue("nothing made for no part in particular", "Datajack" !in armNames && "Obvious Full Arm" !in armNames)
        assertTrue("every option is of a category the part lists", ed.wareOptions(arm).all { it.category in setOf("Bodyware", "Headware", "Cosmetic Enhancement", "Cyberlimb", "Cyberlimb Enhancement", "Cyberlimb Accessory", "Cyber Implant Weapon") })
    }

    @Test
    fun aPartWithNoSubsystemsTakesNoWare() {
        val (c, guid) = creation("Dodge Xenon")
        val ed = VehicleEditor(c, data)
        assertTrue(mods(c, guid).none { ed.takesWare(it.text("guid")!!.trim()) })
        assertTrue(ed.wareOptions(guid).isEmpty())
    }

    @Test
    fun wareIsPaidForUsesCapacityAndStaysOutOfTheCharactersOwnWare() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val arm = modGuid(c, guid, "Drone Arm")
        val gyro = adv.droneWareOptions(arm).first { it.name == "Cyberarm Gyromount" }
        val (total, usedBefore) = VehicleEditor(c, data).wareCapacity(arm)!!
        assertEquals(15.0, total, 0.001); assertEquals(0.0, usedBefore, 0.001)
        val before = NuyenLedger.of(c, settings, data).remaining
        val ware = adv.buyDroneWare(arm, gyro.id)
        assertNotNull(ware)
        assertEquals(before - 6000.0, NuyenLedger.of(c, settings, data).remaining, 0.01)
        assertEquals(8.0, VehicleEditor(c, data).wareCapacity(arm)!!.second, 0.001)
        // No second one: 8 + 8 is more than the 15 the arm has.
        assertNull(adv.buyDroneWare(arm, gyro.id))
        assertEquals("Not enough capacity left in that part", adv.droneWareBlockReason(arm, gyro.id))
        // The same arm on the other side is a separate part with its own capacity.
        assertNotNull(adv.buyDroneWare(modGuid(c, guid, "Drone Arm", 1), gyro.id))
        // It is the drone's: the character's own ware and essence are untouched.
        assertTrue(c.root.child("cyberwares")?.children("cyberware").orEmpty().isEmpty())
        assertTrue(c.augmentations().isEmpty())
        // Taking it back in creation gives the nuyen back.
        assertTrue(adv.removePurchase(ware!!))
        assertEquals(0.0, VehicleEditor(c, data).wareCapacity(arm)!!.second, 0.001)
    }

    @Test
    fun wareBoughtInCareerIsLoggedUndoneAndSold() {
        val (c, guid, log) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val arm = modGuid(c, guid, "Drone Arm")
        val gyro = adv.droneWareOptions(arm).first { it.name == "Cyberarm Gyromount" }
        val start = log.nuyen
        val ware = adv.buyDroneWare(arm, gyro.id)!!
        assertEquals(start - 6000.0, log.nuyen, 0.01)
        assertEquals("AddVehicleWare", log.entries().last().undoKind)
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(start, log.nuyen, 0.01)
        assertEquals(0.0, VehicleEditor(c, data).wareCapacity(arm)!!.second, 0.001)
        val again = adv.buyDroneWare(arm, gyro.id)!!
        assertEquals(3000.0, adv.sellPurchase(again, 50)!!, 0.01)
        assertEquals(0.0, VehicleEditor(c, data).wareCapacity(arm)!!.second, 0.001)
        assertNotNull(ware)
    }

    @Test
    fun theSheetShowsAnArmsWareAndCapacity() {
        val (c, guid, _) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val arm = modGuid(c, guid, "Drone Arm")
        adv.buyDroneWare(arm, adv.droneWareOptions(arm).first { it.name == "Cyberarm Gyromount" }.id)
        val v = SheetSections.read(c, emptyList(), EssenceEngine(ImprovementSet(Improvement.parseAll(c.root), true), data, true, 2, false), data).vehicles.first { it.guid == guid }
        val line = v.modLines.first { it.guid == arm }
        assertTrue(line.takesWare)
        assertEquals(15.0, line.capacityTotal, 0.001); assertEquals(8.0, line.capacityUsed, 0.001)
        assertEquals(listOf("Cyberarm Gyromount"), line.ware.map { it.name })
        assertTrue(v.modLines.first { it.name == "Rigger Interface" }.takesWare.not())
    }

    @Test
    fun aDroneLimbStartsAtTheDronesBodyAndPilotAndCanBeUpgradedToDoubleThat() {
        val (c, guid, log) = career("Saeder-Krupp Direktionssekretar")   // Body 4, Pilot 4
        val adv = CareerAdvancement(c, data, settings)
        val arm = modGuid(c, guid, "Drone Arm")
        val ed = VehicleEditor(c, data)
        assertEquals(4, ed.limbFigure(arm, "STR")); assertEquals(4, ed.limbFigure(arm, "AGI"))
        val agi = adv.droneWareOptions(arm).first { it.name == "Customized Agility" }
        assertEquals(5, agi.minRating); assertEquals(8, agi.maxRating)
        // Customized Agility is priced from the limb's own figure: 4 to 6 is two steps at ¥5,000.
        assertEquals(10000.0, adv.droneWareCost(arm, agi.id, 6)!!, 0.001)
        assertEquals("above the cap of double the base", 20000.0, adv.droneWareCost(arm, agi.id, 12)!!, 0.001)
        val start = log.nuyen
        val ware = adv.buyDroneWare(arm, agi.id, 6)
        assertNotNull(ware)
        assertEquals(start - 10000.0, log.nuyen, 0.01)
        assertEquals(6, ed.limbFigure(arm, "AGI")); assertEquals(4, ed.limbFigure(arm, "STR"))
        // It uses no capacity, and it is offered once: Strength still can be, Agility no longer.
        assertEquals(0.0, ed.wareCapacity(arm)!!.second, 0.001)
        val names = adv.droneWareOptions(arm).map { it.name }
        assertTrue("Customized Agility" !in names && "Cyberlimb Customization, Agility (2050)" !in names)
        assertTrue("Customized Strength" in names)
        // The other arm is its own limb.
        assertEquals(4, ed.limbFigure(modGuid(c, guid, "Drone Arm", 1), "AGI"))
        // The sheet shows both figures.
        val v = SheetSections.read(c, emptyList(), EssenceEngine(ImprovementSet(Improvement.parseAll(c.root), true), data, true, 2, false), data).vehicles.first { it.guid == guid }
        val line = v.modLines.first { it.guid == arm }
        assertEquals(4, line.strength); assertEquals(6, line.agility)
        // Selling it takes the limb back to the base.
        assertEquals(5000.0, adv.sellPurchase(ware!!, 50)!!, 0.01)
        assertEquals(4, ed.limbFigure(arm, "AGI"))
    }

    // ---- the vehicle's figures with its mods applied

    private fun vehicleNode(c: CharacterFile, guid: String) = c.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == guid }
    private fun figures(c: CharacterFile, guid: String) = VehicleFigures.total(vehicleNode(c, guid), data)

    @Test
    fun aSignedBonusAddsToTheFigure() {
        val (c, guid) = creation("Dodge Xenon")
        val ed = VehicleEditor(c, data)
        val base = VehicleFigures.base(vehicleNode(c, guid))
        ed.addMod(guid, modId("Speed Enhancement"), 3, droneMods = false)
        val t = figures(c, guid)
        assertEquals(base["speed"]!! + 3, t["speed"]); assertEquals(base["offroadspeed"]!! + 3, t["offroadspeed"])
        assertEquals(base["handling"], t["handling"])
        ed.addMod(guid, modId("Off-Road Tires"), 4, droneMods = false)   // handling -1 on road, +1 off road, whatever the quantity
        val t2 = figures(c, guid)
        assertEquals(base["handling"]!! - 1, t2["handling"]); assertEquals(base["offroadhandling"]!! + 1, t2["offroadhandling"])
    }

    @Test
    fun anUnsignedBonusSetsTheFigure() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")   // Pilot 4
        val ed = VehicleEditor(c, data)
        ed.addMod(guid, modId("Pilot Program (Drone)"), 6, droneMods = true)
        assertEquals("the rating is the new Pilot", 6, figures(c, guid)["pilot"])
        // A drone upgrade: Handling (Drone) at rating 6 on Handling 4 makes it 6, and a -1 from another mod then takes it to 5.
        ed.addMod(guid, modId("Handling (Drone)"), 6, droneMods = true)
        assertEquals(6, figures(c, guid)["handling"])
        ed.addMod(guid, modId("Handling Downgrade (Drone)"), null, droneMods = true)
        assertEquals(5, figures(c, guid)["handling"])
        // Immobile sets speed and acceleration to nothing.
        ed.addMod(guid, modId("Immobile (Drone)"), null, droneMods = true)
        assertEquals(0, figures(c, guid)["speed"]); assertEquals(0, figures(c, guid)["accel"])
    }

    @Test
    fun aFigureNeverGoesBelowZero() {
        val (c, guid) = creation("Saeder-Krupp Direktionssekretar")   // Armor 3
        val ed = VehicleEditor(c, data)
        repeat(3) { ed.addMod(guid, modId("Armor Downgrade (Drone)"), null, droneMods = true) }   // -3 each
        assertEquals(0, figures(c, guid)["armor"])
    }

    @Test
    fun modsThatCameWithTheVehicleOrAreSwitchedOffAddNothing() {
        val (c, guid) = creation("Dodge Xenon")
        val base = VehicleFigures.base(vehicleNode(c, guid))
        val mod = VehicleEditor(c, data).addMod(guid, modId("Speed Enhancement"), 3, droneMods = false)
        val node = mods(c, guid).first { it.text("guid")?.trim() == mod }
        node.child("equipped")!!.textContent = "False"
        assertEquals(base["speed"], figures(c, guid)["speed"])
        node.child("equipped")!!.textContent = "True"; node.child("included")!!.textContent = "True"
        assertEquals("an included mod is already in the base figures", base["speed"], figures(c, guid)["speed"])
    }

    @Test
    fun increasedSeatingAddsHalfTheSeats() {
        val (c, guid) = creation("Dodge Xenon")
        val seats = VehicleFigures.base(vehicleNode(c, guid))["seats"]!!
        VehicleEditor(c, data).addMod(guid, modId("Increased Seating"), null, droneMods = false)
        assertEquals(seats + Math.round(seats * 0.5).toInt(), figures(c, guid)["seats"])
    }

    @Test
    fun theSheetShowsTheTotalsAndWhatChanged() {
        val (c, guid, _) = career("Dodge Xenon")
        val adv = CareerAdvancement(c, data, settings)
        fun line() = SheetSections.read(c, emptyList(), EssenceEngine(ImprovementSet(Improvement.parseAll(c.root), true), data, true, 2, false), data).vehicles.first { it.guid == guid }
        val before = line()
        assertTrue(before.changes.isEmpty())
        val speed = VehicleFigures.base(vehicleNode(c, guid))["speed"]!!
        adv.buyVehicleMod(guid, modId("Speed Enhancement"), 2)
        val after = line()
        assertTrue("the stats line shows the new speed: ${after.stats}", Regex("Speed ${speed + 2}\\b").containsMatchIn(after.stats))
        val off = VehicleFigures.base(vehicleNode(c, guid))["offroadspeed"]!!
        // On-road and off-road speed moved together, so it is one line.
        assertEquals(listOf("Speed " + (if (speed == off) "$speed" else "$speed/$off") + " → " + (if (speed == off) "${speed + 2}" else "${speed + 2}/${off + 2}")), after.changes)
        // Taking the mod off puts the figure back.
        assertTrue(CareerAdvancement(c, data, settings).discardPurchase(mods(c, guid).first { it.text("included") == "False" }.text("guid")!!.trim()))
        assertTrue(line().changes.isEmpty())
    }

    @Test
    fun wareThatAsksForAChoiceTakesItAndSavesIt() {
        val (c, guid, log) = career("Saeder-Krupp Direktionssekretar")
        val adv = CareerAdvancement(c, data, settings)
        val arm = modGuid(c, guid, "Drone Arm")
        val options = adv.droneWareOptions(arm)
        val optimization = options.first { it.name == "Cyberlimb Optimization" }
        val finger = options.first { it.name == "Cyberfinger (Custom)" }
        assertEquals("skill", optimization.choice); assertEquals("text", finger.choice)
        assertEquals("", options.first { it.name == "Cyberarm Gyromount" }.choice)
        val skill = adv.droneWareSkills().first()
        assertTrue("the character has active skills to choose from", adv.droneWareSkills().isNotEmpty())
        // Without a choice, or with something that is not one of the character's skills, it is refused.
        assertNull(adv.buyDroneWare(arm, optimization.id))
        assertEquals("Choose one of your skills first", adv.droneWareBlockReason(arm, optimization.id))
        assertNull(adv.buyDroneWare(arm, optimization.id, null, "Underwater Basket Weaving"))
        assertNull(adv.buyDroneWare(arm, finger.id))
        // With one, it is bought and the choice is saved in the item, as the sheet spells the skill.
        val ware = adv.buyDroneWare(arm, optimization.id, null, skill.lowercase())
        assertNotNull(ware)
        val stored = mods(c, guid).first { it.text("guid")?.trim() == arm }.child("cyberwares")!!.children("cyberware").first { it.text("guid")?.trim() == ware }
        assertEquals(skill, stored.text("extra"))
        assertNotNull(adv.buyDroneWare(arm, finger.id, null, "Lockpick set"))
        val v = SheetSections.read(c, emptyList(), EssenceEngine(ImprovementSet(Improvement.parseAll(c.root), true), data, true, 2, false), data).vehicles.first { it.guid == guid }
        assertEquals(setOf(skill, "Lockpick set"), v.modLines.first { it.guid == arm }.ware.map { it.extra }.toSet())
        assertTrue(log.undo(log.entries().last().guid))
    }
}
