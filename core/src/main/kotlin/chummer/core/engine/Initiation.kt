package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** A metamagic (or, for a technomancer, an echo) for a picker. */
data class MetamagicOption(val id: String, val name: String, val source: String, val page: String, val unmet: String)

/** A metamagic or echo on the character, and the initiation grade it came with. */
data class MetamagicLine(val guid: String, val name: String, val grade: Int, val paid: Boolean)

/** What the Magic or Resonance tab shows about initiation (submersion for a technomancer). */
data class InitiationSection(
    val technomancer: Boolean, val grade: Int, val maxGrade: Int, val nextCost: Int, val metamagics: List<MetamagicLine>,
    /** The price of a metamagic beyond the first one of its grade. */
    val extraMetamagicCost: Int,
    /** Arts taken (free). */
    val arts: List<MetamagicLine> = emptyList(),
    val enhancements: List<MetamagicLine> = emptyList(), val gradeSpells: List<MetamagicLine> = emptyList(), val enhancementCost: Int = 2,
)

/**
 * Initiation and submersion, in the layout Chummer saves: an `initiationgrade` for each grade (`res` true for submersion) with its
 * group, ordeal and schooling flags, the character's `initiategrade` or `submersiongrade`, and the metamagics or echoes with the grade
 * they came with. A grade may not pass Magic (or Resonance); its karma is (flat + grade times the per-grade price), less the group,
 * ordeal and schooling discounts (Chummer's `InitiationGrade.KarmaCost`). The first metamagic of a grade is included in the grade;
 * each one after costs the metamagic price.
 */
class InitiationEditor(private val file: CharacterFile, private val data: GameData, private val settings: CharacterSettings) {
    private val root get() = file.root
    private fun flag(tag: String) = root.text(tag)?.trim().equals("True", true)

    /** True for a technomancer, who submerges instead of initiating. */
    val technomancer get() = !flag("magenabled") && flag("resenabled")
    val available get() = flag("magenabled") || flag("resenabled")

    private fun grades() = root.child("initiationgrades")?.children("initiationgrade").orEmpty()
        .filter { (it.text("res")?.trim().equals("True", true)) == technomancer }

    fun grade() = grades().maxOfOrNull { it.text("grade")?.trim()?.toIntOrNull() ?: 0 } ?: 0

    private fun attribute(abbrev: String) = AttributeEngine.forFile(file, settings.toRuleSettings())[abbrev]?.totalValue ?: 0

    /** The highest grade the character can have: not above Magic (Resonance). */
    fun maxGrade() = attribute(if (technomancer) "RES" else "MAG")


    fun nextCost(group: Boolean = false, ordeal: Boolean = false, schooling: Boolean = false) = gradeCost(grade() + 1, technomancer, group, ordeal, schooling, settings)

    /** Add the next grade. Null when the grade would pass Magic (Resonance). */
    fun initiate(group: Boolean = false, ordeal: Boolean = false, schooling: Boolean = false): String? {
        val next = grade() + 1
        if (!available || next > maxGrade()) return null
        val doc = file.doc
        fun el(tag: String, text: String? = null) = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        fun b(v: Boolean) = if (v) "True" else "False"
        val guid = UUID.randomUUID().toString()
        val g = el("initiationgrade")
        for ((tag, v) in listOf("guid" to guid, "res" to b(technomancer), "grade" to next.toString(), "group" to b(group), "ordeal" to b(ordeal), "schooling" to b(schooling), "notes" to ""))
            g.appendChild(el(tag, v))
        (root.child("initiationgrades") ?: el("initiationgrades").also { root.appendChild(it) }).appendChild(g)
        root.child(if (technomancer) "submersiongrade" else "initiategrade")?.textContent = next.toString()
        return guid
    }

