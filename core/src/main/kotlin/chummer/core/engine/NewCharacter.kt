package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.CharacterIo
import chummer.core.DataPack
import chummer.core.Expr
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import org.w3c.dom.Element
import java.util.UUID
import kotlin.math.ceil

/** What the user chooses when starting a new character. */
class NewCharacterRequest(
    val name: String = "",
    val alias: String = "",
    /** The id of a metatype in `metatypes.xml` (a base metatype; variants are not supported yet). */
    val metatypeId: String,
    /** The id of a metavariant of that metatype (for example Dryad), if any. */
    val metavariantId: String? = null,
    /**
     * Chummer's hash of the settings profile, as found in a character saved with the same profile. Chummer uses
     * it to notice settings that changed since saving; leave null when unknown (the field is then left empty).
     */
    val settingsHash: Int? = null,
    /** Names of the custom data folders the settings enable and that are present, in load order. */
    val customDataNames: List<String> = emptyList(),
    /** The Force of a critter (spirit, free spirit...) whose attributes are formulas in F; 0 for everything else. */
    val force: Int = 0,
)

/** The priority letters for a Priority or Sum-to-Ten character, and the talent chosen at the talent letter (for example `Magician`). */
class PriorityChoice(
    val heritage: String, val attributes: String, val talentLetter: String, val skills: String, val resources: String,
    /** The talent's `value` in `priorities.xml` (`Mundane`, `Magician`, `Adept`, `Technomancer`...). */
    val talent: String,
    val sumToTen: Boolean,
    /** The skills (or skill groups) picked for the talent's free ratings; see [PriorityTable.skillChoices]. */
    val talentSkills: List<String> = emptyList(),
) {
    val letters get() = listOf(heritage, attributes, talentLetter, skills, resources)

    /** What is wrong with these letters, or null: Sum-to-Ten letters must be worth 10 in all, Priority letters must be A to E once each. */
    fun problem(): String? {
        if (letters.any { it !in listOf("A", "B", "C", "D", "E") }) return "Each category needs a letter from A to E"
        return if (sumToTen) {
            val total = letters.sumOf { mapOf("A" to 4, "B" to 3, "C" to 2, "D" to 1, "E" to 0).getValue(it) }
            if (total == 10) null else "The letters are worth $total points; Sum-to-Ten needs exactly 10"
        } else if (letters.toSet().size == 5) null else "Each letter A to E can be used once"
    }
}

/**
 * Creates a new character file for the Karma build method. The skeleton is a character Chummer 5.226 saved
 * with nothing bought (`new-character-karma.chum5`, emptied of the parts below); everything that depends on the
 * metatype, the settings or the game data is generated: the attribute rows with the metatype's limits, the
 * active skills and skill groups, the book list, the custom data names and the calculated values.
 * Checked against Chummer's own file by NewCharacterTemplateTest.
 */
object NewCharacter {
    /** The order Chummer writes attributes in. */
    private val ATTRIBUTES = listOf("STR", "DEP", "MAGAdept", "CHA", "INT", "AGI", "REA", "RES", "ESS", "EDG", "MAG", "LOG", "BOD", "WIL")
    private val STANDARD = setOf("STR", "CHA", "INT", "AGI", "REA", "LOG", "BOD", "WIL")

    /** Skill categories that appear only for magic users and technomancers. */
    private val HIDDEN_SKILL_CATEGORIES = setOf("Magical Active", "Resonance Active")

