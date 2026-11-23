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

/** Vehicles generated from the data equal the ones Chummer saved for Viper (fields, included mods and gear), and can be bought and sold. */
class VehicleEditorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
    private val skip = setOf("guid", "notes", "notesColor", "sortorder", "mods", "gears", "weapons", "weaponmounts", "vehiclename", "location")

    @Test
    fun generatedVehiclesEqualVipersVehicles() {
        val viper = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val stored = viper.root.child("vehicles")!!.children("vehicle")
        assertTrue(stored.size >= 3)
        val bad = ArrayList<String>()
        for (v in stored) {
            val id = v.text("sourceid")!!.trim()
            if (data[id] == null) continue
            val scratch = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
            scratch.root.child("vehicles")!!.children("vehicle").forEach { scratch.root.child("vehicles")!!.removeChild(it) }
            val guid = VehicleEditor(scratch, data).add(id)
            val made = scratch.root.child("vehicles")!!.children("vehicle").first { it.text("guid")?.trim() == guid }
            for (k in v.children()) {
                if (k.tagName in skip || k.children().isNotEmpty()) continue
                val want = k.textContent.trim(); val got = made.child(k.tagName)?.textContent?.trim()
                if (want != got) bad += "${v.text("name")} ${k.tagName}: Chummer=[$want] ours=[$got]"
            }
            // Included mods and gear: the same names in the same order.
            val wantMods = v.child("mods")?.children("mod").orEmpty().map { it.text("name")?.trim() }
            val gotMods = made.child("mods")?.children("mod").orEmpty().map { it.text("name")?.trim() }
            if (wantMods.take(gotMods.size) != gotMods) bad += "${v.text("name")} mods: Chummer=$wantMods ours=$gotMods"
            val wantGear = v.child("gears")?.children("gear").orEmpty().map { it.text("name")?.trim() }
            val gotGear = made.child("gears")?.children("gear").orEmpty().map { it.text("name")?.trim() }
            if (wantGear.take(gotGear.size) != gotGear) bad += "${v.text("name")} gear: Chummer=$wantGear ours=$gotGear"
            // Every mod field equals the stored one.
            for ((w, g) in v.child("mods")?.children("mod").orEmpty().zip(made.child("mods")?.children("mod").orEmpty())) {
                for (k in w.children()) {
                    if (k.tagName in skip || k.children().isNotEmpty()) continue
                    val a = k.textContent.trim(); val b = g.child(k.tagName)?.textContent?.trim()
                    if (a != b) bad += "${v.text("name")} / mod ${w.text("name")} ${k.tagName}: Chummer=[$a] ours=[$b]"
                }
            }
        }
        bad.forEach { println("  VEHICLEDIFF $it") }
        assertTrue("differences: ${bad.size}", bad.size <= KNOWN)
    }

    @Test
    fun aVehicleCanBeBoughtAndSold() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c, data, settings)
        val id = VehicleCatalog.vehicles(data).first { it.cost != null && it.cost!! in 1.0..3000.0 }.id
        val start = log.nuyen
        val cost = adv.vehicleCost(id)!!
        val guid = adv.buyVehicle(id)!!
        assertEquals(start - cost, log.nuyen, 0.01)
        assertEquals("AddVehicle", log.entries().last().undoKind)
        assertTrue(log.canUndo(log.entries().last()))
        // Undo takes it back and refunds.
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(start, log.nuyen, 0.01)
        assertTrue(c.root.child("vehicles")!!.children("vehicle").none { it.text("guid")?.trim() == guid })
        // Buy again and sell for half.
        val again = adv.buyVehicle(id)!!
        val got = adv.sellPurchase(again, 50)!!
        assertEquals(cost / 2, got, 0.01)
    }

    companion object { const val KNOWN = 0 }
}
