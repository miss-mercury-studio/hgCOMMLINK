package chummer.core.engine

import chummer.core.CharacterIo
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.UUID

/**
 * Replays every item's stored `<bonus>` node through [BonusHandlers] and compares the result with the
 * improvements Chummer stored for that item (matched by the item's GUID). An item counts as matching only when
 * every improvement agrees and no bonus node was left unhandled.
 */
class BonusHandlerFixtureTest {
    private val src = File(System.getProperty("chummer.src"))
    private val ref = File(System.getProperty("chummer.reference"))

    private data class Key(
        val type: String, val name: String, val value: Double, val rating: Double, val unique: String,
        val min: Double, val max: Double, val aug: Double, val augMax: Double, val source: String, val condition: String,
    )

    /** Cyberware rewrites some multipliers to a "non-retroactive" twin after installing; treat the pair as one. */
    private fun plain(type: String) = type.removeSuffix("NonRetroactive")

    /** Chummer stores a random id as the improved name of a limit modifier, so only the rest can be compared. */
    private fun nameOf(i: Improvement) = if (i.type == "LimitModifier" || i.type in OBJECT_IDS) "" else i.improvedName

    /** Improvements whose improved name is the random GUID of an object the item added. */
    private val OBJECT_IDS = setOf("MentorSpirit", "SpecificQuality", "Gear", "AddContact", "ContactForcedLoyalty", "ContactMakeFree", "ContactForceGroup")

    /** Stands in for the editor: it only has to hand out ids, since the test compares improvements. */
    private object FakeObjects : BonusObjects {
        override fun addGear(sourceGuid: String, name: String, category: String, rating: Int, qty: Double, fullCost: Boolean, parentGuid: String?) = UUID.randomUUID().toString()
        override fun addMentorSpirit(name: String) = MentorRef(UUID.randomUUID().toString(), "")
        override fun addPower(name: String) = PowerRef(name, "")
        override fun addContact(loyalty: Int, connection: Int, group: Boolean, readOnly: Boolean) = UUID.randomUUID().toString()
    }

    private fun key(i: Improvement) =
        Key(plain(i.type), nameOf(i), i.value, i.rating, if (i.type == "MentorSpirit" || i.type == "AddContact") "" else i.uniqueName, i.minimum, i.maximum, i.augmented, i.augmentedMaximum, i.source, i.condition)

    private fun sourceOf(item: Element): String? = when (item.tagName) {
        "quality" -> "Quality"
        "armormod" -> "ArmorMod"
        "armor" -> "Armor"
        "cyberware" -> item.text("improvementsource")?.trim()?.ifEmpty { null } ?: "Cyberware"
        "gear" -> "Gear"
        "weapon" -> "Weapon"
        "accessory" -> "WeaponAccessory"
        else -> null
    }

    private fun items(root: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (c in e.children()) {
                if (c.tagName == "improvements") continue
                if (sourceOf(c) != null && c.child("bonus") != null && c.child("guid") != null) out += c
                walk(c)
            }
        }
        walk(root)
        return out
    }

    @Test
    fun storedBonusesReproduceStoredImprovements() {
        val files = (src.walkTopDown().filter { it.extension == "chum5" }.toList() + ref.listFiles { f -> f.extension == "chum5" }.orEmpty().toList())
            .filter { !it.name.startsWith("Davis Jones") }
        val pack = chummer.core.DataPack.load(File(src, "Chummer/data"))
        val translations: Map<String, String> = chummer.core.parseXml(File(src, "Chummer/lang/en-us.xml")).documentElement
            .child("strings")!!.children("string").associate { it.text("key")!!.trim() to it.text("text").orEmpty().trim() }
        var itemsChecked = 0
        var matched = 0
        val unhandled = HashMap<String, Int>()
        val mismatches = ArrayList<String>()
        val diffTypes = HashMap<String, Int>()
        for (f in files) {
            val c = CharacterIo.readFile(f)
            val oldVersion = c.appVersion.split('.').mapNotNull { it.toIntOrNull() }.let { v -> v.size >= 2 && (v[0] < 5 || (v[0] == 5 && v[1] < 226)) }
            val stored = Improvement.parseAll(c.root).groupBy { it.sourceName }
            for (item in items(c.root)) {
                // As in real use, prefer the game data's bonus node; the saved copy can lose attributes such as "unique".
                val fromData = item.text("sourceid")?.trim()?.let { pack[it] }?.element?.child("bonus")
                val bonus = fromData ?: item.child("bonus")!!
                if (bonus.children().isEmpty()) continue
                val guid = item.text("guid")!!.trim()
                val rating = item.text("rating")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                    ?: item.text("levels")?.trim()?.toIntOrNull() ?: 1
                val result = BonusHandlers.apply(bonus, BonusContext(sourceOf(item)!!, guid, rating, choice = item.text("extra")?.trim().orEmpty(), friendlyName = item.text("name")?.trim().orEmpty(), translate = translations::get, addQuality = { _, _ -> UUID.randomUUID().toString() }, objects = FakeObjects))
                itemsChecked++
                result.unhandled.forEach { unhandled.merge(it.lowercase(), 1, Int::plus) }
                val want = (stored[guid] ?: emptyList()).map(::key).groupingBy { it }.eachCount()
                // Chummer only creates DisableQuality improvements from 5.226 on; older files never have them.
                val got = result.improvements.filter { oldVersion.not() || it.type != "DisableQuality" }.map(::key).groupingBy { it }.eachCount()
                val ok = result.unhandled.isEmpty() && want == got
                if (ok) matched++
                else if (result.unhandled.isEmpty()) {
                    // Which improvement types disagree, and in which direction.
                    val missing = want.keys - got.keys
                    val extra = got.keys - want.keys
                    missing.forEach { diffTypes.merge("missing ${it.type}", 1, Int::plus) }
                    extra.forEach { diffTypes.merge("extra ${it.type}", 1, Int::plus) }
                    if (missing.any { it.type == "Attribute" || it.type.endsWith("InhalationResist") } || extra.any { it.type.endsWith("InhalationResist") })
                    mismatches += "${f.name}: ${item.tagName} ${item.text("name")?.trim()} rating=$rating\n      want=${want.keys}\n      got =${got.keys}"
                }
            }
        }
        // Coverage of the whole game data: how many bonus node types (and uses) have a handler.
        val used = HashMap<String, Int>()
        for ((_, e) in pack.byId) e.element.child("bonus")?.children()?.forEach { used.merge(it.tagName.lowercase(), 1, Int::plus) }
        val known = used.keys.filter { BonusHandlers.knows(it) }
        println("Data bonus node types handled: ${known.size} of ${used.size} (${known.sumOf { used.getValue(it) }} of ${used.values.sum()} uses)")
        val bad = itemsChecked - matched
        println("Bonus items: $itemsChecked checked, $matched matched, $bad not (${unhandled.values.sum()} unhandled nodes)")
        println("Unhandled node types (items): " + unhandled.entries.sortedByDescending { it.value }.take(70).joinToString { "${it.key}=${it.value}" })
        println("Mismatch by type: " + diffTypes.entries.sortedByDescending { it.value }.take(40).joinToString { "${it.key}=${it.value}" })
        mismatches.forEach { println("  MISMATCH $it") }
        assertTrue("regressed: $bad > $KNOWN_NOT_MATCHING", bad <= KNOWN_NOT_MATCHING)
        if (bad < KNOWN_NOT_MATCHING) println("Improved: lower KNOWN_NOT_MATCHING to $bad")
    }

    companion object {
        const val KNOWN_NOT_MATCHING = 118
    }
}
