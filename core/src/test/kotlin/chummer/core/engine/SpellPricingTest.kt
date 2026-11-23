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

/** Spell, ritual and preparation prices follow `Character.SpellKarmaCost`. */
class SpellPricingTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private val settings by lazy { CharacterSettings.find("ab2a5404-252f-4100-9efa-7ac281c5cb9b", File(src, "Chummer/data/settings.xml"), listOf(ref)) }
    private val data by lazy {
        val resolved = CustomDataLocator(listOf(File(src, "Chummer/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), resolved.dirs)
    }
    private fun spell(category: String) = data.byId.entries.first { it.value.file == "spells.xml" && it.value.element.tagName == "spell" && it.value.element.child("category")?.textContent?.trim() == category }.key

    @Test
    fun enchantmentsCostTheSpellPriceAndPreparationsAreAlchemical() {
        val c = CharacterIo.readFile(File(ref, "New_Empty.chum5"))
        val adv = CareerAdvancement(c, data, settings)
        val base = settings.int("karmaspell", 5)
        assertEquals(base, adv.spellCost(spell("Enchantments")))
        assertEquals(base, adv.spellCost(spell("Combat")))
        assertEquals(base, adv.spellCost(spell("Rituals")))
        assertEquals(base, adv.spellCost(spell("Combat"), alchemical = true))
        // A preparation is saved as alchemical, and the creation ledger counts it as one.
        val guid = CharacterEditor(c, data, settings = settings).addSpell(spell("Combat"), alchemical = true)
        val row = c.root.child("spells")!!.children("spell").first { it.text("guid")?.trim() == guid }
        assertEquals("True", row.text("alchemical")?.trim())
        assertTrue(KarmaLedger.of(c, settings, data).spent >= 0)
    }
}
