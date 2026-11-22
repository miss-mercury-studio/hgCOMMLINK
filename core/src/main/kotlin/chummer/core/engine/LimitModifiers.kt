package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/** One condition-bound limit modifier: what gives it, how much, and when it applies. It does not change the limit itself. */
data class LimitLine(val limit: String, val name: String, val bonus: Int, val condition: String) {
    /** Chummer's full name, e.g. `Medkit [+3] (Only for First Aid and Medicine)`. */
    val fullName get() = name + " [" + (if (bonus >= 0) "+" else "") + bonus + "]" + if (condition.isNotEmpty()) " ($condition)" else ""
}

/**
 * The modifiers to the Physical, Mental and Social limits that only apply under a condition (a medkit for First Aid, a mask
 * for Intimidation). Chummer lists them beside the limit and leaves them out of the total. They come from the character's own
 * `limitmodifiers` list and from `LimitModifier` improvements aimed at a limit by name.
 */
object LimitModifiers {
    private val conditions: Map<String, String> by lazy {
        LimitModifiers::class.java.getResourceAsStream("/chummer/core/limitconditions.tsv")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }.orEmpty()
    }

    /** The condition text for a language key such as `LimitCondition_Visible`; text that is not a key is returned as it is. */
    fun conditionText(key: String): String = conditions[key] ?: key

    fun of(c: CharacterFile): List<LimitLine> {
        val lines = ArrayList<LimitLine>()
        for (m in c.root.child("limitmodifiers")?.children("limitmodifier").orEmpty()) {
            lines += LimitLine(m.text("limit")?.trim().orEmpty(), m.text("name")?.trim().orEmpty(), m.text("bonus")?.trim()?.toIntOrNull() ?: 0, conditionText(m.text("condition")?.trim().orEmpty()))
        }
        // Improvements aimed at a limit by name (the ones aimed at a modifier's GUID are the modifiers above).
        val names = namesByGuid(c.root)
        for (i in Improvement.parseAll(c.root)) {
            if (i.type != "LimitModifier" || !i.enabled || i.improvedName !in LIMITS) continue
            val name = names[i.sourceName.lowercase()] ?: i.uniqueName.ifEmpty { i.sourceName }
            lines += LimitLine(i.improvedName, name, i.value.toInt(), conditionText(i.condition))
        }
        return lines
    }

    private val LIMITS = setOf("Physical", "Mental", "Social")

    /** Names of the character's items by their GUID, for naming the source of an improvement. */
    private fun namesByGuid(root: Element): Map<String, String> {
        val map = HashMap<String, String>()
        fun walk(e: Element) {
            val guid = e.text("guid")?.trim()
            val name = e.text("name")?.trim()
            if (!guid.isNullOrEmpty() && !name.isNullOrEmpty()) map.putIfAbsent(guid.lowercase(), name)
            for (child in e.children()) if (child.tagName != "improvements") walk(child)
        }
        walk(root)
        return map
    }
}
