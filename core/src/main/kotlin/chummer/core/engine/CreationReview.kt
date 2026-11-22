package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text

/**
 * What stands between a Karma-build character in creation and finishing it, following the checks Chummer runs when you
 * choose to create the character (`ValidateCharacter` and `CheckCharacterValidity`). Only some of Chummer's checks are
 * ported; [notChecked] says which kinds are not, so a clean review is not a promise that Chummer will accept the file.
 *
 *  - [problems] stop a character from being finished: karma overspent, quality limits, knowledge points over the pool,
 *    magic switched on without a tradition.
 *  - [warnings] are things Chummer asks you to confirm, such as karma that will be lost because only a limited amount
 *    carries over into the career.
 */
data class CreationReview(val problems: List<String>, val warnings: List<String>, val notChecked: List<String>) {
    val canFinish get() = problems.isEmpty()

    companion object {
        fun of(c: CharacterFile, settings: CharacterSettings, data: GameData? = null): CreationReview {
            val ledger = KarmaLedger.of(c, settings, data)
            val problems = ArrayList(ledger.problems)

            // Magic needs a tradition (Chummer: "No Magic Tradition has been selected").
            val magic = c.root.text("magenabled")?.trim().equals("True", true)
            val tradition = c.root.child("tradition")?.text("name")?.trim().orEmpty()
            if (magic && c.root.text("magician")?.trim().equals("True", true) && tradition.isEmpty()) problems += "No magic tradition has been chosen"

            // Nuyen: what the character carries against what the karma-to-nuyen budget gives, and the availability limit.
            val warnings = ArrayList<String>()
            if (c.root.text("adept")?.trim().equals("True", true) && data != null) {
                val pp = PowerPoints.of(c, settings, data)
                if (pp.used > pp.total + 0.001) problems += "Power points used %.2f of %.2f".format(java.util.Locale.ROOT, pp.used, pp.total)
            }
            if (data != null) {
                val nuyen = NuyenLedger.of(c, settings, data)
                if (nuyen.remaining < -0.004) problems += "Nuyen overspent by ¥" + "%,d".format(Math.round(-nuyen.remaining))
                if (nuyen.unreadable.isNotEmpty()) warnings += "Prices not counted yet: " + nuyen.unreadable.take(5).joinToString() + if (nuyen.unreadable.size > 5) ", ..." else ""
                problems += availabilityProblems(c, settings, data)
            }
            val carryover = settings.karmaCost["karmacarryover"] ?: 7
            if (ledger.remaining > carryover) {
                warnings += "${ledger.remaining} karma left but only $carryover carry over into the career; the rest is lost"
            }
            if (ledger.notCounted.isNotEmpty()) warnings += "Not counted in the karma total yet: ${ledger.notCounted.joinToString()}"

            return CreationReview(
                problems, warnings,
                notChecked = listOf(
                    "attribute and skill limits per metatype and group", "contact limits", "prototype transhuman and metagenic quality balance",
                    "cyberware capacity", "drone and vehicle rules",
                ),
            )
        }
    }
}

/**
 * Items whose availability is over the settings' limit (12 by default), as Chummer's creation check reports them. Ware counts its
 * grade's availability modifier. Items with an availability the evaluator cannot read are skipped.
 */
internal fun availabilityProblems(c: CharacterFile, settings: CharacterSettings, data: GameData): List<String> {
    val limit = settings.int("availability", 12)
    val out = ArrayList<String>()
    val pack = data as? chummer.core.DataPack
    fun check(e: org.w3c.dom.Element, extra: Int = 0) {
        val rating = e.text("rating")?.trim()?.toIntOrNull() ?: 0
        val v = Availability.parse(e.text("avail").orEmpty(), rating) ?: return
        if (v.number + extra > limit) out += "${e.text("name")?.trim()} has availability ${v.number + extra} (limit $limit)"
    }
    for (g in c.root.child("gears")?.children("gear").orEmpty()) check(g)
    for (w in c.root.child("weapons")?.children("weapon").orEmpty()) { check(w); for (a in w.child("accessories")?.children("accessory").orEmpty()) if (a.text("included")?.trim() != "True") check(a) }
    for (a in c.root.child("armors")?.children("armor").orEmpty()) { check(a); for (m in a.child("armormods")?.children("armormod").orEmpty()) if (m.text("included")?.trim() != "True") check(m) }
    fun ware(w: org.w3c.dom.Element) {
        val bio = w.text("improvementsource")?.trim() == "Bioware"
        val grade = pack?.let { p -> CyberwareEditor.grades(p, bio).firstOrNull { it.child("name")?.textContent?.trim() == w.text("grade")?.trim() } }
        check(w, grade?.child("avail")?.textContent?.trim()?.removePrefix("+")?.toIntOrNull() ?: 0)
        for (ch in w.child("children")?.children("cyberware").orEmpty()) ware(ch)
    }
    for (w in c.root.child("cyberwares")?.children("cyberware").orEmpty()) ware(w)
    return out
}
