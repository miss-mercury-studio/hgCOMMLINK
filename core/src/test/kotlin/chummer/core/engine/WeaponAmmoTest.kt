package chummer.core.engine

import chummer.core.CharacterFile
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
import java.util.UUID

/**
 * What loaded ammunition does to a weapon's damage and AP, from the real ammunition entries in Chummer's game data. Each case loads one ammunition
 * into a copy of a weapon from Viper's file and compares with the same weapon holding ammunition that gives no bonus (Regular Ammo), so the expected
 * figures come from the data, not from the engine. The rules follow Chummer's Weapon.cs: an ammunition's `weaponbonus` adds to damage and AP (or
 * replaces them with `damagereplace` / `apreplace`), `damagetype` changes the type, and a flechette weapon (damage with `(f)`) uses the
 * ammunition's `flechetteweaponbonus` instead.
 */
class WeaponAmmoTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))
    private fun viper() = CharacterIo.readFile(File(ref, "Viper_career.chum5"))
    private val settings by lazy { CharacterSettings.find(viper().root.text("settings").orEmpty(), File(src, "Chummer/data/settings.xml"), listOf(File(ref, "Viper_career/settings"))) }
    private val data by lazy {
        val dirs = CustomDataLocator(listOf(File(src, "Chummer/customdata"), File(ref, "Viper_career/customdata"))).resolve(settings.customData)
        DataPack.load(File(src, "Chummer/data"), dirs.dirs)
    }

    private fun ammoEntry(name: String) = data.byId.values.first { it.file == "gear.xml" && it.name == name }.element

    /** The figures of a copy of [weapon] (changed by [tweak]) with [ammo] in its first clip, holding [rounds]. */
    private fun figures(weapon: String, ammo: String, rounds: Int = 20, tweak: (org.w3c.dom.Element) -> Unit = {}): WeaponTotals {
        val c: CharacterFile = viper()
        val doc = c.doc
        val original = c.root.child("weapons")!!.children("weapon").first { it.text("name") == weapon }
        val w = original.cloneNode(true) as org.w3c.dom.Element
        val guid = UUID.randomUUID().toString()
        w.child("guid")!!.textContent = guid
        tweak(w)
        // A gear entry for the ammunition, carrying the bonus nodes the game data gives it.
        val entry = ammoEntry(ammo)
        val gear = doc.createElement("gear")
        val gearGuid = UUID.randomUUID().toString()
        gear.appendChild(doc.createElement("guid").also { it.textContent = gearGuid })
        gear.appendChild(doc.createElement("name").also { it.textContent = ammo })
        for (tag in listOf("weaponbonus", "flechetteweaponbonus")) entry.child(tag)?.let { gear.appendChild(doc.importNode(it, true)) }
        c.root.child("gears")!!.appendChild(gear)
        val clip = w.child("clips")!!.children("clip").first()
        clip.child("id")!!.textContent = gearGuid
        clip.child("count")!!.textContent = rounds.toString()
        w.child("activeammoslot")!!.textContent = "1"
        c.root.child("weapons")!!.appendChild(w)
        val set = ImprovementSet(Improvement.parseAll(c.root), isCareer = true)
        return WeaponEngine(c.root, set, 5, 5).all().first { it.guid == guid }
    }

    private fun number(damage: String) = Regex("""^-?\d+""").find(damage)!!.value.toInt()
    private fun kind(damage: String) = damage.removePrefix(number(damage).toString())
    private fun ap(text: String) = if (text == "-") 0 else text.removePrefix("+").toInt()

    private val regular = "Ammo: Regular Ammo"

    @Test
    fun regularAmmoAddsNothing() {
        val base = figures("Ares Alpha", regular)
        assertEquals("Ares Alpha", base.name)
        assertTrue("the Alpha's own damage and AP: ${base.damage} ${base.ap}", base.damage.startsWith("11") && ap(base.ap) in -6..-2)
    }

    @Test
    fun armorPiercingLowersApAndLeavesDamage() {
        val base = figures("Ares Alpha", regular)
        val apds = figures("Ares Alpha", "Ammo: APDS")
        assertEquals(base.damage, apds.damage)
        assertEquals(ap(base.ap) - 4, ap(apds.ap))
    }

    @Test
    fun explosiveRoundsAddOneDamageAndOneAp() {
        val base = figures("Ares Alpha", regular)
        val ammo = figures("Ares Alpha", "Ammo: Explosive Rounds")
        assertEquals(number(base.damage) + 1, number(ammo.damage))
        assertEquals(kind(base.damage), kind(ammo.damage))
        assertEquals(ap(base.ap) - 1, ap(ammo.ap))
    }

    @Test
    fun hollowPointsAddDamageAndMakeApWorse() {
        val base = figures("Ares Alpha", regular)
        val ammo = figures("Ares Alpha", "Ammo: Hollow Points")
        assertEquals(number(base.damage) + 1, number(ammo.damage))
        assertEquals(ap(base.ap) + 2, ap(ammo.ap))
    }

    @Test
    fun gelRoundsChangeTheDamageTypeToStun() {
        val base = figures("Ares Alpha", regular)
        val gel = figures("Ares Alpha", "Ammo: Gel Rounds")
        assertEquals("P", kind(base.damage))
        assertEquals("S", kind(gel.damage))
        assertEquals(number(base.damage), number(gel.damage))
        assertEquals(ap(base.ap) + 1, ap(gel.ap))
    }

    @Test
    fun stickAndShockReplacesApAndLowersDamage() {
        val base = figures("Ares Alpha", regular)
        val ammo = figures("Ares Alpha", "Ammo: Stick-n-Shock")
        assertEquals("S(e)", kind(ammo.damage))
        assertEquals(number(base.damage) - 2, number(ammo.damage))
        // The ammunition's AP replaces the weapon's: -5 whatever the weapon had.
        assertEquals(-5, ap(ammo.ap))
    }

    @Test
    fun flechetteRoundsInARifleAddDamageAndAp() {
        val base = figures("Ares Alpha", regular)
        val ammo = figures("Ares Alpha", "Ammo: Flechette Rounds")
        assertEquals("P(f)", kind(ammo.damage))
        assertEquals(number(base.damage) + 2, number(ammo.damage))
        assertEquals(ap(base.ap) + 5, ap(ammo.ap))
    }

    private val slivergun: (org.w3c.dom.Element) -> Unit = {
        // A flechette weapon, as the data lists the Ares Viper Slivergun.
        it.child("damage")!!.textContent = "9P(f)"
        it.child("ap")!!.textContent = "+4"
    }

    @Test
    fun flechetteRoundsInAFlechetteWeaponAddNothingMore() {
        val base = figures("Ares Alpha", regular, tweak = slivergun)
        val ammo = figures("Ares Alpha", "Ammo: Flechette Rounds", tweak = slivergun)
        assertEquals(base.damage, ammo.damage)
        assertEquals(base.ap, ammo.ap)
        assertEquals("P(f)", kind(ammo.damage))
    }

    @Test
    fun stickAndShockFlechetteUsesItsFlechetteBonusInAFlechetteWeapon() {
        val base = figures("Ares Alpha", regular, tweak = slivergun)
        val ammo = figures("Ares Alpha", "Ammo: Stick-n-Shock Flechette Rounds", tweak = slivergun)
        assertEquals("S(e)", kind(ammo.damage))
        assertEquals(number(base.damage) - 2, number(ammo.damage))
        assertEquals(ap(base.ap) - 5, ap(ammo.ap))
        // In an ordinary rifle the same ammunition only changes the type.
        val rifle = figures("Ares Alpha", "Ammo: Stick-n-Shock Flechette Rounds")
        val rifleBase = figures("Ares Alpha", regular)
        assertEquals("S(e)", kind(rifle.damage))
        assertEquals(number(rifleBase.damage), number(rifle.damage))
        assertEquals(rifleBase.ap, rifle.ap)
    }

    @Test
    fun ammoThatReplacesDamageAndApReplacesBoth() {
        val base = figures("Ares Alpha", regular)
        val ammo = figures("Ares Alpha", "Ammo: Taurus Omni-6 Heavy")
        // The ammunition says 7P and AP -1 whatever the weapon had; the character's own bonuses still apply to both figures alike.
        assertEquals(number(base.damage) - 4, number(ammo.damage))
        assertEquals("P", kind(ammo.damage))
        assertEquals(-1, ap(ammo.ap))
    }

    @Test
    fun theWeaponReportsWhatIsLoaded() {
        val ammo = figures("Ares Alpha", "Ammo: APDS", rounds = 31)
        assertEquals("Ammo: APDS", ammo.loadedAmmo)
        assertEquals(31, ammo.loadedRounds)
    }

    @Test
    fun anEmptyWeaponReportsNothingLoaded() {
        val c = viper()
        val weapon = c.root.child("weapons")!!.children("weapon").first { it.text("name") == "Ares Alpha" }
        weapon.child("activeammoslot")?.textContent = "0"
        val set = ImprovementSet(Improvement.parseAll(c.root), isCareer = true)
        val totals = WeaponEngine(c.root, set, 5, 5).all().first { it.name == "Ares Alpha" }
        assertEquals("", totals.loadedAmmo)
        assertEquals(0, totals.loadedRounds)
    }
}
