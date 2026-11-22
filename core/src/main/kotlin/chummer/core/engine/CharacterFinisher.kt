package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.w3c.dom.Element
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Turns a Karma-build character from creation into a career character, the way Chummer does when you choose "Create"
 * (`ValidateCharacter` and `SaveCharacterAsCreated`), matched against a finished file Chummer wrote
 * (`reference/New_Skills _Career.chum5`, see the golden test).
 *
 *  - Karma left is carried over up to the settings' carry-over; the rest is lost.
 *  - The nuyen is the build's nuyen (up to its carry-over) plus the starting nuyen, which is the user's dice roll for
 *    the lifestyle (for Street: 1D6 × 20 nuyen).
 *  - A character with no lifestyle gets a Street lifestyle with its free grid subscription.
 *  - Both amounts are recorded as expense entries that can be undone.
 */
object CharacterFinisher {
    /** What the finish will do, for showing before the user confirms. */
    data class Plan(val karmaKept: Int, val karmaLost: Int, val nuyenKept: Int, val nuyenLost: Int, val addsStreetLifestyle: Boolean)

    private fun nuyenCarryover(settings: CharacterSettings) = settings.values["nuyencarryover"]?.trim()?.toIntOrNull() ?: 200

    fun plan(c: CharacterFile, settings: CharacterSettings, data: DataPack): Plan {
        val left = maxOf(KarmaLedger.of(c, settings, data).remaining, 0)
        val keep = minOf(left, settings.karmaCost["karmacarryover"] ?: 7)
        val nuyen = c.root.text("nuyen")?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        val nuyenKeep = minOf(nuyen, nuyenCarryover(settings))
        val noLifestyle = c.root.child("lifestyles")?.children("lifestyle").orEmpty().isEmpty()
        return Plan(keep, left - keep, nuyenKeep, nuyen - nuyenKeep, noLifestyle)
    }

    /**
     * Finish the character in place. [startingNuyen] is the result of the lifestyle dice roll. Returns the review's
     * problems if the character cannot be finished yet (nothing is changed then).
     */
    fun finish(c: CharacterFile, settings: CharacterSettings, data: DataPack, startingNuyen: Int, now: LocalDateTime = LocalDateTime.now()): List<String> {
        val review = CreationReview.of(c, settings, data)
        if (!review.canFinish) return review.problems
        val plan = plan(c, settings, data)
        val doc = c.doc
        val root = c.root
        fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        fun container(tag: String) = root.child(tag) ?: el(tag).also { root.appendChild(it) }

        if (plan.addsStreetLifestyle) addStreetLifestyle(c, data, ::el, ::container)

        val nuyen = plan.nuyenKept + maxOf(startingNuyen, 0)
        root.child("created")?.textContent = "True"
        root.child("karma")?.textContent = plan.karmaKept.toString()
        root.child("nuyen")?.textContent = nuyen.toString()

        // The unarmed attack stops being a "free" creation item; karma-bought markers are no longer needed.
        for (w in root.child("weapons")?.children("weapon").orEmpty()) {
            if (w.text("name")?.trim() == "Unarmed Attack") w.child("included")?.textContent = "True"
        }
        removeBuyWithKarma(root.child("newskills"))

        val stamp = now.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val expenses = container("expenses")
        fun expense(amount: Int, reason: String, type: String, karmaType: String, nuyenType: String) {
            val e = el("expense")
            e.appendChild(el("guid", UUID.randomUUID().toString()))
            e.appendChild(el("date", stamp))
            e.appendChild(el("amount", amount.toString()))
            e.appendChild(el("reason", reason))
            e.appendChild(el("type", type))
            e.appendChild(el("refund", "False"))
            e.appendChild(el("forcecareervisible", "False"))
            val undo = el("undo")
            undo.appendChild(el("karmatype", karmaType))
            undo.appendChild(el("nuyentype", nuyenType))
            undo.appendChild(el("objectid"))
            undo.appendChild(el("qty", "0"))
            undo.appendChild(el("extra"))
            e.appendChild(undo)
            expenses.appendChild(e)
        }
        if (plan.karmaKept > 0) expense(plan.karmaKept, "Starting Karma", "Karma", "ManualAdd", "AddCyberware")
        if (nuyen > 0) expense(nuyen, "Starting Nuyen", "Nuyen", "ImproveAttribute", "ManualAdd")
        return emptyList()
    }

