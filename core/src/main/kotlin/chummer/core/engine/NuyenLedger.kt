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
 * [unreadable] lists items whose price formula the evaluator cannot read (they count as free). Lifestyles and vehicles are priced too:
 * Viper's lifestyle and its three vehicles equal the totals Chummer printed (LifestyleVehicleCostTest). The prices follow Chummer's formulas as ported for career purchases (checked against Viper's purchase log); a
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

            /** A price formula that may name the item's own figures: Rating, Body and "Vehicle Cost". */
            fun vehicleFormula(text: String, rating: Int, body: Int, vehicleCost: Double, what: String): Double {
                val t = text.trim()
                val v = if (t.startsWith("FixedValues(")) CyberwareEditor.formula(t, rating)
                else chummer.core.Expr.evalOrNull(t.replace("Vehicle Cost", "VehicleCost"), mapOf("Rating" to rating.toDouble(), "Body" to body.toDouble(), "VehicleCost" to vehicleCost))
                if (v == null) unreadable += what
                return v ?: 0.0
            }
            fun vehicle(v: Element): Double {
                val own = formula(v.text("cost").orEmpty(), 0, v.text("name").orEmpty())
                val body = v.text("body")?.trim()?.substringBefore('/')?.toIntOrNull() ?: 0
                var total = own
                for (m in v.child("mods")?.children("mod").orEmpty()) {
                    if (m.text("included")?.trim() == "True") continue
                    total += vehicleFormula(m.text("cost").orEmpty(), num(m, "rating"), body, own, m.text("name").orEmpty())
                    total += gearsIn(m)
                }
                total += gearsIn(v)
                for (mount in v.child("weaponmounts")?.children("weaponmount").orEmpty()) {
                    total += formula(mount.text("cost").orEmpty(), 0, mount.text("name").orEmpty())
                    for (m in mount.child("mods")?.children("vehiclemod").orEmpty()) if (m.text("included")?.trim() != "True") total += formula(m.text("cost").orEmpty(), num(m, "rating"), m.text("name").orEmpty())
                    for (w in mount.child("weapons")?.children("weapon").orEmpty()) total += weapon(w)
                }
                return total
            }

            var spent = 0.0
            val imps = Improvement.parseAll(c.root)
            val dependents = c.root.child("qualities")?.children("quality").orEmpty().filter { it.text("name")?.contains("Dependent") == true }.mapNotNull { it.text("guid")?.trim()?.lowercase() }.toSet()
            for (l in c.root.child("lifestyles")?.children("lifestyle").orEmpty()) spent += lifestyleTotal(l, imps, dependents)
            for (v in c.root.child("vehicles")?.children("vehicle").orEmpty()) spent += vehicle(v)
            for (g in c.root.child("gears")?.children("gear").orEmpty()) spent += gear(g)
            for (w in c.root.child("weapons")?.children("weapon").orEmpty()) spent += weapon(w)
            for (a in c.root.child("armors")?.children("armor").orEmpty()) spent += armor(a)
            for (w in c.root.child("cyberwares")?.children("cyberware").orEmpty()) spent += ware(w)
            return NuyenLedger(karma, if (max == Int.MAX_VALUE) -1 else max, budget, spent, unreadable.distinct())
        }

        /**
         * A lifestyle's total cost, following Chummer's `CostPreSplit`: the base cost, the multipliers of qualities chosen by the user,
         * area, comforts and security, entertainment assets and other qualities as flat costs (qualities paid in LP cost no nuyen),
         * roommates, split with roommates, the cost adjustments of improvements (dependents first, then the metatype, then the rest; "once" adjustments that Chummer
         * spreads over several lifestyles are not applied), outings and services, the percentage, contracts, then times the months bought.
         */
        fun lifestyleTotal(l: Element, improvements: List<Improvement> = emptyList(), dependentQualityGuids: Set<String> = emptySet()): Double {
            fun d(tag: String) = l.text(tag)?.trim()?.toDoubleOrNull() ?: 0.0
            if (l.text("trustfund")?.trim() == "True") return 0.0
            val quals = l.child("lifestylequalities")?.children("lifestylequality").orEmpty().filter { it.text("lifestylequalitysource")?.trim() != "BuiltIn" }
            var cost = d("cost")
            var multiplier = 1.0
            for (q in quals) { val m = q.text("basemultiplier")?.trim()?.toDoubleOrNull() ?: 0.0; if (m != 0.0) multiplier *= 1.0 + m / 100.0 }
            cost *= multiplier
            val levels = d("area") + d("comforts") + d("security")
            if (levels != 0.0) cost *= 1.0 + 0.1 * levels
            cost += d("area") * d("costforearea") + d("comforts") * d("costforcomforts") + d("security") * d("costforsecurity")
            fun flat(q: Element) = if (q.text("uselpcost")?.trim() == "True") 0.0 else q.text("cost")?.trim()?.toDoubleOrNull() ?: 0.0
            fun mult(q: Element) = q.text("multiplier")?.trim()?.toDoubleOrNull() ?: 0.0
            fun type(q: Element) = q.text("lifestylequalitytype")?.trim().orEmpty()
            val assets = quals.filter { type(it) == "Entertainment" && it.text("category")?.contains("Asset") == true }
            var m2 = 1.0
            for (q in assets) if (mult(q) != 0.0) m2 *= 1.0 + mult(q) / 100.0
            cost = cost * m2 + assets.sumOf { flat(it) }
            val others = quals.filter { type(it) != "Entertainment" && type(it) != "Contracts" }
            m2 = 1.0
            for (q in others) if (mult(q) != 0.0) m2 *= 1.0 + mult(q) / 100.0
            cost = cost * m2 + others.sumOf { flat(it) }
            val roommates = d("roommates")
            if (l.text("splitcostwithroommates")?.trim() == "True") cost /= roommates + 1.0
            if (roommates > 0) cost *= 1.0 + 0.1 * roommates
            // Adjustments from the character (metatype, dependents, augmentations...), for this lifestyle or any.
            val base = l.text("baselifestyle")?.trim().orEmpty()
            val standard = l.text("type")?.trim().let { it.isNullOrEmpty() || it == "Standard" }
            val adjust = improvements.filter {
                it.enabled && (it.type == "LifestyleCost" || (standard && it.type == "BasicLifestyleCost")) && it.condition != "once" && (it.improvedName.isEmpty() || it.improvedName == base)
            }
            var dependents = 0.0
            var metatype = 0.0
            var other = 1.0
            for (i in adjust) when {
                i.source == "Quality" && i.sourceName.lowercase() in dependentQualityGuids -> dependents += i.value
                i.source == "Heritage" || i.source == "Metatype" || i.source == "Metavariant" -> metatype += i.value
                else -> other *= 1.0 + i.value / 100.0
            }
            if (dependents != 0.0) cost *= 1.0 + dependents / 100.0
            if (metatype != 0.0) cost *= 1.0 + metatype / 100.0
            if (other != 1.0) cost *= other
            // Outings and services, then contracts (flat costs that are not part of the lifestyle's own price).
            val outings = quals.filter { type(it) == "Entertainment" && it.text("category")?.contains("Asset") != true }
            var outingMultiplier = 1.0
            var outingBase = 1.0
            for (q in outings) { outingMultiplier *= 1.0 + mult(q) / 100.0; outingBase *= 1.0 + (q.text("basemultiplier")?.trim()?.toDoubleOrNull() ?: 0.0) / 100.0 }
            if (outingMultiplier != 1.0) cost *= outingMultiplier
            cost += outings.sumOf { flat(it) }
            if (outingBase != 1.0) cost += d("cost") * outingBase
            cost *= (l.text("percentage")?.trim()?.toDoubleOrNull() ?: 100.0) / 100.0
            val contracts = quals.filter { type(it) == "Contracts" }.sumOf { flat(it) }
            cost += when (l.text("increment")?.trim()) { "Day" -> contracts / (4.34812 * 7); "Week" -> contracts / 4.34812; else -> contracts }
            return maxOf(cost, 0.0) * (l.text("months")?.trim()?.toDoubleOrNull() ?: 1.0)
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