    fun createKarma(data: DataPack, settings: CharacterSettings, request: NewCharacterRequest): CharacterFile {
        val skeleton = NewCharacter::class.java.getResourceAsStream("/chummer/new-character-karma.chum5")
            ?: error("The new character skeleton is missing")
        val character = skeleton.use { CharacterIo.read(it) }
        val doc = character.doc
        val root = character.root

        fun element(tag: String, text: String? = null): Element =
            doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

        fun set(tag: String, value: String) {
            val e = root.child(tag) ?: error("Skeleton has no <$tag>")
            e.textContent = value
        }

        val metatype = data[request.metatypeId]?.element ?: error("No metatype with id ${request.metatypeId}")
        require(metatype.tagName == "metatype") { "Pass the base metatype in metatypeId and a variant in metavariantId" }
        val variant = request.metavariantId?.let { data[it]?.element ?: error("No metavariant with id $it") }
        // A variant has its own limits, cost, bonus and qualities; the base metatype supplies the name and category.
        val source = variant ?: metatype
        fun meta(tag: String) = source.child(tag)?.textContent?.trim().orEmpty()
        // A critter's attributes are formulas in its Force (`F+3`, `F/2`, `(F*2)+4`); every other metatype has plain numbers.
        fun num(text: String): Int = text.toIntOrNull() ?: Expr.evalOrNull(text, mapOf("F" to request.force.toDouble(), "Force" to request.force.toDouble()))?.let { standardRound(it) } ?: 0

        set("settings", settings.id)
        if (request.settingsHash != null) set("settingshashcode", request.settingsHash.toString())
        else root.child("settingshashcode")?.textContent = ""
        set("name", request.name)
        set("alias", request.alias)
        set("metatype", metatype.child("name")?.textContent?.trim().orEmpty())
        set("metatypeid", request.metatypeId.lowercase())
        set("metavariant", variant?.child("name")?.textContent?.trim().orEmpty())
        set("metavariantid", request.metavariantId?.lowercase() ?: "00000000-0000-0000-0000-000000000000")
        set("metatypebp", meta("karma").ifEmpty { "0" })
        set("metatypecategory", metatype.child("category")?.textContent?.trim().orEmpty())
        // The walk, run and sprint fields keep the skeleton's defaults: Chummer does not store the metatype's own values there.

        root.child("sources")!!.let { e -> settings.books.forEach { e.appendChild(element("source", it)) } }
        root.child("customdatadirectorynames")!!.let { e -> request.customDataNames.forEach { e.appendChild(element("directoryname", it)) } }

        // Attribute rows, with the metatype's limits. A new character has every attribute at its minimum.
        val totals = HashMap<String, Int>()
        val rows = root.child("attributes")!!
        for (name in ATTRIBUTES) {
            val prefix = if (name == "MAGAdept") "mag" else name.lowercase()
            val min = num(meta("${prefix}min"))
            val max = num(meta("${prefix}max"))
            val aug = num(meta("${prefix}aug"))
            val total = if (max == 0) 0 else maxOf(min, 1)
            totals[name] = total
            rows.appendChild(element("attribute").also { a ->
                a.appendChild(element("name", name))
                a.appendChild(element("metatypemin", min.toString()))
                a.appendChild(element("metatypemax", max.toString()))
                a.appendChild(element("metatypeaugmax", aug.toString()))
                a.appendChild(element("base", "0"))
                a.appendChild(element("karma", "0"))
                a.appendChild(element("metatypecategory", if (name in STANDARD) "Standard" else "Special"))
                a.appendChild(element("totalvalue", total.toString()))
            })
        }

        // Active skills and the skill groups they belong to.
        val skills = data.byId.values
            .filter { it.file == "skills.xml" && it.element.tagName == "skill" && (it.element.parentNode as? Element)?.tagName == "skills" }
            .map { it.element }
            .filter { it.child("exotic")?.textContent?.trim()?.equals("True", true) != true }
            .filter { it.child("category")?.textContent?.trim() !in HIDDEN_SKILL_CATEGORIES }
            .sortedBy { it.child("name")?.textContent?.trim()?.lowercase().orEmpty() }
        val skillList = root.child("newskills")!!.child("skills")!!
        for (sk in skills) skillList.appendChild(SkillElements.skill(doc, sk))
        val groupList = root.child("newskills")!!.child("groups")!!
        skills.mapNotNull { it.child("skillgroup")?.textContent?.trim()?.ifEmpty { null } }.distinct()
            .sortedBy { it.lowercase() }.forEach { g -> groupList.appendChild(SkillElements.group(doc, g)) }

        // Base lift and carry limits come from the settings' expressions with the starting STR (defaults: STR x 15 and x 10).
        val str = totals.getValue("STR").toDouble()
        set("basecarrylimit", standardRound(settings.evaluate("carrylimitexpression", mapOf("STR" to str), "{STR} * 10")).toString())
        set("baseliftlimit", standardRound(settings.evaluate("liftlimitexpression", mapOf("STR" to str), "{STR} * 15")).toString())

        // Every new character gets its own ids for the built-in unarmed attack.
        root.child("weapons")?.children("weapon")?.forEach { w ->
            w.child("guid")?.textContent = UUID.randomUUID().toString()
            w.child("parentid")?.textContent = UUID.randomUUID().toString()
        }

        // What the metatype grants: its own bonus (armor, reach, the magic or critter switches, natural weapons, with the metatype as the source),
        // the powers it gives and free qualities (those marked removable can be bought off later, as Chummer keeps them apart).
        val editor = CharacterEditor(character, data)
        editor.grantMetatypeBonus(source.child("bonus"), (request.metavariantId ?: request.metatypeId).lowercase(), metatype.child("name")?.textContent?.trim().orEmpty())
        for (p in source.child("powers")?.children("power").orEmpty()) {
            val rating = p.getAttribute("rating").ifEmpty { null }?.let(::num) ?: 0
            editor.addMetatypePower(p.textContent.trim(), rating, p.getAttribute("select"))
        }
        for (group in listOf("positive", "negative")) {
            for (q in source.child("qualities")?.child(group)?.children("quality").orEmpty()) {
                val wanted = q.textContent.trim()
                val entry = data.byId.entries.firstOrNull {
                    it.value.file == "qualities.xml" && it.value.element.tagName == "quality" &&
                        it.value.element.child("name")?.textContent?.trim() == wanted
                } ?: error("Metatype quality $wanted is not in the game data")
                editor.addQuality(entry.key, qualitySource = if (q.getAttribute("removable").equals("True", true)) "MetatypeRemovable" else "Metatype")
            }
        }

        // A critter (a spirit, a free spirit, an infected...) from `critters.xml`: it is a finished character, the skills its entry lists are
        // free levels in its Force, and it needs no karma or nuyen.
        if (data[request.metatypeId]?.file == "critters.xml") {
            set("critter", "True"); set("created", "True"); set("karma", "0"); set("nuyen", "0")
            val improvements = root.child("improvements") ?: element("improvements").also { root.appendChild(it) }
            for (sk in source.child("skills")?.children("skill").orEmpty()) {
                val rating = sk.getAttribute("rating").ifEmpty { null }?.let(::num) ?: continue
                improvements.appendChild(ImprovementWriter.toElement(doc, Improvement(
                    "SkillLevel", "Metatype", sk.textContent.trim(), "", "", rating.toDouble(), 0.0, 0.0, 0.0, 0.0, 1.0, false, true, false, "",
                )))
            }
        }
        // Values Chummer stores for a character at rest.
        val bod = totals.getValue("BOD")
        val wil = totals.getValue("WIL")
        root.child("calculatedvalues")!!.let { cv ->
            cv.appendChild(element("physicalcm", (8 + ceil(bod / 2.0).toInt()).toString()))
            cv.appendChild(element("physicalcmthresholdoffset", "0"))
            cv.appendChild(element("physicalcmoverflow", (bod + 1).toString()))
            cv.appendChild(element("stuncm", (8 + ceil(wil / 2.0).toInt()).toString()))
            cv.appendChild(element("stuncmthresholdoffset", "0"))
        }
        return character
    }