    private fun removeBuyWithKarma(node: Element?) {
        if (node == null) return
        for (child in node.children()) {
            if (child.tagName == "buywithkarma") node.removeChild(child) else removeBuyWithKarma(child)
        }
    }

    private fun addStreetLifestyle(c: CharacterFile, data: DataPack, el: (String, String?) -> Element, container: (String) -> Element) {
        val lifestyles = data.byId.values.filter { it.file == "lifestyles.xml" }
        val street = lifestyles.firstOrNull { it.element.tagName == "lifestyle" && it.element.text("name")?.trim() == "Street" }?.element ?: return
        fun v(tag: String) = street.text(tag)?.trim().orEmpty()
        val id = v("id").lowercase()
        val ls = el("lifestyle", null)
        fun add(tag: String, text: String? = null) { ls.appendChild(el(tag, text)) }
        add("sourceid", id); add("guid", UUID.randomUUID().toString()); add("name"); add("cost", v("cost")); add("dice", v("dice")); add("lp", v("lp"))
        add("baselifestyle", "Street"); add("multiplier", v("multiplier")); add("months", "1"); add("roommates", "0"); add("percentage", "100.0")
        for (t in listOf("area", "comforts", "security", "basearea", "basecomforts", "basesecurity")) add(t, "0")
        for (t in listOf("maxarea", "maxcomforts", "maxsecurity")) add(t, "1")
        add("costforearea", v("costforarea")); add("costforcomforts", v("costforcomforts")); add("costforsecurity", v("costforsecurity"))
        add("allowbonuslp", "False"); add("bonuslp", "0"); add("source", v("source")); add("page", v("page")); add("trustfund", "False")
        add("splitcostwithroommates", "False"); add("type", "Standard"); add("increment", "Month"); add("sourceid", id)
        add("city"); add("district"); add("borough")

        val qualities = el("lifestylequalities", null)
        val improvements = container("improvements")
        for (grid in street.child("freegrids")?.children("freegrid").orEmpty()) {
            val name = grid.textContent.trim()
            val select = grid.getAttribute("select")
            val q = lifestyles.firstOrNull { it.element.tagName == "quality" && it.element.text("name")?.trim() == name }?.element ?: continue
            fun qv(tag: String) = q.text(tag)?.trim().orEmpty()
            val guid = UUID.randomUUID().toString()
            val lq = el("lifestylequality", null)
            fun qadd(tag: String, text: String? = null) { lq.appendChild(el(tag, text)) }
            qadd("sourceid", qv("id").lowercase()); qadd("guid", guid); qadd("name", name); qadd("category", qv("category")); qadd("extra", select)
            qadd("cost", qv("cost")); qadd("multiplier", "0"); qadd("basemultiplier", "0"); qadd("lp", qv("lp"))
            for (t in listOf("areamaximum", "comfortsmaximum", "securitymaximum", "area", "comforts", "security")) qadd(t, "0")
            qadd("uselpcost", "True"); qadd("print", "True"); qadd("lifestylequalitytype", "Entertainment"); qadd("lifestylequalitysource", "BuiltIn")
            qadd("free", "True"); qadd("isfreegrid", "True"); qadd("source", qv("source")); qadd("page", qv("page")); qadd("allowed", qv("allowed"))
            qadd("bonus"); qadd("notes"); qadd("notesColor", NOTES_COLOR)
            qualities.appendChild(lq)
            // Choosing the grid records its name as a Text improvement.
            val imp = Improvement(
                type = "Text", source = "Quality", improvedName = select, sourceName = guid, uniqueName = "", value = 0.0, augmented = 0.0,
                minimum = 0.0, maximum = 0.0, augmentedMaximum = 0.0, rating = 1.0, addToRating = false, enabled = true, custom = false, condition = "",
            )
            improvements.appendChild(ImprovementWriter.toElement(c.doc, imp))
        }
        ls.appendChild(qualities)
        add("notes"); add("notesColor", NOTES_COLOR); add("sortorder", "0")
        container("lifestyles").appendChild(ls)
    }
}
