package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The career log and karma spending, checked against Viper's own 215-entry log. */
class CareerAdvancementTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val campaign = File(ref, "Viper_career")
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy {
        CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(campaign, "settings")))
    }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(campaign, "customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }

    @Test
    fun storedBalancesAreTheSumOfTheLog() {
        val log = ExpenseLog(viper())
        assertEquals(215, log.entries().size)
        assertTrue(log.balanced())
        assertEquals(5, log.karma)
    }

    @Test
    fun improvementPricesMatchViperLog() {
        val k = settings.karmaCost
        val unit = k["karmaattribute"] ?: 5
        val step = Regex("""^(Attribute|Knowledge Skill|Skill) (.+) (\d+) -> (\d+)$""")
        var checked = 0
        for (e in ExpenseLog(viper()).entries().filter { it.isKarma }) {
            val m = step.find(e.reason) ?: continue
            val (kind, _, from, to) = m.destructured
            assertEquals(from.toInt() + 1, to.toInt())
            val expected = when (kind) {
                "Attribute" -> if (from == "0") unit else to.toInt() * unit
                "Knowledge Skill" -> if (from == "0") k["karmanewknowledgeskill"]!! else to.toInt() * k["karmaimproveknowledgeskill"]!!
                else -> if (from == "0") k["karmanewactiveskill"]!! else to.toInt() * k["karmaimproveactiveskill"]!!
            }
            assertEquals(e.reason, -expected.toDouble(), e.amount, 0.001)
            checked++
        }
        assertTrue("only $checked priced entries", checked > 40)
    }

    @Test
    fun improvingKnowledgeSkillSpendsKarmaAndUndoRestores() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c)
        log.manualKarma(50, "Test reward")
        val skill = c.skills().first { it.isKnowledge && it.base + it.karma in 1..4 }
        val before = log.karma
        val offer = adv.skillOffer(skill.guid)
        assertTrue(offer.available)
        assertTrue(adv.improveSkill(skill.guid))
        assertEquals(before - offer.cost!!, log.karma)
        assertEquals(skill.karma + 1, c.skills().first { it.guid == skill.guid }.karma)
        assertTrue(log.balanced())
        val entry = log.entries().last()
        assertTrue(log.undo(entry.guid))
        assertEquals(before, log.karma)
        assertEquals(skill.karma, c.skills().first { it.guid == skill.guid }.karma)
        assertTrue(log.balanced())
    }

    @Test
    fun attributeImprovementNeedsKarmaAndLogsIt() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c)
        val offer = adv.attributeOffer("AGI")
        assertEquals(false, offer.available && log.karma < offer.cost!!)
        log.manualKarma(100, "Test reward")
        assertTrue(adv.improveAttribute("AGI"))
        val e = log.entries().last()
        assertEquals("ImproveAttribute", e.undoKind)
        assertEquals("AGI", e.objectId)
        assertTrue(log.balanced())
        assertTrue(log.undo(e.guid))
        assertFalse(log.entries().any { it.guid == e.guid })
        assertTrue(log.balanced())
    }

    @Test
    fun cannotAffordIsRefused() {
        val c = viper()
        val log = ExpenseLog(c)
        log.manualKarma(-log.karma, "Spend all")
        val adv = CareerAdvancement(c, data, settings)
        assertFalse(adv.improveAttribute("AGI"))
        assertEquals(0, log.karma)
    }

    @Test
    fun specializationCostsSevenAndUndoRemovesIt() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val log = ExpenseLog(c)
        log.manualKarma(30, "Test reward")
        val skill = c.skills().first { !it.isKnowledge && it.base + it.karma > 0 && it.specializations.isEmpty() }
        assertEquals(7, adv.specializationOffer(skill.guid).cost)
        val choice = adv.specializationChoices(skill.guid).firstOrNull() ?: "Anything"
        val before = log.karma
        assertTrue(adv.addSpecialization(skill.guid, choice))
        assertEquals(before - 7, log.karma)
        assertEquals(listOf(choice), c.skills().first { it.guid == skill.guid }.specializations)
        assertEquals(null, adv.specializationOffer(skill.guid).cost)
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(before, log.karma)
        assertTrue(c.skills().first { it.guid == skill.guid }.specializations.isEmpty())
    }

    @Test
    fun qualityPurchaseCostsDoubleAndUndoRemovesIt() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val toughness = data.byId.entries.first { it.value.file == "qualities.xml" && it.value.name == "Toughness" }.key
        val log = adv.log
        log.manualKarma(40, "Test reward")
        val before = log.karma
        val cost = adv.qualityCost(toughness)!!
        assertEquals(2 * 9 * (settings.karmaCost["karmaquality"] ?: 1) , cost)
        val guid = adv.addQuality(toughness)!!
        assertEquals(before - cost, log.karma)
        assertTrue(c.root.let { r -> r.textContent.contains(guid) })
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(before, log.karma)
        assertTrue(!c.root.textContent.contains(guid))
    }

    @Test
    fun spellsAndQualityRemoval() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        val log = adv.log
        log.manualKarma(100, "Test reward")
        val ball = data.byId.entries.first { it.value.file == "spells.xml" && it.value.name == "Ball Lightning" }.key
        val cost = adv.spellCost(ball)!!
        assertEquals(settings.karmaCost["karmaspell"] ?: 5, cost)
        val before = log.karma
        val spell = adv.addSpell(ball)!!
        assertEquals(before - cost, log.karma)
        assertTrue(c.root.textContent.contains(spell))
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(before, log.karma)
        assertTrue(!c.root.textContent.contains(spell))
        // A positive quality leaves for free; a negative one is bought off at double.
        val q = c.root.getElementsByTagName("quality")
        val removal = adv.qualityRemoval(c)
        assertTrue(removal.values.any { it == 0 })
    }

    private fun CareerAdvancement.qualityRemoval(c: chummer.core.CharacterFile) =
        c.root.getElementsByTagName("quality").let { n -> (0 until n.length).mapNotNull { i -> (n.item(i) as org.w3c.dom.Element).getElementsByTagName("guid").item(0)?.textContent?.trim()?.let { g -> qualityRemovalCost(g)?.let { g to it } } }.toMap() }

    @Test
    fun armorGearIsPaidForInstalledAndUndone() {
        val c = viper()
        val log = ExpenseLog(c, data, settings)
        log.manualNuyen(100000.0, "Test funds")
        val adv = CareerAdvancement(c, data, settings)
        val shirt = data.byId.entries.first { it.value.file == "armor.xml" && it.value.element.tagName == "armor" && it.value.name == "Custom Ballistic Mask" }.key
        val armor = adv.buyArmor(shirt)!!
        val ed = ArmorWeaponEditor(c, data)
        val left = ed.armorCapacityLeft(armor)!!
        val options = GearCatalog.list(data).filter { (it.armorCapacity ?: 99.0) <= left && it.cost != null && it.maxRating == 0 }
        assertTrue("some gear fits", options.isNotEmpty())
        val pick = options.first()
        val before = log.nuyen
        val guid = adv.buyArmorGear(armor, pick.id)!!
        assertEquals(before - pick.cost!!, log.nuyen.toDouble(), 0.01)
        assertTrue(ed.armorCapacityLeft(armor)!! < left)
        assertTrue(log.balanced())
        assertTrue(log.undo(log.entries().last().guid))
        assertEquals(before.toDouble(), log.nuyen.toDouble(), 0.01)
        assertEquals(left, ed.armorCapacityLeft(armor)!!, 0.001)
        assertTrue(guid.isNotEmpty())
    }
}
