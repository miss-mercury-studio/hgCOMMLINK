package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lifestyle and vehicle prices against the totals Chummer printed for Viper (reference/exports/Viper_career.xml). */
class LifestyleVehicleCostTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private fun money(s: String?) = s!!.replace(",", "").trim().toDouble()

    /** The character with only the vehicle at [index] (or only the lifestyle) left, so the ledger's total is that item's price. */
    private fun only(keep: String, index: Int): CharacterFile {
        val c = viper()
        for (tag in listOf("gears", "weapons", "armors", "cyberwares", "lifestyles", "vehicles")) {
            val list = c.root.child(tag) ?: continue
            val items = list.children()
            items.forEachIndexed { i, e -> if (tag != keep || i != index) list.removeChild(e) }
        }
        return c
    }

    @Test
    fun lifestyleTotalMatchesChummer() {
        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        val want = money(export.child("lifestyles")!!.children("lifestyle").first().text("totalcost"))
        assertEquals(want, NuyenLedger.of(only("lifestyles", 0), settings, data).spent, 0.01)
    }

    @Test
    fun vehiclePricesMatchChummer() {
        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        val wants = export.child("vehicles")!!.children("vehicle").map { it.text("name")!!.trim() to money(it.text("cost")) }
        val vehicles = viper().root.child("vehicles")!!.children("vehicle")
        assertEquals(3, vehicles.size)
        val bad = ArrayList<String>()
        vehicles.forEachIndexed { i, v ->
            val name = v.text("name")!!.trim()
            val want = wants.first { it.first == name }.second
            val got = NuyenLedger.of(only("vehicles", i), settings, data)
            println("Vehicle $name: Chummer $want ours ${got.spent} unreadable ${got.unreadable}")
            if (Math.abs(want - got.spent) > 0.01) bad += "$name: Chummer $want ours ${got.spent}"
        }
        assertTrue(bad.joinToString(), bad.isEmpty())
    }
}
