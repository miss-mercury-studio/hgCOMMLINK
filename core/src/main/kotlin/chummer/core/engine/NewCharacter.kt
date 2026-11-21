package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.CharacterIo
import chummer.core.DataPack
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
    /**
     * Chummer's hash of the settings profile, as found in a character saved with the same profile. Chummer uses
     * it to notice settings that changed since saving; leave null when unknown (the field is then left empty).
     */
    val settingsHash: Int? = null,
    /** Names of the custom data folders the settings enable and that are present, in load order. */
    val customDataNames: List<String> = emptyList(),
)

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
        require(metatype.tagName == "metatype") { "Metatype variants are not supported yet" }
        fun meta(tag: String) = metatype.child(tag)?.textContent?.trim().orEmpty()

        set("settings", settings.id)
        if (request.settingsHash != null) set("settingshashcode", request.settingsHash.toString())
        else root.child("settingshashcode")?.textContent = ""
        set("name", request.name)
        set("alias", request.alias)
        set("metatype", meta("name"))
        set("metatypeid", request.metatypeId.lowercase())
        set("metatypebp", meta("karma").ifEmpty { "0" })
        set("metatypecategory", meta("category"))
        for (tag in listOf("walk", "walkalt")) set(tag, meta("walk"))
        for (tag in listOf("run", "runalt")) set(tag, meta("run"))
        for (tag in listOf("sprint", "sprintalt")) set(tag, meta("sprint"))

        root.child("sources")!!.let { e -> settings.books.forEach { e.appendChild(element("source", it)) } }
        root.child("customdatadirectorynames")!!.let { e -> request.customDataNames.forEach { e.appendChild(element("directoryname", it)) } }

        // Attribute rows, with the metatype's limits. A new character has every attribute at its minimum.
        val totals = HashMap<String, Int>()
        val rows = root.child("attributes")!!
        for (name in ATTRIBUTES) {
            val prefix = if (name == "MAGAdept") "mag" else name.lowercase()
            val min = meta("${prefix}min").toIntOrNull() ?: 0
            val max = meta("${prefix}max").toIntOrNull() ?: 0
            val aug = meta("${prefix}aug").toIntOrNull() ?: 0
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
        for (s in skills) {
            fun flag(tag: String) = if (s.child(tag)?.textContent?.trim().equals("True", true)) "True" else "False"
            skillList.appendChild(element("skill").also { e ->
                e.appendChild(element("guid", UUID.randomUUID().toString()))
                e.appendChild(element("suid", s.child("id")?.textContent?.trim()))
                e.appendChild(element("isknowledge", "False"))
                e.appendChild(element("skillcategory", s.child("category")?.textContent?.trim()))
                e.appendChild(element("requiresgroundmovement", flag("requiresgroundmovement")))
                e.appendChild(element("requiresswimmovement", flag("requiresswimmovement")))
                e.appendChild(element("requiresflymovement", flag("requiresflymovement")))
                e.appendChild(element("karma", "0"))
                e.appendChild(element("base", "0"))
                e.appendChild(element("notes"))
                e.appendChild(element("notesColor", NOTES_COLOR))
                e.appendChild(element("name", s.child("name")?.textContent?.trim()))
                e.appendChild(element("buywithkarma", "False"))
            })
        }
        val groupList = root.child("newskills")!!.child("groups")!!
        skills.mapNotNull { it.child("skillgroup")?.textContent?.trim()?.ifEmpty { null } }.distinct()
            .sortedBy { it.lowercase() }.forEach { g ->
                groupList.appendChild(element("group").also { e ->
                    e.appendChild(element("karma", "0"))
                    e.appendChild(element("base", "0"))
                    e.appendChild(element("isbroken", "False"))
                    e.appendChild(element("id", UUID.randomUUID().toString()))
                    e.appendChild(element("name", g))
                })
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
}
