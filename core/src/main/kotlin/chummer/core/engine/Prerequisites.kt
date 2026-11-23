package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * The `required` and `forbidden` blocks of a game-data entry, evaluated against a character as Chummer's
 * `SelectionShared.RequirementsMet` does: every `oneof` inside `required` needs one of its items, every `allof` needs
 * all of them, and any item of a `oneof` inside `forbidden` rules the entry out. `chargenonly`, `careeronly` and
 * `onlyprioritygiven` on the entry itself are checked too.
 *
 * Chummer's test has about 83 item kinds; the ones read here are the common ones (quality, metatype, metatype
 * category, attribute, Magic/Resonance/Depth enabled, powers, spells, critter powers, ware and gear by name). An item
 * kind that is not read is treated as satisfied in `required` and as absent in `forbidden`, and is recorded in
 * [Result.unchecked], so a requirement is never refused on a rule this code does not know.
 */
object Prerequisites {
    class Result(val problems: List<String>, val unchecked: Set<String>) {
        val met get() = problems.isEmpty()
    }

    fun check(entry: Element, file: CharacterFile, data: GameData, rules: RuleSettings = RuleSettings(), checkLimit: Boolean = true): Result {
        val root = file.root
        val created = root.text("created")?.trim().equals("True", true)
        val problems = ArrayList<String>()
        val unchecked = sortedSetOf<String>()
        if (created && entry.child("chargenonly") != null) problems += "Only available in character creation"
        if (!created && entry.child("careeronly") != null) problems += "Only available in career"
        if (!created && entry.child("onlyprioritygiven") != null && root.text("buildmethod")?.trim() in setOf("Priority", "SumtoTen")) {
            problems += "Only given by a priority choice"
        }

        // How many times the entry may be taken: its `limit` (a quality defaults to once; "False" means no limit); a creation-only
        // `chargenlimit` replaces it while the character is being created. A limit that is a formula is not read.
        if (checkLimit) {
            val chargen = entry.child("chargenlimit")?.textContent?.trim().orEmpty()
            val own = entry.child("limit")?.textContent?.trim().orEmpty()
            val limit = if (chargen.isNotEmpty() && !created) chargen else own.ifEmpty { if (entry.tagName == "quality") "1" else "False" }
            val max = limit.toIntOrNull()
            if (max != null) {
                val name = entry.child("name")?.textContent?.trim().orEmpty()
                val have = root.child("qualities")?.children("quality").orEmpty().count { it.text("name")?.trim().equals(name, true) }
                if (entry.tagName == "quality" && have >= max) problems += if (max == 1) "Already taken" else "Already taken $have times (limit $max)"
            }
        }

        val attrs by lazy { AttributeEngine.forFile(file, rules) }

        fun names(list: String, item: String, tag: String = "name"): List<String> =
            root.child(list)?.children(item).orEmpty().mapNotNull { it.text(tag)?.trim() }

        /** True, false, or null when the item kind is not read here. */
        fun test(node: Element): Pair<Boolean?, String> {
            val text = node.textContent.trim()
            val name = node.text("name")?.trim() ?: ""
            return when (node.tagName) {
                "quality", "characterquality" -> {
                    val wanted = name.ifEmpty { text }
                    val extra = node.text("extra")?.trim().orEmpty()
                    val have = root.child("qualities")?.children("quality").orEmpty().any {
                        (it.text("name")?.trim().equals(wanted, true) || it.text("sourceid")?.trim().equals(wanted, true)) &&
                            (extra.isEmpty() || it.text("extra")?.trim().equals(extra, true))
                    }
                    have to wanted + (if (extra.isNotEmpty()) " ($extra)" else "")
                }
                "metatype" -> {
                    val shown = data[text]?.element?.child("name")?.textContent?.trim() ?: text
                    (root.text("metatype")?.trim().let { it.equals(text, true) || it.equals(shown, true) }) to "$shown (metatype)"
                }
                "metatypecategory" -> root.text("metatypecategory")?.trim().equals(text, true) to "$text (metatype category)"
                "metavariant" -> root.text("metavariant")?.trim().equals(text, true) to "$text (metavariant)"
                "magenabled" -> (root.text("magenabled")?.trim() == "True") to "Magic enabled"
                "resenabled" -> (root.text("resenabled")?.trim() == "True") to "Resonance enabled"
                "depenabled" -> (root.text("depenabled")?.trim() == "True") to "Depth enabled"
                "attribute" -> {
                    val target = node.text("total")?.trim()?.toIntOrNull() ?: 0
                    val a = attrs[name]
                    val have = if (node.child("natural") != null) a?.value else a?.totalValue
                    ((have ?: 0) >= target) to "$name $target"
                }
                "power" -> names("powers", "power").any { it.equals(text, true) } to "$text (power)"
                "critterpower" -> names("critterpowers", "critterpower").any { it.equals(name.ifEmpty { text }, true) } to "${name.ifEmpty { text }} (critter power)"
                "spell" -> names("spells", "spell").any { it.equals(text, true) } to "$text (spell)"
                "gear" -> names("gears", "gear").any { it.equals(name.ifEmpty { text }, true) } to "${name.ifEmpty { text }} (gear)"
                "bioware", "cyberware" -> names("cyberwares", "cyberware").any { it.equals(name.ifEmpty { text }, true) } to "${name.ifEmpty { text }} (${node.tagName})"
                "cyberwarecontains", "biowarecontains" -> names("cyberwares", "cyberware").any { it.contains(text, true) } to "$text (${node.tagName})"
                else -> null to node.tagName
            }
        }

        entry.child("forbidden")?.children("oneof")?.forEach { oneOf ->
            for (item in oneOf.children()) {
                val (ok, label) = test(item)
                if (ok == null) unchecked += item.tagName else if (ok) problems += "Not allowed with $label"
            }
        }
        entry.child("required")?.let { required ->
            for (oneOf in required.children("oneof")) {
                val results = oneOf.children().map { test(it) to it.tagName }
                results.filter { it.first.first == null }.forEach { unchecked += it.second }
                if (results.isNotEmpty() && results.none { it.first.first != false }) {
                    problems += "Needs one of: " + results.joinToString(", ") { it.first.second }
                }
            }
            for (allOf in required.children("allof")) {
                for (item in allOf.children()) {
                    val (ok, label) = test(item)
                    if (ok == null) unchecked += item.tagName else if (!ok) problems += "Needs $label"
                }
            }
        }
        return Result(problems, unchecked)
    }
}