    /** Take a grade back with the metamagics that came with it. Only the highest grade can go. */
    fun removeGrade(guid: String): Boolean {
        val g = root.child("initiationgrades")?.children("initiationgrade")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        val n = g.text("grade")?.trim()?.toIntOrNull() ?: return false
        val isRes = g.text("res")?.trim().equals("True", true)
        if (grades().any { (it.text("grade")?.trim()?.toIntOrNull() ?: 0) > n && (it.text("res")?.trim().equals("True", true)) == isRes }) return false
        for (m in metamagicElements().filter { it.text("grade")?.trim()?.toIntOrNull() == n }) removeMetamagic(m.text("guid").orEmpty())
        for (a in artElements().filter { it.text("grade")?.trim()?.toIntOrNull() == n }) removeArt(a.text("guid").orEmpty())
        for (e in enhancementElements().filter { it.text("grade")?.trim()?.toIntOrNull() == n }) removeEnhancement(e.text("guid").orEmpty())
        for (s in gradeSpellElements(n)) CharacterEditor(file, data, settings = settings).removeItem(s.text("guid").orEmpty())
        g.parentNode.removeChild(g)
        root.child(if (isRes) "submersiongrade" else "initiategrade")?.textContent = (n - 1).toString()
        return true
    }

    // ---- metamagics and echoes

    private val listTag get() = "metamagics"
    private fun metamagicElements() = root.child(listTag)?.children("metamagic").orEmpty()

    fun metamagics(): List<MetamagicLine> = metamagicElements().map {
        MetamagicLine(it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("grade")?.trim()?.toIntOrNull() ?: 0, it.text("paidwithkarma")?.trim().equals("True", true))
    }

    fun extraMetamagicCost() = settings.karmaCost["karmametamagic"] ?: 15

    /** True when a metamagic added to [grade] now costs karma: it is not the first of that grade. */
    fun costsKarma(grade: Int) = metamagicElements().any { it.text("grade")?.trim()?.toIntOrNull() == grade } || gradeSpellElements(grade).isNotEmpty()

    private fun sourceFile() = if (technomancer) "echoes.xml" else "metamagic.xml"
    private fun tag() = if (technomancer) "echo" else "metamagic"

    /** How many times a metamagic or echo can be taken: an echo's `limit` (some can be taken twice), otherwise once. */
    private fun limitOf(entry: Element) = if (technomancer) maxOf(entry.text("limit")?.trim()?.toIntOrNull() ?: 1, 1) else 1

    private fun taken(name: String) = metamagicElements().count { it.text("name")?.trim() == name }

    private fun known() = metamagicElements().map { it.text("name")?.trim().orEmpty() }.toSet()
    private fun hasArt(name: String) = root.child("arts")?.children("art").orEmpty().any { it.text("name")?.trim() == name }
    private fun hasQuality(name: String) = root.child("qualities")?.children("quality").orEmpty().any { it.text("name")?.trim() == name }

    /** Whether a `required` block is met: its `allof` and `oneof` lists of arts, qualities, metamagics, the initiate grade and the tradition. */
    private fun met(required: Element?): String {
        if (required == null) return ""
        fun one(e: Element): Boolean = when (e.tagName) {
            "art" -> hasArt(e.textContent.trim())
            "quality" -> hasQuality(e.textContent.trim())
            "power" -> hasPower(e.textContent.trim())
            "metamagic" -> e.textContent.trim() in known()
            "initiategrade" -> grade() >= (e.textContent.trim().toIntOrNull() ?: 0)
            "tradition" -> root.child("tradition")?.text("name")?.trim() == e.textContent.trim()
            else -> true
        }
        val all = required.child("allof")?.children().orEmpty().filter { it.tagName != "oneof" }
        val oneofs = required.children("oneof") + required.child("allof")?.children("oneof").orEmpty()
        val missing = all.filterNot(::one).map { it.textContent.trim() }
        if (missing.isNotEmpty()) return "needs " + missing.joinToString(", ")
        for (o in oneofs) if (o.children().isNotEmpty() && o.children().none(::one)) return "needs one of " + o.children().joinToString(", ") { it.textContent.trim() }
        return ""
    }

