package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.data.CustomDataLocator
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Martial arts: the layout against Gentle Earthquake's Carromeleg, career purchases with undo, and the creation ledger. */
class MartialArtEditorTest {
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
    private fun art(name: String) = data.byId.entries.first { it.value.file == "martialarts.xml" && it.value.element.tagName == "martialart" && it.value.name == name }.key

    @Test
    fun aStyleIsSavedAsChummerSavesIt() {
        val want = CharacterIo.readFile(File(src, "Chummer.Tests/TestFiles/Gentle Earthquake.chum5")).root.child("martialarts")!!.children("martialart").single()
        val c = viper()
        val ed = MartialArtEditor(c, data)
        val guid = ed.addArt(art("Carromeleg"))
        val made = ed.art(guid)!!
        // Same fields in the same order as Chummer's own file.
        assertEquals(want.children().map { it.tagName }, made.children().map { it.tagName })
        for (tag in listOf("name", "source", "page", "rating", "cost", "isquality")) assertEquals(tag, want.text(tag), made.text(tag))
        // Its bonus is kept as an improvement from the MartialArt source: the skill, and the style's name as the unique name.
        val imp = c.root.child("improvements")!!.children("improvement").single { it.text("sourcename") == guid }
        assertEquals("SkillSpecializationOption", imp.text("improvementttype")); assertEquals("MartialArt", imp.text("improvementsource"))
        assertEquals("Unarmed Combat", imp.text("improvedname")); assertEquals("Carromeleg", imp.text("unique"))
        val technique = MartialArtCatalog.techniquesFor(data, art("Carromeleg")).first { it.name == "Iaijutsu" }
        val tg = ed.addTechnique(guid, technique.id)
        val t = made.child("martialarttechniques")!!.children("martialarttechnique").single()
        assertEquals(tg, t.text("guid")); assertEquals("Iaijutsu", t.text("name")); assertEquals(technique.id, t.text("sourceid"))
        // Taking the style out takes its improvements with it.
        assertTrue(ed.removeArt(guid))
        assertTrue(c.root.child("improvements")!!.children("improvement").none { it.text("sourcename") == guid })
    }

    @Test
    fun careerPurchasesCostKarmaAndUndoGivesItBack() {
        val c = viper()
        val adv = CareerAdvancement(c, data, settings)
        adv.log.manualKarma(100, "Test reward")
        val start = adv.log.karma
        val style = art("Tae Kwon Do")
        val guid = adv.buyMartialArt(style)!!
        assertEquals(start - 7, adv.log.karma)
        val techniques = MartialArtCatalog.techniquesFor(data, style)
        assertTrue(techniques.size >= 3)
        // The first technique is free, each after costs 5.
        val t1 = adv.buyTechnique(guid, techniques[0].id)!!
        assertEquals(start - 7, adv.log.karma)
        val t2 = adv.buyTechnique(guid, techniques[1].id)!!
        assertEquals(start - 12, adv.log.karma)
        assertTrue(adv.log.balanced())
        // Undo, newest first.
        assertTrue(adv.log.undo(adv.log.entries().last().guid))
        assertEquals(start - 7, adv.log.karma)
        assertTrue(MartialArtEditor(c, data).art(guid)!!.child("martialarttechniques")!!.children("martialarttechnique").none { it.text("guid") == t2 })
        assertTrue(adv.log.undo(adv.log.entries().last().guid)) // the free technique
        assertTrue(adv.log.undo(adv.log.entries().last().guid)) // the style
        assertEquals(start, adv.log.karma)
        assertEquals(null, MartialArtEditor(c, data).art(guid))
        assertTrue(t1.isNotEmpty())
        // Cannot afford: nothing changes.
        adv.log.manualKarma(-adv.log.karma, "Spend all")
        assertEquals(null, adv.buyMartialArt(style))
    }

    @Test
    fun creationCostsAreTheLedgers() {
        val ed = MartialArtEditor(CharacterIo.readFile(File(src, "Chummer.Tests/TestFiles/Apex Predator.chum5")), data)
        val c = ed.let { CharacterIo.readFile(File(src, "Chummer.Tests/TestFiles/Gentle Earthquake.chum5")) }
        val adv = CareerAdvancement(c, data, CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), emptyList()))
        val s = CharacterSettings.find(c.root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), emptyList())
        val before = KarmaLedger.of(c, s, data).martialArts
        assertEquals(7, before) // Carromeleg with its one technique
        val style = art("Tae Kwon Do")
        val guid = adv.buyMartialArt(style)!!
        assertEquals(before + 7, KarmaLedger.of(c, s, data).martialArts)
        val techniques = MartialArtCatalog.techniquesFor(data, style)
        adv.buyTechnique(guid, techniques[0].id)
        adv.buyTechnique(guid, techniques[1].id)
        assertEquals(before + 7 + 5, KarmaLedger.of(c, s, data).martialArts)
        assertTrue(adv.removeMartialArtItem(guid))
        assertEquals(before, KarmaLedger.of(c, s, data).martialArts)
        assertFalse(adv.removeMartialArtItem(guid))
    }
}
