package chummer.core.data

import chummer.core.Expr
import chummer.core.child
import chummer.core.children
import chummer.core.engine.RuleSettings
import chummer.core.parseXml
import org.w3c.dom.Element
import java.io.File

/**
 * A character settings profile (house rules, karma costs, enabled books, custom data).
 * A character file stores only the profile's GUID, so these are loaded separately.
 */
class CharacterSettings(
    val id: String,
    val name: String,
    /** Every simple `<tag>value</tag>` setting, keyed by lower-case tag. */
    val values: Map<String, String>,
    val books: List<String>,
    val customData: List<CustomDataRef>,
    val bannedWareGrades: List<String>,
    val redlinerExclusion: List<String>,
    val karmaCost: Map<String, Int>,
    /** True when the character named a profile we could not find and this is the stand-in. */
    val isFallback: Boolean = false,
) {
    /** How characters made with this profile are built: Priority, SumtoTen, Karma or LifeModule. */
    val buildMethod: String get() = string("buildmethod")

    fun string(key: String, default: String = ""): String = values[key.lowercase()] ?: default
    fun int(key: String, default: Int = 0): Int = values[key.lowercase()]?.trim()?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean = false): Boolean =
        values[key.lowercase()]?.trim()?.let { it.equals("true", true) } ?: default

    /** Evaluate an expression setting, such as the lift limit `{STR} * 15`, with the given variables. */
    fun evaluate(key: String, vars: Map<String, Double>, default: String = ""): Double =
        Expr.eval(string(key).ifBlank { default }, vars)

    /** The settings the calculation engines read. */
    fun toRuleSettings(): RuleSettings {
        val base = RuleSettings()
        return base.copy(
            unclampAttributeMinimum = bool("unclampattributeminimum"),
            dontUseCyberlimbCalculation = bool("dontusecyberlimbcalculation"),
            limbCount = int("limbcount", base.limbCount),
            cyberlimbAttributeBonusCap = int("cyberlimbattributebonuscap", base.cyberlimbAttributeBonusCap),
            excludeLimbSlots = string("excludelimbslot").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            redlinerExcludes = redlinerExclusion.toSet().ifEmpty { base.redlinerExcludes },
            essenceDecimals = decimalsOf(string("essenceformat", "#,0.00")),
            dontRoundEssenceInternally = bool("donotroundessenceinternally"),
            maxSkillRating = int("maxskillrating", base.maxSkillRating),
            maxSkillRatingCreate = int("maxskillratingcreate", base.maxSkillRatingCreate),
            maxKnowledgeSkillRating = int("maxknowledgeskillrating", base.maxKnowledgeSkillRating),
            maxKnowledgeSkillRatingCreate = int("maxknowledgeskillratingcreate", base.maxKnowledgeSkillRatingCreate),
        )
    }

    companion object {
        /** Number of decimals in a .NET number format such as `#,0.00`. */
        fun decimalsOf(format: String): Int = format.substringAfter('.', "").count { it == '0' || it == '#' }

        fun parse(e: Element, isFallback: Boolean = false): CharacterSettings {
            val simple = HashMap<String, String>()
            for (c in e.children()) if (c.children().isEmpty()) simple[c.tagName.lowercase()] = c.textContent.trim()
            val custom = e.child("customdatadirectorynames")?.children("customdatadirectoryname").orEmpty().map {
                val raw = it.child("directoryname")?.textContent?.trim().orEmpty()
                CustomDataRef(
                    guid = raw.substringBefore('>'), version = raw.substringAfter('>', ""),
                    order = it.child("order")?.textContent?.trim()?.toIntOrNull() ?: 0,
                    enabled = it.child("enabled")?.textContent?.trim().equals("True", true),
                )
            }
            fun list(parent: String, item: String) =
                e.child(parent)?.children(item).orEmpty().map { it.textContent.trim() }
            val karma = e.child("karmacost")?.children().orEmpty()
                .mapNotNull { k -> k.textContent.trim().toIntOrNull()?.let { k.tagName.lowercase() to it } }.toMap()
            return CharacterSettings(
                id = simple["id"].orEmpty().lowercase(), name = simple["name"].orEmpty(), values = simple,
                books = list("books", "book"), customData = custom,
                bannedWareGrades = list("bannedwaregrades", "grade"), redlinerExclusion = list("redlinerexclusion", "limb"),
                karmaCost = karma, isFallback = isFallback,
            )
        }

        /** Every profile available: the standalone files in [userDirs] first, then Chummer's built-in ones. */
        fun available(builtInFile: File, userDirs: List<File> = emptyList()): List<CharacterSettings> {
            val out = ArrayList<CharacterSettings>()
            for (dir in userDirs) {
                for (f in dir.listFiles { x -> x.extension == "xml" }.orEmpty().sortedBy { it.name }) {
                    val root = try { parseXml(f).documentElement } catch (ex: Exception) { continue }
                    if (root.tagName == "settings" && root.child("id") != null) out += parse(root)
                }
            }
            parseXml(builtInFile).documentElement.child("settings")?.children("setting").orEmpty().forEach { out += parse(it) }
            return out
        }

        /**
         * Find the profile a character names. Looks in [userDirs] (standalone files with a `<settings>` root)
         * first, then in Chummer's built-in `data/settings.xml`. An unknown name, such as the old
         * `default.xml`, falls back to the first built-in profile.
         */
        fun find(settingsRef: String, builtInFile: File, userDirs: List<File> = emptyList()): CharacterSettings {
            val wanted = settingsRef.trim().lowercase()
            for (dir in userDirs) {
                for (f in dir.listFiles { x -> x.extension == "xml" }.orEmpty().sortedBy { it.name }) {
                    val root = try {
                        parseXml(f).documentElement
                    } catch (ex: Exception) {
                        continue
                    }
                    if (root.tagName == "settings" && root.child("id")?.textContent?.trim()?.lowercase() == wanted) return parse(root)
                }
            }
            val builtIn = parseXml(builtInFile).documentElement.child("settings")?.children("setting").orEmpty()
            builtIn.firstOrNull { it.child("id")?.textContent?.trim()?.lowercase() == wanted }?.let { return parse(it) }
            return parse(builtIn.first(), isFallback = true)
        }
    }
}
