package chummer.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

/** A loaded `.chum5` file. The DOM is kept so unknown content can be written back unchanged. */
class CharacterFile(val doc: Document) {
    val root: Element get() = doc.documentElement

    val name get() = root.text("name").orEmpty()
    val appVersion get() = root.text("appversion").orEmpty()
    val buildMethod get() = root.text("buildmethod").orEmpty()

    /** Attributes as stored: name -> (min, base, karma, stored total). */
    data class AttributeRow(val name: String, val min: Int, val base: Int, val karma: Int, val storedTotal: Int)

    fun attributes(): List<AttributeRow> =
        root.child("attributes")?.children("attribute").orEmpty().map {
            AttributeRow(it.text("name").orEmpty(), it.int("metatypemin"), it.int("base"), it.int("karma"), it.int("totalvalue"))
        }

    /** One applied improvement as stored by Chummer. */
    data class ImprovementRow(
        val type: String, val source: String, val name: String,
        val value: Int, val aug: Int, val rating: Int, val enabled: Boolean,
    )

    fun improvements(): List<ImprovementRow> =
        root.child("improvements")?.children("improvement").orEmpty().map {
            ImprovementRow(
                it.text("improvementttype").orEmpty(), it.text("improvementsource").orEmpty(),
                it.text("improvedname").orEmpty(), it.int("val"), it.int("aug"), it.int("rating"),
                it.text("enabled")?.trim().let { v -> v == "1" || v.equals("True", true) },
            )
        }

    /** An installed augmentation (cyberware or bioware), with its children. */
    data class Augmentation(
        val name: String, val sourceId: String, val grade: String, val rating: Int,
        val essFormula: String, val addToParent: Boolean, val children: List<Augmentation>,
        val category: String = "", val limbSlot: String = "", val limbSlotCount: Int = 1,
        val inheritAttributes: Boolean = false, val minAgility: Int = 3, val minStrength: Int = 3,
        val plugsIntoModularMount: String = "",
        val improvementSource: String = "Cyberware", val isGeneware: Boolean = false, val suite: Boolean = false,
        val essDiscount: Double = 0.0, val extraEssAdditive: Double = 0.0, val extraEssMultiplicative: Double = 1.0,
        val forceGrade: String = "", val minRating: Int = 0, val prototypeTranshuman: Boolean = false,
        val guid: String = "",
    ) {
        val isLimb get() = limbSlot.isNotEmpty()
    }

    private fun parseAug(e: Element): Augmentation = Augmentation(
        e.text("name").orEmpty(), e.text("sourceid").orEmpty(), e.text("grade").orEmpty(), e.int("rating"),
        e.text("ess").orEmpty(), e.text("addtoparentess")?.trim().equals("True", true),
        e.child("children")?.children("cyberware").orEmpty().map(::parseAug),
        category = e.text("category").orEmpty().trim(), limbSlot = e.text("limbslot").orEmpty().trim(),
        limbSlotCount = e.text("limbslotcount")?.trim()?.toIntOrNull() ?: 1,
        inheritAttributes = e.text("inheritattributes")?.trim().equals("True", true),
        minAgility = e.int("minagility", 3), minStrength = e.int("minstrength", 3),
        plugsIntoModularMount = e.text("plugsintomodularmount").orEmpty().trim(),
        improvementSource = e.text("improvementsource").orEmpty().trim().ifEmpty { "Cyberware" },
        isGeneware = e.text("isgeneware")?.trim().equals("True", true),
        suite = e.text("suite")?.trim().equals("True", true),
        essDiscount = e.text("essdiscount")?.trim()?.toDoubleOrNull() ?: 0.0,
        extraEssAdditive = e.text("extraessadditivemultiplier")?.trim()?.toDoubleOrNull() ?: 0.0,
        extraEssMultiplicative = e.text("extraessmultiplicativemultiplier")?.trim()?.toDoubleOrNull() ?: 1.0,
        forceGrade = e.text("forcegrade").orEmpty().trim(), minRating = e.int("minrating"),
        prototypeTranshuman = e.text("prototypetranshuman")?.trim().equals("True", true),
        guid = e.text("guid").orEmpty().trim(),
    )

    fun augmentations(): List<Augmentation> =
        root.child("cyberwares")?.children("cyberware").orEmpty().map(::parseAug)

    /** One armor mod. */
    data class ArmorMod(val name: String, val armor: Int, val equipped: Boolean, val encumbrance: Boolean, val guid: String)

    /** One piece of armor with its mods. `equipped` is the stored flag; the engine can be given another. */
    data class ArmorItem(
        val guid: String, val name: String, val armorValue: String, val armorOverride: String,
        val rating: Int, val equipped: Boolean, val encumbrance: Boolean,
        /** The equipment set (armor location) id, empty when none. */
        val location: String, val damage: Int, val mods: List<ArmorMod>,
    ) {
        /** Stacking accessories start with `+` (or `-`) and add to the best armor instead of replacing it. */
        val isStacking get() = armorValue.startsWith("+") || armorValue.startsWith("-")
    }

