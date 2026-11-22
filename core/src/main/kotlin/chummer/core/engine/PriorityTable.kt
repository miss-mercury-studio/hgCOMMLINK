package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/** What a talent priority offers (Magician, Adept, Technomancer...): its magic or resonance, free spells and skills. */
data class TalentOption(
    val value: String, val name: String, val magic: Int, val resonance: Int, val depth: Int, val spells: Int, val complexForms: Int,
    val skillQty: Int, val skillVal: Int, val skillType: String, val qualities: List<String>,
)

/** The free allotments one set of priority letters gives a character. */
data class PriorityAllotment(
    val attributePoints: Int, val specialPoints: Int, val skillPoints: Int, val skillGroupPoints: Int, val nuyen: Int,
    /** Karma the metatype choice costs. */
    val metatypeKarma: Int, val talent: TalentOption?,
)

/**
 * Chummer's priority tables (`priorities.xml`): the points each letter gives to attributes, skills and resources, the special
 * attribute points a metatype gets at a heritage letter, and what each talent letter offers. The Sum-to-Ten build uses the same
 * letters, each worth A=4 to E=0 of the ten points.
 */
class PriorityTable(private val data: DataPack) {
    private val entries: List<Element> by lazy {
        data.byId.values.filter { it.file == "priorities.xml" && it.element.tagName == "priority" }.map { it.element }
    }

    private fun entries(category: String, letter: String) =
        entries.filter { it.text("category")?.trim() == category && it.text("value")?.trim() == letter }

    private fun int(e: Element?, tag: String) = e?.text(tag)?.trim()?.toIntOrNull() ?: 0

    /** Attribute points for a letter. */
    fun attributes(letter: String) = int(entries("Attributes", letter).firstOrNull(), "attributes")

    fun skills(letter: String) = int(entries("Skills", letter).firstOrNull(), "skills")
    fun skillGroups(letter: String) = int(entries("Skills", letter).firstOrNull(), "skillgroups")

    /** Starting nuyen for a letter, from the priority table the settings choose ("Standard" by default). */
    fun resources(letter: String, table: String = "Standard"): Int {
        val rows = entries("Resources", letter)
        return int(rows.firstOrNull { it.text("prioritytable")?.trim() == table } ?: rows.firstOrNull(), "resources")
    }

    /** The metatype's row at a heritage letter: special attribute points and karma cost. Null when the letter does not offer it. */
    fun heritage(letter: String, metatype: String, metavariant: String = ""): Pair<Int, Int>? {
        for (p in entries("Heritage", letter)) {
            val m = p.child("metatypes")?.children("metatype")?.firstOrNull { it.text("name")?.trim() == metatype } ?: continue
            if (metavariant.isNotEmpty() && metavariant != "None") {
                val v = m.child("metavariants")?.children("metavariant")?.firstOrNull { it.text("name")?.trim() == metavariant } ?: continue
                return int(v, "value") to int(v, "karma")
            }
            return int(m, "value") to int(m, "karma")
        }
        return null
    }

    /** The talents a letter offers. */
    fun talents(letter: String): List<TalentOption> = entries("Talent", letter).flatMap { p ->
        p.child("talents")?.children("talent").orEmpty().map { t ->
            TalentOption(
                t.text("value")?.trim().orEmpty(), t.text("name")?.trim().orEmpty(), int(t, "magic"), int(t, "resonance"), int(t, "depth"), int(t, "spells"), int(t, "cfp"),
                int(t, "skillqty"), int(t, "skillval"), t.text("skilltype")?.trim().orEmpty(),
                t.child("qualities")?.children("quality").orEmpty().map { it.textContent.trim() },
            )
        }
    }

    /** Points a letter is worth in the Sum-to-Ten build (A 4, B 3, C 2, D 1, E 0). */
    fun sumToTenValue(letter: String): Int = mapOf("A" to 4, "B" to 3, "C" to 2, "D" to 1, "E" to 0)[letter] ?: 0

    /** The allotments for the priorities stored on a character (`prioritymetatype` and the others, written like `C,2`). */
    fun allotment(c: CharacterFile, table: String = "Standard"): PriorityAllotment? {
        fun letter(tag: String) = c.root.text(tag)?.trim()?.substringBefore(',')?.takeIf { it.isNotEmpty() }
        val heritage = letter("prioritymetatype") ?: return null
        val (special, karma) = heritage(heritage, c.root.text("metatype")?.trim().orEmpty(), c.root.text("metavariant")?.trim().orEmpty()) ?: return null
        val talentLetter = letter("priorityspecial")
        val talentValue = c.root.text("prioritytalent")?.trim().orEmpty()
        return PriorityAllotment(
            letter("priorityattributes")?.let(::attributes) ?: 0, special, letter("priorityskills")?.let(::skills) ?: 0, letter("priorityskills")?.let(::skillGroups) ?: 0,
            letter("priorityresources")?.let { resources(it, table) } ?: 0, karma, talentLetter?.let { l -> talents(l).firstOrNull { it.value == talentValue } },
        )
    }
}
