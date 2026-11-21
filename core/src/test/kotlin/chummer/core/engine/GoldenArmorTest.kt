package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.parseXml
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Armor checks for Viper against Chummer's own numbers:
 *  - the print export (Social Situations set worn): armor against each damage type;
 *  - the saved file (Runner Armor set worn): the armor encumbrance Chummer stored as an improvement.
 */
class GoldenArmorTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")

    private val c = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings = CharacterSettings.find(
        c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")),
    )
    private val rules = settings.toRuleSettings()

    private fun engine(armors: List<chummer.core.CharacterFile.ArmorItem>, set: ImprovementSet, str: Int) =
        ArmorEngine(
            armors, set, str,
            uncappedAccessories = settings.bool("uncappedarmoraccessorybonuses"),
            noEncumbrance = settings.bool("noarmorencumbrance"),
            armorDegradation = settings.bool("armordegredation"),
        )

    @Test
    fun socialSetMatchesExport() {
        // Export state: Kamikaze on, the "Spell" CHA improvement, Social Situations set worn.
        val spellCha = Improvement("Attribute", "Custom", "CHA", "", "", 0.0, 3.0, 0.0, 0.0, 0.0, 1.0, false, true, false, "")
        val improvements = Improvement.parseAll(c.root)
            .filter { it.source != "ArmorEncumbrance" }
            .map { if (it.customGroup == "Kamikaze") it.copy(enabled = true) else it } + spellCha
        val set = ImprovementSet(improvements, isCareer = true)
        val str = AttributeEngine.forSet(c, set, rules).getValue("STR").totalValue

        val social = setOf("754ff734-f00b-4976-a513-70212b9d437e") // the rating 2 cloak with Fire Resistance
        val armors = c.armors().map { it.copy(equipped = it.guid in social || it.name.startsWith("Mortimer of London")) }
        val e = engine(armors, set, str)

        val export = parseXml(File(ref, "exports/Viper_career.xml")).documentElement.children("character").first()
        val fields = mapOf(
            "armor" to "Armor", "firearmor" to "FireArmor", "coldarmor" to "ColdArmor",
            "electricityarmor" to "ElectricityArmor", "acidarmor" to "AcidArmor", "fallingarmor" to "FallingArmor",
        )
        for ((field, type) in fields) {
            val want = export.text(field)!!.trim().toInt()
            assertEquals(field, want, e.rating(type))
        }
        assertEquals("encumbrance with the Social set", 0, e.encumbrance())
    }

    @Test
    fun runnerSetEncumbranceMatchesSavedFile() {
        val set = ImprovementSet(Improvement.parseAll(c.root), isCareer = true)
        val str = AttributeEngine.forSet(c, set, rules).getValue("STR").totalValue
        val stored = Improvement.parseAll(c.root).filter { it.source == "ArmorEncumbrance" }
        println("stored encumbrance improvements: " + stored.map { "${it.improvedName} val=${it.value} aug=${it.augmented} r=${it.rating}" })
        val got = engine(c.armors(), set, str).encumbrance()
        println("STR=$str computed encumbrance=$got")
        val want = stored.firstOrNull { it.improvedName == "AGI" }?.let { (it.augmented * it.rating).toInt() } ?: 0
        assertEquals("encumbrance with the Runner set", want, got)
    }
}
