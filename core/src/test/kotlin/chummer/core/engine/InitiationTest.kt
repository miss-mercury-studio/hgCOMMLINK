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

/** Initiation and submersion: the saved layout, the grade limit, costs, metamagics and undo. */
class InitiationTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key
    private fun career(talent: String): CharacterFile {
        val t = PriorityTable(data).talents("B").first { it.value == talent }
        val picks = if (t.pickCount > 0) PriorityTable(data).skillChoices(t).take(t.pickCount) else emptyList()
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), PriorityChoice("E", "A", "B", "C", "D", talent, true, talentSkills = picks))
        if (talent == "Magician") CharacterEditor(c, data, settings = settings).setTradition(TraditionCatalog.list(data).first().id)
        val problems = CharacterFinisher.finish(c, settings, data, startingNuyen = 0)
        assertTrue(problems.toString(), problems.isEmpty())
        return c
    }

    @Test
    fun aMagicianInitiatesUpToMagicAndPaysKarma() {
        val c = career("Magician")
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(200, "Reward"); adv.log.manualNuyen(20000.0, "Pay")
        val ed = adv.initiation()
        assertFalse(ed.technomancer)
        val mag = AttributeEngine.forFile(c, settings.toRuleSettings())["MAG"]!!.totalValue
        assertEquals(mag, ed.maxGrade())
        val start = adv.log.karma
        // Grade 1 costs the flat price plus the per-grade price.
        assertEquals(13, ed.nextCost())
        assertEquals(13 - 1, ed.nextCost(group = true)) // a tenth off, rounded
        val g = adv.initiate()!!
        assertEquals(start - 13, adv.log.karma)
        val row = c.root.child("initiationgrades")!!.children("initiationgrade").single()
        assertEquals(listOf("guid", "res", "grade", "group", "ordeal", "schooling", "notes"), row.children().map { it.tagName })
        assertEquals(g, row.text("guid")); assertEquals("False", row.text("res")); assertEquals("1", row.text("grade"))
        assertEquals("1", c.root.text("initiategrade"))
        // A grade cannot pass Magic.
        while (ed.grade() < mag) assertNotNull(adv.initiate())
        assertNull(adv.initiate())
        // Undo: the newest grade goes, with its karma.
        val before = adv.log.karma
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(mag - 1, ed.grade())
        assertEquals(mag - 1, c.root.text("initiategrade")!!.toInt())
        assertTrue(adv.log.karma > before)
    }

    @Test
    fun theFirstMetamagicOfAGradeIsFreeAndTheNextCostsKarma() {
        val c = career("Magician")
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(200, "Reward")
        adv.initiate()
        val ed = adv.initiation()
        val options = ed.options()
        assertTrue(options.toString(), options.isNotEmpty())
        val free = options.filter { it.unmet.isEmpty() }
        assertTrue(free.size >= 2)
        val start = adv.log.karma
        val first = adv.addMetamagic(free[0].id)!!
        assertEquals(start, adv.log.karma)
        val m = c.root.child("metamagics")!!.children("metamagic").single()
        assertEquals(listOf("guid", "name", "source", "paidwithkarma", "page", "grade", "bonus", "improvementsource", "notes"), m.children().map { it.tagName })
        assertEquals("False", m.text("paidwithkarma")); assertEquals("1", m.text("grade"))
        // The second one of the same grade costs the metamagic price, and can be undone.
        assertNotNull(adv.addMetamagic(free[1].id))
        assertEquals(start - 15, adv.log.karma)
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(start, adv.log.karma)
        assertEquals(listOf(first), ed.metamagics().map { it.guid })
        // Taking the grade back takes its metamagics with it.
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertTrue(ed.metamagics().isEmpty())
    }

    @Test
    fun schoolingCostsNuyenAndIsRefundedWithTheGrade() {
        val c = career("Magician")
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(100, "Reward")
        assertNull(adv.initiate(schooling = true)) // no nuyen
        adv.log.manualNuyen(10000.0, "Pay")
        val nuyen = adv.log.nuyen
        assertNotNull(adv.initiate(schooling = true))
        assertEquals(nuyen - 10000, adv.log.nuyen, 0.001)
        assertTrue(adv.log.undo(adv.log.entries().first { it.isKarma && it.undoKind == "ImproveInitiateGrade" }.guid))
        assertEquals(nuyen, adv.log.nuyen, 0.001)
    }

    @Test
    fun aTechnomancerSubmergesAndTakesEchoes() {
        val c = career("Technomancer")
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(100, "Reward")
        val ed = adv.initiation()
        assertTrue(ed.technomancer)
        assertNotNull(adv.initiate())
        assertEquals("1", c.root.text("submersiongrade"))
        assertEquals("True", c.root.child("initiationgrades")!!.children("initiationgrade").single().text("res"))
        val echo = ed.options().first { it.unmet.isEmpty() }
        assertNotNull(adv.addMetamagic(echo.id))
        assertEquals("Echo", c.root.child("metamagics")!!.children("metamagic").single().text("improvementsource"))
        assertEquals(1, Sheet.build(c, data, settings).initiation!!.grade)
    }
}
