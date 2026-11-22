package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.w3c.dom.Element

/**
 * The nuyen budget of a Karma-build character in creation, following Chummer's `TotalStartingNuyen`: the karma put into nuyen
 * (`<nuyenbp>`, at most the settings' `nuyenmaxbp`) goes through the settings' karma-to-nuyen expression, and everything the
 * character carries costs nuyen. What is left is [remaining]; Chummer keeps that in `<nuyen>`.
 *
 * [unreadable] lists items whose price formula the evaluator cannot read (they count as free). Lifestyles and vehicles are not
 * priced yet. The prices follow Chummer's formulas as ported for career purchases (checked against Viper's purchase log); a
 * Chummer-made creation file with gear would confirm the totals.
 */
data class NuyenLedger(val karmaBought: Int, val maxKarma: Int, val budget: Double, val spent: Double, val unreadable: List<String>) {
    val remaining get() = budget - spent

    companion object {
        fun of(c: CharacterFile, settings: CharacterSettings, data: GameData): NuyenLedger {
            val bp = c.root.text("nuyenbp")?.trim()?.toDoubleOrNull()?.toInt() ?: 0
            val max = if (settings.bool("unrestrictednuyen")) Int.MAX_VALUE else settings.int("nuyenmaxbp", 10)
            val karma = bp.coerceIn(0, max)
            val priority = c.root.text("startingnuyen")?.trim()?.toDoubleOrNull() ?: 0.0
            val budget = settings.evaluate("chargenkarmatonuyenexpression", mapOf("Karma" to karma.toDouble(), "PriorityNuyen" to priority), "{Karma} * 2000 + {PriorityNuyen}")
            val unreadable = ArrayList<String>()
            val pack = data as? DataPack
            fun formula(text: String, rating: Int, what: String): Double {
                val v = CyberwareEditor.formula(text, rating)
                if (v == null) unreadable += what
                return v ?: 0.0
            }
            fun num(e: Element, tag: String) = e.text(tag)?.trim()?.toIntOrNull() ?: 0

            fun gear(g: Element): Double {
                val rating = num(g, "rating")
                val qty = g.text("qty")?.trim()?.toDoubleOrNull() ?: 1.0
                val per = g.text("costfor")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                var total = formula(g.text("cost").orEmpty(), rating, g.text("name").orEmpty()) * qty / per
                for (child in g.child("children")?.children("gear").orEmpty()) total += gear(child)
                return total
            }
            fun gearsIn(e: Element) = e.child("gears")?.children("gear").orEmpty().sumOf { gear(it) }

            fun weapon(w: Element): Double {
                if (w.text("included")?.trim() == "True") return 0.0
                val own = formula(w.text("cost").orEmpty(), num(w, "rating"), w.text("name").orEmpty())
                var total = own
                for (a in w.child("accessories")?.children("accessory").orEmpty()) {
                    if (a.text("included")?.trim() == "True") continue
                    val text = a.text("cost").orEmpty().replace("Weapon Cost", own.toString())
                    total += formula(text, num(a, "rating"), a.text("name").orEmpty()) + gearsIn(a)
                }
                for (u in w.child("underbarrel")?.children("weapon").orEmpty()) total += weapon(u)
                return total
            }

            fun armor(a: Element): Double {
                val rating = num(a, "rating")
                var total = formula(a.text("cost").orEmpty(), rating, a.text("name").orEmpty())
                for (m in a.child("armormods")?.children("armormod").orEmpty()) {
                    if (m.text("included")?.trim() == "True") continue
                    total += formula(m.text("cost").orEmpty(), num(m, "rating"), m.text("name").orEmpty())
                }
                return total + gearsIn(a)
            }

            fun gradeMultiplier(w: Element): Double {
                val grade = w.text("grade")?.trim().orEmpty()
                val bio = w.text("improvementsource")?.trim() == "Bioware"
                return pack?.let { p -> CyberwareEditor.grades(p, bio).firstOrNull { it.child("name")?.textContent?.trim() == grade } }
                    ?.child("cost")?.textContent?.trim()?.toDoubleOrNull() ?: 1.0
            }
            fun ware(w: Element): Double {
                var total = formula(w.text("cost").orEmpty(), num(w, "rating"), w.text("name").orEmpty()) * gradeMultiplier(w)
                for (child in w.child("children")?.children("cyberware").orEmpty()) total += ware(child)
                return total + gearsIn(w)
            }

            var spent = 0.0
            for (g in c.root.child("gears")?.children("gear").orEmpty()) spent += gear(g)
            for (w in c.root.child("weapons")?.children("weapon").orEmpty()) spent += weapon(w)
            for (a in c.root.child("armors")?.children("armor").orEmpty()) spent += armor(a)
            for (w in c.root.child("cyberwares")?.children("cyberware").orEmpty()) spent += ware(w)
            return NuyenLedger(karma, if (max == Int.MAX_VALUE) -1 else max, budget, spent, unreadable.distinct())
        }

        /** Keep the stored `<nuyen>` equal to what is left, as Chummer does while a character is in creation. */
        fun sync(c: CharacterFile, settings: CharacterSettings, data: GameData) {
            val remaining = of(c, settings, data).remaining
            c.root.child("nuyen")?.textContent = if (remaining == Math.rint(remaining)) remaining.toLong().toString() else remaining.toString()
        }
    }
}

/** Availability such as `12R` or `(Rating * 5)F`: the number, worked out at a rating, and whether it is restricted or forbidden. */
object Availability {
    data class Value(val number: Int, val flag: Char?)

    fun parse(text: String, rating: Int = 0): Value? {
        val t = text.trim()
        if (t.isEmpty()) return Value(0, null)
        val flag = t.last().takeIf { it == 'R' || it == 'F' }
        val body = if (flag != null) t.dropLast(1).trim() else t
        val n = CyberwareEditor.formula(body.removePrefix("+"), rating) ?: return null
        return Value(Math.round(n).toInt(), flag)
    }
}
