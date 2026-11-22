package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text

/** A complex form for a picker. [extra] is true when the form asks for a choice (a Matrix attribute) when it is learned. */
data class ComplexFormOption(val id: String, val name: String, val target: String, val duration: String, val fade: String, val source: String, val needsChoice: Boolean)

/** A complex form on the character. */
data class ComplexFormLine(val guid: String, val name: String, val extra: String, val target: String, val duration: String, val fade: String)

/** What a technomancer sheet shows: the forms, how many are free, how many the character can know, and the karma price of one more. */
data class ComplexFormSection(val forms: List<ComplexFormLine>, val free: Int, val limit: Int, val price: Int)

object ComplexForms {
    fun catalog(data: DataPack): List<ComplexFormOption> =
        data.byId.entries.filter { it.value.file == "complexforms.xml" && it.value.element.tagName == "complexform" }.map { (id, e) ->
            fun t(tag: String) = e.element.child(tag)?.textContent?.trim().orEmpty()
            ComplexFormOption(id, t("name"), t("target"), t("duration"), t("fv"), t("source"), t("name").contains("["))
        }.sortedBy { it.name.lowercase() }

    /** Chummer's `ComplexFormKarmaCost`: the plain price changed by the character's cost improvements. */
    fun price(base: Int, set: ImprovementSet): Int {
        var v = base.toDouble()
        v += set.all.filter { it.type == "NewComplexFormKarmaCost" && it.enabled }.sumOf { it.value * it.rating }
        var multiplier = 1.0
        set.all.filter { it.type == "NewComplexFormKarmaCostMultiplier" && it.enabled }.forEach { multiplier *= it.value / 100.0 }
        return maxOf(standardRound(v * multiplier), 0)
    }

    /** How many forms the character may know: twice Resonance plus the limit improvements (Chummer's rule in `cmdAddComplexForm_Click`). */
    fun limit(c: CharacterFile, data: GameData?, settings: CharacterSettings): Int {
        val rules = settings.toRuleSettings()
        val res = AttributeEngine.forFile(c, rules)["RES"]?.totalValue ?: 0
        val set = ImprovementSet(Improvement.parseAll(c.root), c.root.text("created")?.trim().equals("True", true))
        return res * 2 + standardRound(set.valueOf("ComplexFormLimit"))
    }

    fun room(c: CharacterFile, data: GameData?, settings: CharacterSettings) =
        limit(c, data, settings) - c.root.child("complexforms")?.children("complexform").orEmpty().size

    fun section(c: CharacterFile, data: GameData?, settings: CharacterSettings, base: Int): ComplexFormSection {
        val set = ImprovementSet(Improvement.parseAll(c.root), c.root.text("created")?.trim().equals("True", true))
        return ComplexFormSection(
            c.root.child("complexforms")?.children("complexform").orEmpty().map {
                ComplexFormLine(it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("extra").orEmpty().trim(), it.text("target").orEmpty().trim(), it.text("duration").orEmpty().trim(), it.text("fv").orEmpty().trim())
            },
            c.root.text("cfplimit")?.trim()?.toIntOrNull() ?: 0, limit(c, data, settings), price(base, set),
        )
    }
}
