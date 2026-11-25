package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/** Armor and weapons added from the data must be what Chummer wrote for the same items in Viper's file. */
class ArmorWeaponEditorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }
    private val ws = Regex("""\s+""")
    private val skip = setOf("guid", "notes", "notesColor", "location", "sortorder", "extra", "overclocked", "wirelesson", "wirelessbonus")

    private fun compare(listTag: String, itemTag: String, skipTags: Set<String>, add: (ArmorWeaponEditor, String, Element) -> String, usable: (ArmorWeaponEditor, String) -> Boolean): Pair<Int, List<String>> {
        val c = viper()
        var checked = 0
        val bad = ArrayList<String>()
        for (g in c.root.child(listTag)!!.children(itemTag)) {
            val id = g.text("sourceid")?.trim().orEmpty()
            if (data[id] == null) continue
            val scratch = viper()
            val ed = ArmorWeaponEditor(scratch, data)
            if (!usable(ed, id)) continue
            val guid = add(ed, id, g)
            val made = scratch.root.child(listTag)!!.children(itemTag).first { it.text("guid")?.trim() == guid }
            checked++
            for (k in g.children()) {
                if (k.tagName in skipTags || k.children().isNotEmpty()) continue
                val want = k.textContent.trim().replace(ws, " ")
                if (k.tagName == "weapontype" && want == "gear") continue // weapons that come with gear (grenades), not picked directly
                val got = made.child(k.tagName)?.textContent?.trim()?.replace(ws, " ")
                if (want != got) bad += "${g.text("name")}: ${k.tagName} Chummer=[$want] ours=[$got]"
            }
        }
        return checked to bad
    }

    @Test
    fun armorMatchesViper() {
        val (n, bad) = compare("armors", "armor", skip + setOf("armorname", "maxrating", "equipped"), { ed, id, g -> ed.addArmor(id, g.text("rating")?.trim()?.toIntOrNull()) }, { ed, id -> ed.armorProblem(id) == null })
        println("Armor checked $n, ${bad.size} differences")
        bad.forEach { println("  ARMORDIFF $it") }
        assertTrue(n >= 3)
        assertTrue("armor differences: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun weaponsMatchViper() {
        val (n, bad) = compare(
            "weapons", "weapon", skip + setOf("weaponname", "included", "parentid", "activeammoslot", "cost", "ammo", "equipped", "mount"),
            { ed, id, _ -> ed.addWeapon(id) }, { ed, id -> ed.weaponProblem(id) == null },
        )
        println("Weapons checked $n, ${bad.size} differences")
        bad.forEach { println("  WEAPONDIFF $it") }
        assertTrue(n >= 2)
        assertTrue("weapon differences: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun buyingArmorAndWeaponsSpendsNuyenAndUndoRefunds() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val vest = data.byId.entries.first { it.value.file == "armor.xml" && it.value.name == "Armor Vest" }.key
        val knife = data.byId.entries.first { it.value.file == "weapons.xml" && it.value.name == "Knife" }.key
        val before = adv.log.nuyen
        val a = adv.buyArmor(vest)!!
        val w = adv.buyWeapon(knife)!!
        assertTrue(adv.log.nuyen < before)
        assertTrue(adv.log.balanced())
        assertTrue(c.root.textContent.contains(a) && c.root.textContent.contains(w))
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertTrue(Math.abs(adv.log.nuyen - before) < 0.001)
        assertTrue(!c.root.textContent.contains(a) && !c.root.textContent.contains(w))
    }

    private fun allOf(e: Element, tag: String, out: MutableList<Element> = ArrayList()): List<Element> { for (c in e.children()) { if (c.tagName == tag) out += c; allOf(c, tag, out) }; return out }

    @Test
    fun accessoriesMatchViper() {
        val skipA = skip + setOf("parentid", "included", "extra")
        var checked = 0
        val bad = ArrayList<String>()
        for (g in allOf(viper().root, "accessory")) {
            val id = g.text("sourceid")?.trim().orEmpty()
            if (data[id] == null) continue
            val scratch = viper()
            val ed = ArmorWeaponEditor(scratch, data)
            val weapon = scratch.root.child("weapons")!!.children("weapon").first()
            val guid = ed.addAccessory(weapon.text("guid")!!.trim(), id, mount = g.text("mount")?.trim(), rating = g.text("rating")?.trim()?.toIntOrNull())
            val made = allOf(scratch.root, "accessory").first { it.text("guid")?.trim() == guid }
            checked++
            for (k in g.children()) {
                if (k.tagName in skipA || k.children().isNotEmpty()) continue
                val want = k.textContent.trim().replace(ws, " ")
                val got = made.child(k.tagName)?.textContent?.trim()?.replace(ws, " ")
                if (want != got) bad += "${g.text("name")}: ${k.tagName} Chummer=[$want] ours=[$got]"
            }
        }
        println("Accessories checked $checked, ${bad.size} differences")
        bad.take(30).forEach { println("  ACCDIFF $it") }
        assertTrue(checked >= 40)
        assertTrue("accessory differences: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun armorModsMatchViper() {
        val skipM = skip + setOf("included", "notes", "armor", "bonus") // Chummer keeps the bonus node on some mods and not others; the improvements are what count
        var checked = 0
        val bad = ArrayList<String>()
        for (g in allOf(viper().root, "armormod")) {
            val id = g.text("sourceid")?.trim().orEmpty()
            if (data[id] == null) continue
            val scratch = viper()
            val ed = ArmorWeaponEditor(scratch, data)
            val armor = scratch.root.child("armors")!!.children("armor").first()
            val guid = ed.addArmorMod(armor.text("guid")!!.trim(), id, rating = g.text("rating")?.trim()?.toIntOrNull())
            val made = allOf(scratch.root, "armormod").first { it.text("guid")?.trim() == guid }
            checked++
            for (k in g.children()) {
                if (k.tagName in skipM || k.children().isNotEmpty()) continue
                val want = k.textContent.trim().replace(ws, " ")
                val got = made.child(k.tagName)?.textContent?.trim()?.replace(ws, " ")
                if (want != got) bad += "${g.text("name")}: ${k.tagName} Chummer=[$want] ours=[$got]"
            }
        }
        println("Armor mods checked $checked, ${bad.size} differences")
        bad.take(30).forEach { println("  MODDIFF $it") }
        assertTrue(checked >= 10)
        assertTrue("armor mod differences: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun defaultPartsComeWithTheItem() {
        val c = viper()
        val scratch = viper()
        val ed = ArmorWeaponEditor(scratch, data)
        val ingram = data.byId.entries.first { it.value.file == "weapons.xml" && it.value.name == "Ingram Smartgun X" }.key
        val w = ed.addWeapon(ingram)
        val made = scratch.root.child("weapons")!!.children("weapon").first { it.text("guid")?.trim() == w }
        val want = c.root.child("weapons")!!.children("weapon").first { it.text("name")?.trim() == "Ingram Smartgun X" }
        fun inc(e: Element) = e.child("accessories")!!.children("accessory").filter { it.text("included") == "True" }.map { it.text("name") + "@" + it.text("mount") }.sorted()
        assertTrue(inc(made).isNotEmpty())
        org.junit.Assert.assertEquals(inc(want), inc(made))
        // The smartgun system brings its range finder and camera.
        val sg = allOf(made, "accessory").first { it.text("name") == "Smartgun System, Internal" }
        org.junit.Assert.assertEquals(listOf("Laser Range Finder", "Camera, Micro"), sg.child("gears")!!.children("gear").map { it.text("name") })
        assertTrue(adv(scratch).log.balanced())
    }

    private fun adv(c: CharacterFile) = CareerAdvancement(c, data, settings)

    @Test
    fun buyingPartsSpendsNuyenAndUndoRefunds() {
        val c = viper()
        val a = adv(c)
        val before = a.log.nuyen
        val weapon = c.root.child("weapons")!!.children("weapon").first { it.text("name")?.trim() == "Ruger Super Warhawk" }.text("guid")!!.trim()
        val armor = c.root.child("armors")!!.children("armor").first { it.text("name")?.trim() == "Armor Jacket" }.text("guid")!!.trim()
        val sling = data.byId.entries.first { it.value.file == "weapons.xml" && it.value.element.tagName == "accessory" && it.value.name == "Sling" }.key
        val pack = data.byId.entries.first { it.value.file == "armor.xml" && it.value.element.tagName == "mod" && it.value.name == "Gel Packs" }.key
        val s1 = a.buyAccessory(weapon, sling)!!
        val m1 = a.buyArmorMod(armor, pack)!!
        assertEquals(before - 15.0 - 1500.0, a.log.nuyen, 0.001)
        assertTrue(a.log.balanced())
        assertTrue(c.root.textContent.contains(s1) && c.root.textContent.contains(m1))
        assertTrue(a.log.undo(a.log.entries().last().guid))
        assertTrue(a.log.undo(a.log.entries().last().guid))
        assertEquals(before, a.log.nuyen, 0.001)
        assertTrue(!c.root.textContent.contains(s1) && !c.root.textContent.contains(m1))
    }

    @Test
    fun underbarrelWeaponsAndArmorGearComeWithTheItem() {
        val scratch = viper()
        val ed = ArmorWeaponEditor(scratch, data)
        val ak = data.byId.entries.first { it.value.file == "weapons.xml" && it.value.element.tagName == "weapon" && it.value.name == "AK-98" }.key
        val w = ed.addWeapon(ak)
        val made = scratch.root.child("weapons")!!.children("weapon").first { it.text("guid")?.trim() == w }
        val ub = made.child("underbarrel")!!.children("weapon").single()
        assertEquals("AK-98 Grenade Launcher", ub.text("name"))
        assertEquals("True", ub.text("included"))
        assertEquals("0", ub.text("cost"))
        assertEquals(w, ub.text("parentid")?.trim())
        assertEquals("glauncher", ub.text("weapontype"))
        val clothes = data.byId.entries.first { it.value.file == "armor.xml" && it.value.element.tagName == "armor" && it.value.name == "Actioneer Business Clothes" }.key
        val a = ed.addArmor(clothes)
        val armor = scratch.root.child("armors")!!.children("armor").first { it.text("guid")?.trim() == a }
        assertEquals(listOf("Concealable Holster"), armor.child("gears")!!.children("gear").map { it.text("name") })
        // The gear in Viper's Custom Ballistic Mask fits its capacity of 8, and one more does not.
        val mask = data.byId.entries.first { it.value.file == "armor.xml" && it.value.element.tagName == "armor" && it.value.name == "Custom Ballistic Mask" }.key
        val m = ed.addArmor(mask)
        val want = viper().root.child("armors")!!.children("armor").first { it.text("name") == "Custom Ballistic Mask" }
        for (g in want.child("gears")!!.children("gear")) {
            val id = g.text("sourceid")!!.trim()
            assertEquals(g.text("name"), null, ed.armorGearProblem(m, id))
            ed.addArmorGear(m, id, g.text("rating")?.trim()?.toIntOrNull())
        }
        val left = ed.armorCapacityLeft(m)!!
        assertTrue("capacity left $left", left >= 0.0)
        // Removing gear that sits inside armor works too.
        val first = scratch.root.child("armors")!!.children("armor").first { it.text("guid")?.trim() == m }.child("gears")!!.children("gear").first().text("guid")!!.trim()
        assertTrue(GearEditor(scratch, data).removeGear(first))
    }

    @Test
    fun loadingAmmoSplitsTheStackAndFillsTheClip() {
        val c = viper()
        val ed = ArmorWeaponEditor(c, data)
        val ruger = c.root.child("weapons")!!.children("weapon").first { it.text("name") == "Ruger Super Warhawk" }
        val w = ruger.text("guid")!!.trim()
        // An unloaded slot: Viper's file has all six loaded, so empty one first.
        val clip = ruger.child("clips")!!.children("clip")[0]
        clip.child("count")!!.textContent = "0"; clip.child("location")!!.textContent = ""
        val offered = ed.compatibleAmmo(w).filter { it.name == "Ammo: Regular Ammo" && it.quantity > 6 }.map { it.guid }
        val ammo = c.root.child("gears")!!.children("gear").first { it.text("guid")?.trim() in offered }
        val before = ammo.text("qty")!!.trim().toDouble()
        val totalBefore = c.root.child("gears")!!.children("gear").filter { it.text("name") == "Ammo: Regular Ammo" }.sumOf { it.text("qty")!!.trim().toDouble() }
        assertTrue(ed.loadAmmo(w, ammo.text("guid")!!.trim(), 1))
        assertEquals(before - 6.0, ammo.text("qty")!!.trim().toDouble(), 0.001)
        assertEquals("6", clip.text("count"))
        assertEquals("loaded", clip.text("location"))
        val totalAfter = c.root.child("gears")!!.children("gear").filter { it.text("name") == "Ammo: Regular Ammo" }.sumOf { it.text("qty")!!.trim().toDouble() }
        assertEquals(totalBefore, totalAfter, 0.001) // rounds are moved, not created
        assertTrue(!ed.loadAmmo(w, ammo.text("guid")!!.trim(), 1)) // the slot is full now
        // Unloading puts the rounds back into the loose stack and empties the clip.
        assertTrue(ed.unloadAmmo(w, 1))
        assertEquals("0", clip.text("count"))
        // The rounds merge into the first loose stack of the same ammunition (which need not be the one they came from).
        assertEquals(totalBefore, c.root.child("gears")!!.children("gear").filter { it.text("name") == "Ammo: Regular Ammo" }.sumOf { it.text("qty")!!.trim().toDouble() }, 0.001)
        assertTrue(!ed.unloadAmmo(w, 1))
    }
}
