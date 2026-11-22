package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** A critter power for a picker. [maxRating] is 0 for a power without a rating. */
data class CritterPowerOption(
    val id: String, val name: String, val category: String, val type: String, val action: String, val range: String, val duration: String,
    val karma: Int, val maxRating: Int, val source: String, val page: String, val unsupported: List<String>,
)

/** A critter power on the character. */
data class CritterPowerLine(val guid: String, val name: String, val extra: String, val rating: Int, val category: String, val karma: Int)

/**
 * Critter powers (shapeshifters, free spirits, infected, drakes...), in the layout Chummer saves them, with the improvements of their bonus
 * kept under the `CritterPower` source and the power's GUID. Each costs its `karma` (Chummer's ledger sums them; in career the price is paid
 * when the power is bought). Powers the metatype gives are free, do not count towards the limit and cannot be removed here.
 */
class CritterPowerEditor(private val file: CharacterFile, private val data: GameData) {
    private val root get() = file.root

    private fun rows() = root.child("critterpowers")?.children("critterpower").orEmpty()

    fun powers(): List<CritterPowerLine> = rows().map {
        CritterPowerLine(
            it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("extra").orEmpty().trim(), it.text("rating")?.trim()?.toIntOrNull() ?: 0,
            it.text("category").orEmpty().trim(), it.text("karma")?.trim()?.toIntOrNull() ?: 0,
        )
    }

    fun options(): List<CritterPowerOption> {
        val pack = data as? DataPack ?: return emptyList()
        val have = rows().map { it.text("name")?.trim().orEmpty() }.toSet()
        return pack.byId.entries.filter { it.value.file == "critterpowers.xml" && it.value.element.tagName == "power" && it.value.element.child("hide") == null }.mapNotNull { (id, e) ->
            val el = e.element
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val name = t("name")
            if (name.isEmpty() || name in have) return@mapNotNull null
            val rating = t("rating")
            CritterPowerOption(
                id, name, t("category"), t("type"), t("action"), t("range"), t("duration"), t("karma").toIntOrNull() ?: 0,
                if (rating.equals("True", true)) 12 else rating.toIntOrNull() ?: 0, t("source"), t("page"),
                el.child("bonus")?.let { BonusHandlers.apply(it, BonusContext("CritterPower", "", 1)).unhandled.distinct() }.orEmpty(),
            )
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
    }

    /** Add a power; its karma price is for the caller to pay. Null for an unknown or already known power, or a rating out of range. */
    fun add(entryId: String, rating: Int = 0, extra: String = "", given: Boolean = false): String? {
        val entry = data[entryId]?.element ?: return null
        if (entry.tagName != "power") return null
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val name = d("name")
        if (rows().any { it.text("name")?.trim() == name }) return null
        val ratingText = d("rating")
        val max = if (ratingText.equals("True", true)) 12 else ratingText.toIntOrNull() ?: 0
        if (!given && (rating < 0 || (max == 0 && rating != 0) || rating > max)) return null
        if (!given && ratingText.equals("True", true) && rating < 1) return null
        val doc = file.doc
        fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        val guid = UUID.randomUUID().toString()
        val p = el("critterpower")
        val shownExtra = extra.ifEmpty { if (rating != 0) rating.toString() else "" }
        for ((tag, v) in listOf(
            "guid" to guid, "name" to name, "extra" to shownExtra, "rating" to rating.toString(), "category" to d("category"), "type" to d("type"), "action" to d("action"),
            "range" to d("range"), "duration" to d("duration"), "grade" to "0", "source" to d("source"), "page" to d("page"), "karma" to (d("karma").toIntOrNull() ?: 0).toString(),
            "points" to "0", "counttowardslimit" to if (given) "False" else "True",
        )) p.appendChild(el(tag, v))
        p.appendChild(el("bonus")); p.appendChild(el("notes"))
        (root.child("critterpowers") ?: el("critterpowers").also { root.appendChild(it) }).appendChild(p)
        entry.child("bonus")?.takeIf { it.children().isNotEmpty() }?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext("CritterPower", guid, maxOf(rating, 1), extra, name, objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    /** Take a power out with its improvements. Powers the metatype gave (grade other than 0) stay. */
    fun remove(guid: String): Boolean {
        val p = rows().firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        // A power the metatype gave does not count towards the limit, and stays.
        if (p.text("counttowardslimit")?.trim().equals("False", true)) return false
        root.child("improvements")?.children("improvement")?.filter { it.text("sourcename")?.trim().equals(guid, true) }?.forEach { it.parentNode.removeChild(it) }
        p.parentNode.removeChild(p)
        return true
    }
}