    private fun Element.flag(tag: String) = text(tag)?.trim().let { it == "1" || it.equals("True", true) }

    fun armors(): List<ArmorItem> =
        root.child("armors")?.children("armor").orEmpty().map { a ->
            ArmorItem(
                guid = a.text("guid").orEmpty().trim(), name = a.text("name").orEmpty().trim(),
                armorValue = a.text("armor").orEmpty().trim(), armorOverride = a.text("armoroverride").orEmpty().trim(),
                rating = a.int("rating"), equipped = a.flag("equipped"), encumbrance = a.flag("encumbrance"),
                location = a.text("location").orEmpty().trim(), damage = a.int("damage"),
                mods = a.child("armormods")?.children("armormod").orEmpty().map { m ->
                    ArmorMod(m.text("name").orEmpty().trim(), m.int("armor"), m.flag("equipped"), m.flag("encumbrance"), m.text("guid").orEmpty().trim())
                },
            )
        }

    /** Equipment set ids and names (Chummer calls them armor locations). */
    fun armorLocations(): Map<String, String> =
        root.child("armorlocations")?.children("location").orEmpty()
            .associate { it.text("guid").orEmpty().trim() to it.text("name").orEmpty().trim() }

    /** Sum of expense amounts by type ("Karma" or "Nuyen"), as recorded in the career log. */
    fun expenseTotal(type: String): Double =
        root.child("expenses")?.children("expense").orEmpty()
            .filter { it.text("type") == type }
            .sumOf { it.text("amount")?.trim()?.toDoubleOrNull() ?: 0.0 }

    /** What the character list needs, read from the top-level fields. */
    data class Summary(
        val name: String, val alias: String, val metatype: String, val buildMethod: String,
        val isCareer: Boolean, val karma: String, val nuyen: String, val appVersion: String, val edition: String,
    )

    fun summary() = Summary(
        name = name, alias = root.text("alias").orEmpty(), metatype = root.text("metatype").orEmpty(),
        buildMethod = buildMethod, isCareer = root.text("created")?.trim().equals("True", true),
        karma = root.text("karma")?.trim().orEmpty(),
        nuyen = root.text("nuyen")?.trim()?.toBigDecimalOrNull()?.toBigInteger()?.toString().orEmpty(),
        appVersion = appVersion, edition = root.text("gameedition").orEmpty(),
    )

    /** A skill as stored (active or knowledge). Totals are not stored; the engine computes them. */
    data class SkillRow(
        val name: String, val suid: String, val category: String, val base: Int, val karma: Int,
        val isKnowledge: Boolean, val type: String, val isNativeLanguage: Boolean,
        val specializations: List<String>, val guid: String = "", val buyWithKarma: Boolean = false,
        /** Specializations that are not free (those that cost a skill point or karma). */
        val paidSpecializations: Int = 0,
    )

    private fun parseSkill(e: Element) = SkillRow(
        guid = e.text("guid").orEmpty().trim(), name = e.text("name").orEmpty().trim(), suid = e.text("suid").orEmpty().trim(),
        category = e.text("skillcategory").orEmpty().trim(), base = e.int("base"), karma = e.int("karma"),
        isKnowledge = e.text("isknowledge")?.trim().equals("True", true), type = e.text("type").orEmpty().trim(),
        isNativeLanguage = e.text("isnativelanguage")?.trim().equals("True", true),
        specializations = e.child("specs")?.children("spec").orEmpty().mapNotNull { it.text("name")?.trim() },
        buyWithKarma = e.text("buywithkarma")?.trim().equals("True", true),
        paidSpecializations = e.child("specs")?.children("spec").orEmpty().count { it.text("free")?.trim() != "True" },
    )

    /** Active skills, then knowledge skills, in file order. */
    fun skills(): List<SkillRow> {
        val ns = root.child("newskills") ?: return emptyList()
        return (ns.child("skills")?.children("skill").orEmpty() + ns.child("knoskills")?.children("skill").orEmpty()).map(::parseSkill)
    }

    /** Skill groups as stored: name -> (base points, karma points). */
    fun skillGroups(): Map<String, Pair<Int, Int>> =
        root.child("newskills")?.child("groups")?.children("group").orEmpty()
            .associate { it.text("name").orEmpty().trim() to (it.int("base") to it.int("karma")) }

    /** Every non-empty `<sourceid>` in the file, with its parent's tag for reporting. */
    fun sourceIds(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val nodes = doc.getElementsByTagName("sourceid")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as Element
            val v = n.textContent.trim()
            if (v.isNotEmpty() && v != "00000000-0000-0000-0000-000000000000") {
                out.add((n.parentNode as Element).tagName to v)
            }
        }
        return out
    }

    companion object {
        fun load(file: File) = CharacterIo.readFile(file)
    }
}
