package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** A base lifestyle for a picker: what it costs for one month before any adjustment. */
data class LifestyleOption(val id: String, val name: String, val monthly: Double, val source: String, val page: String)

object LifestyleCatalog {
    /** The monthly base lifestyles the data offers (hidden placeholders and day-by-day hospital stays are left out). */
    fun options(data: DataPack): List<LifestyleOption> =
        data.byId.entries.filter { it.value.file == "lifestyles.xml" && it.value.element.tagName == "lifestyle" }.mapNotNull { (id, e) ->
            val el = e.element
            if (el.child("hide") != null || !el.text("increment")?.trim().isNullOrEmpty()) return@mapNotNull null
            LifestyleOption(id, el.text("name")?.trim().orEmpty(), el.text("cost")?.trim()?.toDoubleOrNull() ?: 0.0, el.text("source")?.trim().orEmpty(), el.text("page")?.trim().orEmpty())
        }.sortedWith(compareBy({ it.monthly }, { it.name.lowercase() }))
}

/** Lifestyles on a character, in the layout Chummer writes (the same fields the finisher gives the Street lifestyle). */
class LifestyleEditor(private val file: CharacterFile, private val data: DataPack) {
    private val doc get() = file.doc
    private val root get() = file.root
    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    fun lifestyle(guid: String) = root.child("lifestyles")?.children("lifestyle")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** Add a lifestyle paid for [months] months; returns its GUID. */
    fun add(entryId: String, months: Int): String {
        val entry = data[entryId]?.element ?: error("No lifestyle with id $entryId")
        require(entry.tagName == "lifestyle") { "Not a lifestyle" }
        require(months >= 1) { "At least one month" }
        fun v(tag: String) = entry.text(tag)?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val ls = el("lifestyle")
        fun add(tag: String, text: String? = null) { ls.appendChild(el(tag, text)) }
        add("sourceid", v("id").lowercase()); add("guid", guid); add("name"); add("cost", v("cost")); add("dice", v("dice")); add("lp", v("lp"))
        add("baselifestyle", v("name")); add("multiplier", v("multiplier")); add("months", months.toString()); add("roommates", "0"); add("percentage", "100.0")
        for (t in listOf("area", "comforts", "security", "basearea", "basecomforts", "basesecurity")) add(t, "0")
        for (t in listOf("maxarea", "maxcomforts", "maxsecurity")) add(t, "1")
        add("costforearea", v("costforarea")); add("costforcomforts", v("costforcomforts")); add("costforsecurity", v("costforsecurity"))
        add("allowbonuslp", "False"); add("bonuslp", "0"); add("source", v("source")); add("page", v("page")); add("trustfund", "False")
        add("splitcostwithroommates", "False"); add("type", "Standard"); add("increment", "Month")
        add("city"); add("district"); add("borough")
        ls.appendChild(el("lifestylequalities"))
        add("notes"); add("sortorder", "0")
        (root.child("lifestyles") ?: el("lifestyles").also { root.appendChild(it) }).appendChild(ls)
        return guid
    }

    /** What a lifestyle costs in all, as the nuyen ledger prices it. */
    fun total(guid: String): Double {
        val l = lifestyle(guid) ?: return 0.0
        val all = root.child("lifestyles")?.children("lifestyle").orEmpty()
        val dependents = root.child("qualities")?.children("quality").orEmpty().filter { it.text("name")?.contains("Dependent") == true }.mapNotNull { it.text("guid")?.trim()?.lowercase() }.toSet()
        return NuyenLedger.lifestyleTotal(l, Improvement.parseAll(root), dependents, all)
    }

    fun remove(guid: String): Boolean {
        val l = lifestyle(guid) ?: return false
        l.parentNode.removeChild(l)
        return true
    }
}
