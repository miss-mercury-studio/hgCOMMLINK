package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/** One set of choices a mentor spirit offers (set 1 and set 2 are picked separately), and which one is taken. */
data class MentorChoiceSet(val set: Int, val options: List<String>, val chosen: String)

/** A mentor spirit on the character with its advantage and disadvantage text and the choices it offers. */
data class MentorLine(val guid: String, val name: String, val advantage: String, val disadvantage: String, val sets: List<MentorChoiceSet>)

/**
 * Mentor spirits and paragons: the advantage choices. Chummer keeps, in the `mentorspirit` element, the mentor's own `bonus`, and the
 * bonus of the choice picked from each set as `choice1` and `choice2`; every improvement they make has the source `MentorSpirit` and the
 * spirit's GUID as source name. Changing a choice rebuilds all of the spirit's improvements from the stored nodes.
 */
class MentorEditor(private val file: CharacterFile, private val data: GameData) {
    private val root get() = file.root
    private fun el(tag: String): Element = file.doc.createElement(tag)

    private fun spirits() = root.child("mentorspirits")?.children("mentorspirit").orEmpty()
    private fun find(guid: String) = spirits().firstOrNull { it.text("guid")?.trim().equals(guid, true) }
    private fun entryOf(m: Element) = m.text("id")?.trim()?.let { data[it]?.element }

    private fun setOf(choice: Element) = choice.getAttribute("set").trim().toIntOrNull() ?: 1

    fun mentors(): List<MentorLine> = spirits().map { m ->
        val entry = entryOf(m)
        val choices = entry?.child("choices")?.children("choice").orEmpty()
        val sets = choices.map(::setOf).distinct().sorted().map { s ->
            val options = choices.filter { setOf(it) == s }.map { it.text("name")?.trim().orEmpty() }
            val chosen = m.child("choice$s")
            // The saved node holds the choice's bonus, so the chosen one is the option whose bonus looks the same.
            val pick = choices.firstOrNull { setOf(it) == s && chosen != null && sameBonus(it.child("bonus"), chosen) }?.text("name")?.trim().orEmpty()
            MentorChoiceSet(s, options, pick)
        }
        MentorLine(m.text("guid").orEmpty().trim(), m.text("name").orEmpty().trim(), m.text("advantage").orEmpty().trim(), m.text("disadvantage").orEmpty().trim(), sets)
    }

    private fun sameBonus(a: Element?, b: Element): Boolean {
        if (a == null) return false
        val x = a.children(); val y = b.children()
        if (x.size != y.size || x.isEmpty()) return false
        return x.indices.all { x[it].tagName == y[it].tagName && x[it].textContent.replace(Regex("\\s+"), "") == y[it].textContent.replace(Regex("\\s+"), "") }
    }

    /** Take choice [name] of [set] for the spirit [guid] (an empty name clears it). False when the spirit or the choice does not exist. */
    fun choose(guid: String, set: Int, name: String): Boolean {
        val m = find(guid) ?: return false
        val entry = entryOf(m) ?: return false
        val tag = "choice$set"
        if (set !in 1..2) return false
        val old = m.child(tag)
        val fresh = el(tag)
        if (name.isNotEmpty()) {
            val choice = entry.child("choices")?.children("choice").orEmpty().firstOrNull { setOf(it) == set && it.text("name")?.trim() == name } ?: return false
            choice.child("bonus")?.children().orEmpty().forEach { fresh.appendChild(file.doc.importNode(it, true)) }
        }
        if (old != null) m.replaceChild(fresh, old) else m.appendChild(fresh)
        rebuild(guid)
        return true
    }

    /** Remove the spirit's improvements and make them again from its bonus and chosen choices. */
    fun rebuild(guid: String) {
        val m = find(guid) ?: return
        val entry = entryOf(m)
        val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
        imps.children("improvement").filter { it.text("improvementsource")?.trim() == "MentorSpirit" && it.text("sourcename")?.trim().equals(guid, true) && it.text("improvementttype")?.trim() != "MentorSpirit" }
            .forEach { imps.removeChild(it) }
        val name = m.text("name")?.trim().orEmpty()
        val nodes = listOfNotNull(entry?.child("bonus"), m.child("choice1"), m.child("choice2")).filter { it.children().isNotEmpty() }
        for (n in nodes) {
            val result = BonusHandlers.apply(n, BonusContext("MentorSpirit", guid, 1, "", name, objects = EditorObjects(file, data)))
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(file.doc, imp))
        }
    }
}
