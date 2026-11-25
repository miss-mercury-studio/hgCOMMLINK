package chummer.core.engine

import chummer.core.*
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes a copy of Viper with this session's gear and armor changes applied to `core/build/samples/AppEdited_Viper_GearArmor.chum5`, and a
 * `.txt` beside it saying what to look for, for opening in desktop Chummer by hand. Desktop Chummer is the reference, and has never opened a file
 * these editors changed. The assertions only guard that the file reads back with what the note promises.
 */
class GearArmorSampleTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))

    @Test
    fun writeGearAndArmorSampleToTryInDesktopChummer() {
        val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
        val settings = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings")))
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        val data = DataPack.load(File(src, "Chummer/data"), dirs.dirs)
        val adv = CareerAdvancement(c, data, settings)
        val aw = ArmorWeaponEditor(c, data)
        val gear = GearEditor(c, data, settings)
        val notes = ArrayList<String>()
        fun armor(name: String) = c.armors().first { it.name == name }
        fun entry(name: String) = data.byId.entries.first { it.value.file == "gear.xml" && it.value.element.tagName == "gear" && it.value.name == name }.key
        fun carried(name: String, extra: String = "") = c.root.child("gears")!!.children("gear").filter { it.text("name")?.trim() == name && it.text("extra")?.trim().orEmpty() == extra }

        c.root.child("alias")?.textContent = "GearArmorCheck"
        notes += "Character: alias GearArmorCheck (a copy of Viper). Open it in desktop Chummer and compare each item below."

        // 1. A set holding two armors, worn, with the Runner Armor set taken off.
        val set = aw.addArmorSet("Night Out")!!
        aw.moveArmorToSet(armor("Designer Cloak").guid, set)
        aw.moveArmorToSet(armor("Mortimer of London: Berwick Dress").guid, set)
        aw.setArmorSetEquipped(c.armorLocations().entries.first { it.value == "Runner Armor" }.key, false)
        aw.setArmorSetEquipped(set, true)
        notes += "ARMOR TAB: a new location \"Night Out\" holds Designer Cloak and Mortimer of London: Berwick Dress, both equipped. Every armor in \"Runner Armor\" is unequipped (checkbox off)."
        notes += "ARMOR TAB: the Armor Jacket's Gel Packs mod is switched off (equipped box off) while the jacket itself is not worn."
        val jacket = armor("Armor Jacket")
        val gel = jacket.mods.first { it.name == "Gel Packs" }
        aw.setArmorModEquipped(gel.guid, false)

        // 2. Ammunition bought into an existing stack, and loose stacks merged.
        val smgBefore = GearStacks.quantity(carried("Ammo: Regular Ammo", "Submachine Guns").first())
        adv.buyGear(entry("Ammo: Regular Ammo"), null, 5.0, "Submachine Guns")
        val smgStacks = carried("Ammo: Regular Ammo", "Submachine Guns")
        notes += "GEAR TAB: \"Ammo: Regular Ammo (Submachine Guns)\" was ${GearStacks.format(smgBefore)} rounds in ${smgStacks.size + 0} stack(s) before buying 5; it should now show ${GearStacks.format(GearStacks.quantity(smgStacks.first()))}."
        val chipBefore = GearStacks.quantity(carried("Datachip").first())
        val chipId = entry("Datachip")
        val added = gear.addGear(chipId, null, 3.0)
        gear.mergeStacks(carried("Datachip").first { it.text("guid")?.trim() != added }.text("guid")!!.trim())
        notes += "GEAR TAB: one \"Datachip\" line with quantity ${GearStacks.format(chipBefore + 3.0)} (was ${GearStacks.format(chipBefore)}; 3 more were added and merged)."

        // 3. A plug-in bought into the Fake SIN, and one of its licenses switched off.
        val sin = carried("Fake SIN", "Calfree").first()
        val sinGuid = sin.text("guid")!!.trim()
        val credstick = adv.gearPluginOptions(sinGuid).first { it.name == "Certified Credstick, Standard" }
        val partsBefore = sin.child("children")!!.children("gear").size
        adv.buyGearPlugin(sinGuid, credstick.id)
        val medical = sin.child("children")!!.children("gear").first { it.text("extra")?.trim() == "Medical License" }
        gear.setEquipped(medical.text("guid")!!.trim(), false)
        notes += "GEAR TAB: Fake SIN (Calfree) has ${partsBefore + 1} items inside it: a new Certified Credstick, Standard, and the Medical License is not equipped."
        notes += "NUYEN: ${adv.log.nuyen} (the career log has Purchased Gear entries for the ammo, the credstick; the datachips were added without paying)."

        val out = File("build/samples").apply { mkdirs() }
        File(out, "AppEdited_Viper_GearArmor.chum5").outputStream().use { CharacterIo.write(c, it) }
        File(out, "AppEdited_Viper_GearArmor.txt").writeText(notes.joinToString("\n\n") + "\n")

        // It reads back with what the note says.
        val back = CharacterIo.readFile(File(out, "AppEdited_Viper_GearArmor.chum5"))
        assertEquals("Night Out", back.armorLocations()[set])
        assertTrue(back.armors().filter { it.location == set }.map { it.name }.containsAll(listOf("Designer Cloak", "Mortimer of London: Berwick Dress")))
        assertTrue(back.armors().filter { it.location == set }.all { it.equipped })
        assertTrue(back.armors().first { it.name == "Armor Jacket" }.mods.first { it.name == "Gel Packs" }.equipped.not())
        assertEquals(1, back.root.child("gears")!!.children("gear").count { it.text("name")?.trim() == "Datachip" })
        // Desktop Chummer 5.226 opened this file and showed Armor 12 in its condition monitor panel.
        val rules = settings.toRuleSettings()
        val improvements = ImprovementSet(Improvement.parseAll(back.root), isCareer = true)
        val str = AttributeEngine.forSet(back, improvements, rules).getValue("STR").totalValue
        val total = ArmorEngine(
            back.armors(), improvements, str,
            uncappedAccessories = settings.bool("uncappedarmoraccessorybonuses"),
            noEncumbrance = settings.bool("noarmorencumbrance"),
            armorDegradation = settings.bool("armordegredation"),
        ).rating("Armor")
        println("worn: " + back.armors().filter { it.equipped }.joinToString { it.name + " (" + it.armorValue + ")" } + " -> our armor " + total)
        assertEquals("our armor total matches the 12 Chummer showed", 12, total)
        println(notes.joinToString("\n"))
    }
}
