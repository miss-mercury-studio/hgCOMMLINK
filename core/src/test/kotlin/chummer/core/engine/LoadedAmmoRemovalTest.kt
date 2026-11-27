package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What happens to a clip when the ammunition loaded in it is removed from the gear list. Needs only Chummer's public data. */
class LoadedAmmoRemovalTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key
    private fun id(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    private fun setup(): Triple<CharacterFile, CareerAdvancement, String> {
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "Test", metatypeId = human()), PriorityChoice("C", "B", "E", "D", "A", "Mundane", true))
        val adv = CareerAdvancement(c, data, settings)
        val w = adv.buyWeapon(id("weapons.xml", "weapon", "Ares Predator V"))!!
        return Triple(c, adv, w)
    }

    @Test
    fun undoingThePurchaseOfAllTheLoadedAmmoEmptiesItsClipToo() {
        val (c, adv, w) = setup()
        // Exactly one clip's worth: the clip then uses the bought stack itself, with no split-off copy.
        val ammo = adv.buyGear(id("gear.xml", "gear", "Ammo: Regular Ammo"), null, 15.0, "Heavy Pistols")!!
        assertTrue(ArmorWeaponEditor(c, data).loadAmmo(w, ammo, 0, null))
        assertEquals(ammo, clips(c).first().text("id")!!.trim())
        assertTrue(adv.removePurchase(ammo))
        assertEquals(0, totals(c).clips.first().rounds)
    }

    @Test
    fun ammunitionLoadedFromAnotherStackIsNotTouchedWhenALooseStackGoes() {
        val (c, adv, w) = setup()
        val ammo = adv.buyGear(id("gear.xml", "gear", "Ammo: Regular Ammo"), null, 60.0, "Heavy Pistols")!!
        assertTrue(ArmorWeaponEditor(c, data).loadAmmo(w, ammo, 0, null))
        // 15 are in the clip as their own item; the 45 left over are a different item.
        assertTrue(adv.removePurchase(ammo))
        assertEquals(15, totals(c).clips.first().rounds)
        assertEquals("Ammo: Regular Ammo", totals(c).clips.first().ammo)
    }

    private fun clips(c: CharacterFile) = c.root.child("weapons")!!.children("weapon").first { it.text("name") == "Ares Predator V" }.child("clips")?.children("clip").orEmpty()
    private fun totals(c: CharacterFile) = WeaponEngine(c.root, ImprovementSet(Improvement.parseAll(c.root), isCareer = true), 5, 5).all().first { it.name == "Ares Predator V" }

    @Test
    fun removingLoadedAmmoEmptiesItsClip() {
        val (c, adv, w) = setup()
        val ammo = adv.buyGear(id("gear.xml", "gear", "Ammo: Regular Ammo"), null, 60.0, "Heavy Pistols")!!
        val ed = ArmorWeaponEditor(c, data)
        assertTrue(ed.loadAmmo(w, ammo, 0, null))
        val loaded = clips(c).first().text("id")!!.trim()
        println("CLIPS before: " + clips(c).map { it.text("id") + "/" + it.text("count") })
        // The player takes the loaded rounds out of the gear list.
        adv.removePurchase(loaded)
        println("CLIPS after:  " + clips(c).map { it.text("id") + "/" + it.text("count") } + " ammo=" + totals(c).clips.map { "${it.ammo}:${it.rounds}" })
        val t = totals(c)
        // The rounds went with the ammunition: the clip is empty, not counting rounds of nothing.
        assertEquals("a clip holds ${t.clips.first().rounds} rounds of '${t.clips.first().ammo}'", 0, t.clips.first().rounds)
        assertTrue(ed.compatibleAmmo(w).none { it.guid.equals(loaded, true) })
    }
}
