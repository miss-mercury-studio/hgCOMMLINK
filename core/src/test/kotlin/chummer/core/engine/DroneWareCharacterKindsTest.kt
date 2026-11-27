package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The same drone purchases for an A.I. (whose body is the drone) and for an ordinary character: the vehicle, its limb ware, the limb figures and the
 * prices come out the same, and for an A.I. none of it counts as the character's own ware (no essence, no desktop warning, and the A.I. still cannot
 * buy ware of its own). Needs only Chummer's public data.
 */
class DroneWareCharacterKindsTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun meta(name: String) = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == name }.key
    private fun id(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    /** The letters are metatype, attributes, talent, skills, resources: an A.I. here is heritage C, attributes D, talent B, skills E and resources A. */
    private fun character(ai: Boolean): CharacterFile =
        if (ai) NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = meta("A.I.")), PriorityChoice("C", "D", "B", "E", "A", "A.I.", true, talentSkills = emptyList()))
        else NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = meta("Human")), PriorityChoice("C", "B", "E", "D", "A", "Mundane", true))

    /** What a scenario produced, to compare between the two kinds of character. */
    data class Result(val armOptions: List<String>, val costs: List<Double>, val capacityUsed: Double, val figures: Pair<Int, Int>, val spent: Double, val choice: String, val changes: List<String>)

    private fun scenario(c: CharacterFile): Result {
        val adv = CareerAdvancement(c, data, settings)
        val before = NuyenLedger.of(c, settings, data).spent
        val drone = adv.buyVehicle(id("vehicles.xml", "vehicle", "Saeder-Krupp Direktionssekretar"))
        assertNotNull("the drone can be bought", drone)
        val arm = c.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == drone }.child("mods")!!.children("mod").first { it.text("name") == "Drone Arm" }.text("guid")!!.trim()
        val options = adv.droneWareOptions(arm)
        val agi = options.first { it.name == "Customized Agility" }
        val opt = options.first { it.name == "Cyberlimb Optimization" }
        val skill = adv.droneWareSkills().first()
        val costs = listOf(adv.droneWareCost(arm, agi.id, 5)!!, adv.droneWareCost(arm, opt.id, null)!!)
        assertNotNull(adv.buyDroneWare(arm, agi.id, 5))
        assertNotNull(adv.buyDroneWare(arm, opt.id, null, skill))
        val ed = VehicleEditor(c, data)
        val line = Sheet.build(c, data, settings).sections.vehicles.first { it.guid == drone }
        val figures = ed.limbFigure(arm, "STR")!! to ed.limbFigure(arm, "AGI")!!
        return Result(
            options.map { it.name }, costs, ed.wareCapacity(arm)!!.second, figures, NuyenLedger.of(c, settings, data).spent - before,
            if (line.modLines.first { m -> m.guid == arm }.ware.any { w -> w.extra == skill }) "saved" else "missing",
            line.changes,
        )
    }

    @Test
    fun anAiAndAnOrdinaryCharacterGetTheSameDroneWare() {
        val human = scenario(character(ai = false))
        val ai = scenario(character(ai = true))
        println("DRONEWARE human=$human")
        println("DRONEWARE ai=$ai")
        assertEquals(human, ai)
        assertEquals(4 to 5, ai.figures)          // Body 4 (Strength), Customized Agility 5 on Pilot 4
        assertEquals("saved", ai.choice)
        assertEquals(2.0, ai.capacityUsed, 0.001) // the upgrade uses none; the optimization uses 2
        assertEquals(5000.0 + 2000.0, ai.costs.sum(), 0.001)
    }

    @Test
    fun aDronesWareIsNotAnAisOwnWare() {
        val c = character(ai = true)
        val essenceBefore = Sheet.build(c, data, settings).essence
        scenario(c)
        // The drone's ware is not the character's: no ware of its own, the same essence, nothing for desktop Chummer to stall on.
        assertTrue(c.augmentations().isEmpty())
        assertTrue(c.root.child("cyberwares")?.children("cyberware").orEmpty().isEmpty())
        assertEquals(essenceBefore, Sheet.build(c, data, settings).essence, 0.0001)
        assertTrue("no desktop warning: " + DesktopCheck.problems(c), DesktopCheck.problems(c).isEmpty())
        // And an A.I. still cannot buy ware of its own.
        val adv = CareerAdvancement(c, data, settings)
        assertNull(adv.buyWare(id("cyberware.xml", "cyberware", "Datajack")))
        assertTrue(c.root.child("cyberwares")?.children("cyberware").orEmpty().isEmpty())
    }

    @Test
    fun anAisDroneFiguresAndModsWorkLikeAnyVehicles() {
        val c = character(ai = true)
        val adv = CareerAdvancement(c, data, settings)
        val drone = adv.buyVehicle(id("vehicles.xml", "vehicle", "Saeder-Krupp Direktionssekretar"))!!
        val node = c.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == drone }
        val base = VehicleFigures.base(node)
        VehicleEditor(c, data).addMod(drone, id("vehicles.xml", "mod", "Pilot Program (Drone)"), 6, droneMods = true)
        assertEquals(6, VehicleFigures.total(node, data)["pilot"])
        assertEquals(base["pilot"], 4)
        assertTrue(DesktopCheck.problems(c).isEmpty())
    }
}
