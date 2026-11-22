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

            val warnings = ArrayList<String>()
            val carryover = settings.karmaCost["karmacarryover"] ?: 7
            if (ledger.remaining > carryover) {
                warnings += "${ledger.remaining} karma left but only $carryover carry over into the career; the rest is lost"
            }
            if (ledger.notCounted.isNotEmpty()) warnings += "Not counted in the karma total yet: ${ledger.notCounted.joinToString()}"

            return CreationReview(
                problems, warnings,
                notChecked = listOf(
                    "attribute and skill limits per metatype and group", "contact limits", "prototype transhuman and metagenic quality balance",
                    "cyberware grades and capacity", "drone and vehicle rules", "nuyen limits",
                ),
            )
        }
    }
}