    /**
     * Creates a character for the Priority or Sum-to-Ten build: a Karma-build character plus what the letters give. The build fields
     * (`buildmethod`, the priorities with their Sum-to-Ten values, the point totals Chummer keeps beside them, the nuyen from the
     * resources letter), the metatype's karma cost, the talent's quality and its Magic or Resonance as the attribute's minimum.
     * Not set up yet: the skills a talent gives for free, free spells, and the Magic or Resonance skill groups' effects.
     */
    fun createPriority(data: DataPack, settings: CharacterSettings, request: NewCharacterRequest, choice: PriorityChoice): CharacterFile {
        choice.problem()?.let { error(it) }
        val character = createKarma(data, settings, request)
        val root = character.root
        val table = PriorityTable(data)
        val doc = character.doc
        fun element(tag: String, text: String) = doc.createElement(tag).also { it.textContent = text }
        fun set(tag: String, value: String) {
            root.child(tag)?.textContent = value ?: return
            if (root.child(tag) == null) error("Skeleton has no <$tag>")
        }
        fun addAfter(after: String, tag: String, value: String) {
            root.child(tag)?.let { it.textContent = value; return }
            val anchor = root.child(after)
            val e = element(tag, value)
            if (anchor?.nextSibling != null) root.insertBefore(e, anchor.nextSibling) else root.appendChild(e)
        }

        set("buildmethod", if (choice.sumToTen) "SumtoTen" else "Priority")
        // Settings-driven limits Chummer stores in the file (in the order of its own files, after the build method).
        addAfter("buildmethod", "maxavail", settings.int("availability", 12).toString())
        addAfter("buildmethod", "nuyenmaxbp", settings.int("nuyenmaxbp", 10).toString())
        addAfter("buildmethod", "maxnuyen", settings.int("nuyenmaxbp", 10).toString())
        addAfter("buildmethod", "maxkarma", settings.int("buildkarma", 25).toString())
        addAfter("buildmethod", "buildkarma", settings.int("buildkarma", 25).toString())
        if (choice.sumToTen) addAfter("buildmethod", "sumtoten", "10")

        fun stored(letter: String) = letter + "," + table.sumToTenValue(letter)
        set("prioritymetatype", stored(choice.heritage)); set("priorityattributes", stored(choice.attributes))
        set("priorityspecial", stored(choice.talentLetter)); root.children("priorityskills").first().textContent = stored(choice.skills)
        set("priorityresources", stored(choice.resources)); set("prioritytalent", choice.talent)

        val metatype = root.child("metatype")!!.textContent.trim()
        val variant = root.child("metavariant")?.textContent?.trim().orEmpty()
        val (special, karma) = table.heritage(choice.heritage, metatype, variant)
            ?: error("$metatype is not offered at priority ${choice.heritage}")
        val talent = table.talents(choice.talentLetter).firstOrNull { it.value == choice.talent }
            ?: error("${choice.talent} is not offered at priority ${choice.talentLetter}")
        set("metatypebp", karma.toString())
        val specialTotal = special + talent.specialPoints
        set("special", specialTotal.toString()); set("totalspecial", specialTotal.toString())
        set("totalattributes", table.attributes(choice.attributes).toString())
        root.child("newskills")!!.child("skillptsmax")!!.textContent = table.skills(choice.skills).toString()
        root.child("newskills")!!.child("skillgrpsmax")!!.textContent = table.skillGroups(choice.skills).toString()
        set("startingnuyen", table.resources(choice.resources, settings.string("prioritytable", "Standard")).toString())
        // Contact points come from Charisma: the settings' expression (3 per point by default).
        val cha = root.child("attributes")!!.children("attribute").first { it.child("name")?.textContent?.trim() == "CHA" }.child("totalvalue")?.textContent?.trim()?.toDoubleOrNull() ?: 1.0
        set("contactpoints", standardRound(settings.evaluate("contactpointsexpression", mapOf("CHAUnaug" to cha, "CHA" to cha), "{CHAUnaug} * 3")).toString())

        // The talent: Magic or Resonance as the minimum of that attribute, and the talent's quality.
        fun raiseMinimum(name: String, minimum: Int) {
            if (minimum <= 0) return
            val row = root.child("attributes")!!.children("attribute").first { it.child("name")?.textContent?.trim() == name }
            row.child("metatypemin")!!.textContent = minimum.toString()
            row.child("totalvalue")!!.textContent = minimum.toString()
        }
        raiseMinimum("MAG", talent.magic); raiseMinimum("RES", talent.resonance); raiseMinimum("DEP", talent.depth)
        // Free spells and complex forms (Chummer keeps the spell count in `spelllimit`), and the free ratings in the chosen skills.
        set("spelllimit", talent.spells.toString()); set("cfplimit", talent.complexForms.toString())
        val choices = table.skillChoices(talent)
        require(choice.talentSkills.size <= talent.pickCount) { "${talent.value} gives ${talent.pickCount} free skills" }
        require(choice.talentSkills.distinct().size == choice.talentSkills.size && choice.talentSkills.all { it in choices }) { "A chosen skill is not offered for ${talent.value}" }
        val improvements = root.child("improvements")!!
        if (talent.pickValue != 0) for (name in choice.talentSkills) {
            improvements.appendChild(ImprovementWriter.toElement(doc, Improvement(
                if (talent.picksGroups) "SkillGroupBase" else "SkillBase", "Heritage", name, "", "", talent.pickValue.toDouble(), 0.0, 0.0, 0.0, 0.0, 1.0, false, true, false, "",
            )))
        }
        val editor = CharacterEditor(character, data, settings = settings)
        for (name in talent.qualities) {
            val id = data.byId.entries.firstOrNull { it.value.file == "qualities.xml" && it.value.element.tagName == "quality" && it.value.element.child("name")?.textContent?.trim() == name }?.key ?: continue
            editor.addQuality(id, qualitySource = "Metatype")
        }
        root.child("karma")!!.textContent = KarmaLedger.of(character, settings, data).remaining.toString()
        // The stored nuyen is what is left of the budget; without this it stayed 0 until the first edit (issue #2).
        NuyenLedger.sync(character, settings, data)
        return character
    }
}

/** A critter that can be created: its name, its category (Spirits, Paranormal Critters...), and whether its attributes depend on a Force. */
data class CritterOption(val id: String, val name: String, val category: String, val forceBased: Boolean)

object CritterCatalog {
    fun list(data: DataPack): List<CritterOption> = data.byId.entries.filter { it.value.file == "critters.xml" && it.value.element.tagName == "metatype" && it.value.element.child("hide") == null }.map { (id, e) ->
        val el = e.element
        val formulas = listOf("bodmin", "agimin", "strmin", "wilmin").mapNotNull { el.child(it)?.textContent?.trim() }
        CritterOption(id, el.child("name")?.textContent?.trim().orEmpty(), el.child("category")?.textContent?.trim().orEmpty(), formulas.any { it.toIntOrNull() == null })
    }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
}