    /** The metamagics (echoes) on offer, each with what it still needs, if anything. Ones the character has are left out. */
    fun options(): List<MetamagicOption> {
        val pack = data as? DataPack ?: return emptyList()
        val magician = flag("magician"); val adept = flag("adept")
        return pack.byId.entries.filter { it.value.file == sourceFile() && it.value.element.tagName == tag() }.mapNotNull { (id, e) ->
            val el = e.element
            val name = el.text("name")?.trim().orEmpty()
            if (taken(name) >= limitOf(el)) return@mapNotNull null
            if (!technomancer && magician != adept) {
                // A pure magician sees the magician ones, a pure adept the adept ones.
                val wanted = if (magician) "magician" else "adept"
                if (el.text(wanted)?.trim().equals("False", true)) return@mapNotNull null
            }
            MetamagicOption(id, name, el.text("source")?.trim().orEmpty(), el.text("page")?.trim().orEmpty(), met(el.child("required")))
        }.sortedBy { it.name.lowercase() }
    }

    /** Add a metamagic (echo) for [grade]. Null when it is already known, its requirements are not met, or the grade does not exist. */
    fun addMetamagic(entryId: String, grade: Int = grade()): String? {
        val entry = data[entryId]?.element ?: return null
        if (entry.tagName != tag() || grade < 1 || grade > grade()) return null
        val name = entry.child("name")?.textContent?.trim().orEmpty()
        if (taken(name) >= limitOf(entry) || met(entry.child("required")).isNotEmpty()) return null
        val paid = costsKarma(grade)
        val doc = file.doc
        fun el(t: String, text: String? = null) = doc.createElement(t).also { if (!text.isNullOrEmpty()) it.textContent = text }
        val guid = UUID.randomUUID().toString()
        val m = el("metamagic")
        val source = if (technomancer) "Echo" else "Metamagic"
        for ((t, v) in listOf(
            "guid" to guid, "name" to name, "source" to entry.child("source")?.textContent?.trim().orEmpty(), "paidwithkarma" to if (paid) "True" else "False",
            "page" to entry.child("page")?.textContent?.trim().orEmpty(), "grade" to grade.toString(),
        )) m.appendChild(el(t, v))
        m.appendChild(el("bonus"))
        m.appendChild(el("improvementsource", source)); m.appendChild(el("notes"))
        (root.child(listTag) ?: el(listTag).also { root.appendChild(it) }).appendChild(m)
        entry.child("bonus")?.takeIf { it.children().isNotEmpty() }?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext(source, guid, 1, "", name, objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    fun removeMetamagic(guid: String): Boolean {
        val m = metamagicElements().firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        root.child("improvements")?.children("improvement")?.filter { it.text("sourcename")?.trim().equals(guid, true) }?.forEach { it.parentNode.removeChild(it) }
        m.parentNode.removeChild(m)
        return true
    }

    companion object {
        /** Chummer's `InitiationGrade.KarmaCost` for grade [n]. */
        fun gradeCost(n: Int, technomancer: Boolean, group: Boolean, ordeal: Boolean, schooling: Boolean, settings: CharacterSettings): Int {
            val flat = settings.karmaCost["karmainitiationflat"] ?: 10
            val per = settings.karmaCost["karmainitiation"] ?: 3
            val prefix = if (technomancer) "karmares" else "karmamag"
            fun percent(key: String, default: Double) = settings.string(key).toDoubleOrNull() ?: default
            var multiplier = 1.0
            if (group) multiplier -= percent("${prefix}initiationgrouppercent", 0.1)
            if (ordeal) multiplier -= percent("${prefix}initiationordealpercent", if (technomancer) 0.2 else 0.1)
            if (schooling) multiplier -= percent("${prefix}initiationschoolingpercent", 0.1)
            return standardRound((flat + n * per) * multiplier)
        }
    }

    // ---- arts (Street Grimoire): free, any number, each with the grade it was taken at

    private fun artElements() = root.child("arts")?.children("art").orEmpty()

    fun arts(): List<MetamagicLine> = artElements().map {
        MetamagicLine(it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("grade")?.trim()?.toIntOrNull() ?: 0, false)
    }

    /** The arts on offer (the ones the character has are left out), with what each still needs. */
    fun artOptions(): List<MetamagicOption> {
        val pack = data as? DataPack ?: return emptyList()
        val have = artElements().map { it.text("name")?.trim().orEmpty() }.toSet()
        return pack.byId.entries.filter { it.value.file == "metamagic.xml" && it.value.element.tagName == "art" }.mapNotNull { (id, e) ->
            val name = e.element.text("name")?.trim().orEmpty()
            if (name in have) null else MetamagicOption(id, name, e.element.text("source")?.trim().orEmpty(), e.element.text("page")?.trim().orEmpty(), met(e.element.child("required")))
        }.sortedBy { it.name.lowercase() }
    }

    /** Add an art for [grade] (the latest by default). Arts cost nothing (Chummer's karma branch for them is commented out). */
    fun addArt(entryId: String, grade: Int = grade()): String? {
        val entry = data[entryId]?.element ?: return null
        if (entry.tagName != "art" || grade < 1 || grade > grade()) return null
        val name = entry.child("name")?.textContent?.trim().orEmpty()
        if (artElements().any { it.text("name")?.trim() == name } || met(entry.child("required")).isNotEmpty()) return null
        val doc = file.doc
        fun el(t: String, text: String? = null) = doc.createElement(t).also { if (!text.isNullOrEmpty()) it.textContent = text }
        val guid = UUID.randomUUID().toString()
        val a = el("art")
        for ((t, v) in listOf("guid" to guid, "name" to name, "source" to entry.child("source")?.textContent?.trim().orEmpty(), "page" to entry.child("page")?.textContent?.trim().orEmpty(), "grade" to grade.toString()))
            a.appendChild(el(t, v))
        a.appendChild(el("bonus")); a.appendChild(el("improvementsource", "Metamagic")); a.appendChild(el("notes"))
        (root.child("arts") ?: el("arts").also { root.appendChild(it) }).appendChild(a)
        entry.child("bonus")?.takeIf { it.children().isNotEmpty() }?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext("Metamagic", guid, 1, "", name, objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    fun removeArt(guid: String): Boolean {
        val a = artElements().firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        root.child("improvements")?.children("improvement")?.filter { it.text("sourcename")?.trim().equals(guid, true) }?.forEach { it.parentNode.removeChild(it) }
        a.parentNode.removeChild(a)
        return true
    }

    // ---- enhancements (Street Grimoire power enhancements): kept with the adept power they belong to, or on the character when there is none

    private fun enhancementElements(): List<Element> =
        root.child("enhancements")?.children("enhancement").orEmpty() +
            root.child("powers")?.children("power").orEmpty().flatMap { it.child("enhancements")?.children("enhancement").orEmpty() }

    fun enhancements(): List<MetamagicLine> = enhancementElements().map {
        MetamagicLine(it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("grade")?.trim()?.toIntOrNull() ?: 0, false)
    }

    fun enhancementPrice() = settings.karmaCost["karmaenhancement"] ?: 2

    private fun hasPower(name: String) = root.child("powers")?.children("power").orEmpty().any { it.text("name")?.trim() == name }

    /** The enhancements on offer; their `required` blocks may also ask for a power. */
    fun enhancementOptions(): List<MetamagicOption> {
        val pack = data as? DataPack ?: return emptyList()
        val have = enhancementElements().map { it.text("name")?.trim().orEmpty() }.toSet()
        return pack.byId.entries.filter { it.value.file == "powers.xml" && it.value.element.tagName == "enhancement" }.mapNotNull { (id, e) ->
            val name = e.element.text("name")?.trim().orEmpty()
            if (name in have) null else MetamagicOption(id, name, e.element.text("source")?.trim().orEmpty(), e.element.text("page")?.trim().orEmpty(), met(e.element.child("required")))
        }.sortedBy { it.name.lowercase() }
    }

    fun addEnhancement(entryId: String, grade: Int = grade()): String? {
        val entry = data[entryId]?.element ?: return null
        if (entry.tagName != "enhancement" || grade < 1 || grade > grade()) return null
        val name = entry.child("name")?.textContent?.trim().orEmpty()
        if (enhancementElements().any { it.text("name")?.trim() == name } || met(entry.child("required")).isNotEmpty()) return null
        val doc = file.doc
        fun el(t: String, text: String? = null) = doc.createElement(t).also { if (!text.isNullOrEmpty()) it.textContent = text }
        val guid = UUID.randomUUID().toString()
        val e = el("enhancement")
        for ((t, v) in listOf("sourceid" to entryId.lowercase(), "guid" to guid, "name" to name, "source" to entry.child("source")?.textContent?.trim().orEmpty(), "page" to entry.child("page")?.textContent?.trim().orEmpty(), "grade" to grade.toString()))
            e.appendChild(el(t, v))
        e.appendChild(el("bonus")); e.appendChild(el("improvementsource", "Initiation")); e.appendChild(el("notes")); e.appendChild(el("notesColor", "Black"))
        val powerName = entry.child("power")?.textContent?.trim().orEmpty()
        val power = root.child("powers")?.children("power").orEmpty().firstOrNull { it.text("name")?.trim() == powerName && powerName.isNotEmpty() }
        val list = if (power != null) (power.child("enhancements") ?: el("enhancements").also { power.appendChild(it) })
        else (root.child("enhancements") ?: el("enhancements").also { root.appendChild(it) })
        list.appendChild(e)
        entry.child("bonus")?.takeIf { it.children().isNotEmpty() }?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext("Initiation", guid, 1, "", name, objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    fun removeEnhancement(guid: String): Boolean {
        val e = enhancementElements().firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        root.child("improvements")?.children("improvement")?.filter { it.text("sourcename")?.trim().equals(guid, true) }?.forEach { it.parentNode.removeChild(it) }
        e.parentNode.removeChild(e)
        return true
    }

    // ---- enchantments and rituals taken at a grade: ordinary spells that remember the grade

    private fun gradeSpellElements(n: Int? = null) = root.child("spells")?.children("spell").orEmpty().filter {
        val g = it.text("grade")?.trim()?.toIntOrNull() ?: 0
        if (n == null) g > 0 else g == n
    }

    fun gradeSpells(): List<MetamagicLine> = gradeSpellElements().map {
        MetamagicLine(it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("grade")?.trim()?.toIntOrNull() ?: 0, false)
    }

    /** Spells of [category] ("Enchantments" or "Rituals") the character does not know yet. */
    fun gradeSpellOptions(category: String): List<MetamagicOption> {
        val pack = data as? DataPack ?: return emptyList()
        val have = root.child("spells")?.children("spell").orEmpty().map { it.text("name")?.trim().orEmpty() }.toSet()
        return pack.byId.entries.filter { it.value.file == "spells.xml" && it.value.element.tagName == "spell" && it.value.element.text("category")?.trim() == category }.mapNotNull { (id, e) ->
            val name = e.element.text("name")?.trim().orEmpty()
            if (name in have) null else MetamagicOption(id, name, e.element.text("source")?.trim().orEmpty(), e.element.text("page")?.trim().orEmpty(), "")
        }.sortedBy { it.name.lowercase() }
    }

    /** Add the spell and mark it with [grade]. */
    fun addGradeSpell(entryId: String, grade: Int = grade()): String? {
        if (grade < 1 || grade > grade()) return null
        val entry = data[entryId]?.element ?: return null
        if (entry.tagName != "spell") return null
        val guid = CharacterEditor(file, data, settings = settings).addSpell(entryId)
        root.child("spells")?.children("spell")?.firstOrNull { it.text("guid")?.trim() == guid }?.child("grade")?.textContent = grade.toString()
        return guid
    }

    fun section() = InitiationSection(technomancer, grade(), maxGrade(), nextCost(), metamagics(), extraMetamagicCost(), arts(), enhancements(), gradeSpells(), enhancementPrice())
}
