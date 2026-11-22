package chummer.core.engine

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

/** Complex forms: the saved layout, the free count and karma price in creation, the limit, and career purchases with undo. */
class ComplexFormTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key
    private fun form(name: String) = ComplexForms.catalog(data).first { it.name == name }.id
    private fun technomancer() = NewCharacter.createPriority(
        data, settings, NewCharacterRequest(name = "T", metatypeId = human()),
        PriorityChoice("E", "A", "B", "C", "D", "Technomancer", true, talentSkills = PriorityTable(data).let { t -> t.skillChoices(t.talents("B").first { it.value == "Technomancer" }).take(2) }),
    )

    @Test
    fun aTechnomancerStartsWithResonanceAndFreeForms() {
        val c = technomancer()
        assertEquals("True", c.root.text("resenabled"))
        val sheet = Sheet.build(c, data, settings)
        val section = sheet.complexForms!!
        assertEquals(PriorityTable(data).talents("B").first { it.value == "Technomancer" }.complexForms, section.free)
        assertEquals(section.free, c.root.text("cfplimit")?.toInt())
        assertTrue(section.limit >= section.free)
    }

    @Test
    fun aFormIsSavedAsChummerSavesIt() {
        val c = technomancer()
        val guid = CharacterEditor(c, data, settings = settings).addComplexForm(form("Cleaner"))
        val f = c.root.child("complexforms")!!.children("complexform").single()
        assertEquals(listOf("guid", "name", "target", "duration", "fv", "extra", "source", "page", "notes", "grade"), f.children().map { it.tagName })
        assertEquals(guid, f.text("guid")); assertEquals("Persona", f.text("target")); assertEquals("0", f.text("grade"))
        assertTrue(CharacterEditor(c, data, settings = settings).removeItem(guid))
        assertTrue(c.root.child("complexforms")!!.children().isEmpty())
    }

    @Test
    fun formsPastTheFreeCountCostKarma() {
        val c = technomancer()
        val free = c.root.text("cfplimit")!!.toInt()
        val editor = CharacterEditor(c, data, settings = settings)
        val ids = ComplexForms.catalog(data).filter { !it.needsChoice }.map { it.id }
        val karma0 = KarmaLedger.of(c, settings, data).remaining
        repeat(free) { editor.addComplexForm(ids[it]) }
        assertEquals(karma0, KarmaLedger.of(c, settings, data).remaining)
        editor.addComplexForm(ids[free])
        assertEquals(karma0 - 4, KarmaLedger.of(c, settings, data).remaining)
    }

    @Test
    fun careerPurchasesCostKarmaAndUndoGivesItBack() {
        val c = technomancer()
        assertTrue(CharacterFinisher.finish(c, settings, data, startingNuyen = 0).isEmpty())
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(50, "Reward")
        val start = adv.log.karma
        assertEquals(4, adv.complexFormCost())
        val g = adv.addComplexForm(form("Cleaner"))
        assertNotNull(g)
        assertEquals(start - 4, adv.log.karma)
        assertNull(adv.addComplexForm(form("Cleaner"))) // already known
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(start, adv.log.karma)
        assertTrue(c.root.child("complexforms")!!.children().isEmpty())
        // Not enough karma: nothing changes.
        adv.log.manualKarma(-adv.log.karma, "Spend all")
        assertNull(adv.addComplexForm(form("Cleaner")))
    }

    @Test
    fun spritesAreRegisteredUpToCharismaAndLevelUpToResonance() {
        val c = technomancer()
        val sprites = SpriteEditor(c, data, settings)
        val res = AttributeEngine.forFile(c, settings.toRuleSettings())["RES"]!!.totalValue
        assertEquals(res, sprites.maxLevel())
        val cha = AttributeEngine.forFile(c, settings.toRuleSettings())["CHA"]!!.totalValue
        assertEquals(cha, sprites.registeredLimit())
        val options = sprites.options()
        assertTrue(options.toString(), options.any { it.name == "Machine Sprite" })
        val machine = options.first { it.name == "Machine Sprite" }.id
        // Too high a level is refused; the default is the highest allowed.
        assertNull(sprites.add(machine, "Too strong", force = res + 1))
        val guid = sprites.add(machine, "Purphoros")!!
        val s = c.root.child("spirits")!!.children("spirit").single()
        assertEquals(listOf("guid", "name", "crittername", "services", "force", "bound", "fettered", "type", "file", "relative", "notes", "mainmugshotindex", "mugshots"), s.children().map { it.tagName })
        assertEquals("Sprite", s.text("type")); assertEquals("True", s.text("bound")); assertEquals(res.toString(), s.text("force"))
        assertEquals(listOf(SpriteLine(guid, "Machine Sprite", "Purphoros", res, 1, true)), sprites.sprites())
        // The registered limit stops the next one.
        var made = 1
        while (sprites.add(machine, "n$made") != null) made++
        assertEquals(cha, made)
        assertTrue(sprites.remove(guid))
        assertEquals(made - 1, sprites.sprites().size)
        assertEquals(made - 1, Sheet.build(c, data, settings).complexForms!!.sprites.size)
    }

    @Test
    fun aSpriteCanBeRenamedAndItsLevelAndTasksChanged() {
        val c = technomancer()
        val sprites = SpriteEditor(c, data, settings)
        val machine = sprites.options().first { it.name == "Machine Sprite" }.id
        val guid = sprites.add(machine, "", force = 2)!!
        assertTrue(sprites.update(guid, critterName = "Purphoros", force = 3, services = 4))
        assertEquals(listOf(SpriteLine(guid, "Machine Sprite", "Purphoros", 3, 4, true)), sprites.sprites())
        // Too strong, negative tasks, and unregistering in creation are refused.
        assertFalse(sprites.update(guid, force = sprites.maxLevel() + 1))
        assertFalse(sprites.update(guid, services = -1))
        assertFalse(sprites.update(guid, bound = false))
        assertFalse(sprites.update("nope", force = 1))
    }
}

/** Focus binding costs. */
class FocusCostTest {
    @Test
    fun aFocusCostsItsForceTimesThePriceOfItsKind() {
        assertEquals(8, FocusCost.of("Qi Focus", 4, emptyMap()))
        assertEquals(24, FocusCost.of("Power Focus", 4, emptyMap()))
        // The settings' price wins, a choice in brackets or after a comma is ignored, and an individualized focus is cheaper.
        assertEquals(12, FocusCost.of("Weapon Focus (Sword)", 3, mapOf("karmaweaponfocus" to 4)))
        assertEquals(10, FocusCost.of("Spellcasting Focus, Individualized, Partial", 3, emptyMap()) + 5)
        assertEquals(4, FocusCost.of("Sustaining Focus, Individualized, Complete", 3, emptyMap()))
    }
}
