package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** An adept power for a picker. [choices] lists what the user can pick for powers that ask (a skill, an attribute); empty means free text. */
data class PowerOption(
    val id: String, val name: String, val pointsPerLevel: Double, val levels: Boolean, val maxLevels: Int, val source: String, val page: String,
    val needsChoice: Boolean, val choices: List<String>, val unsupported: List<String>,
)

object PowerCatalog {
    fun list(data: DataPack): List<PowerOption> =
        data.byId.entries.filter { it.value.file == "powers.xml" && it.value.element.tagName == "power" }.mapNotNull { (id, e) ->
            val el = e.element
            if (el.child("hide") != null) return@mapNotNull null
            fun t(tag: String) = el.child(tag)?.textContent?.trim().orEmpty()
            val bonus = el.child("bonus")
            val nodes = bonus?.children().orEmpty().map { it.tagName.lowercase() }
            PowerOption(
                id, t("name"), t("points").toDoubleOrNull() ?: 0.0, t("levels").equals("True", true), t("maxlevels").toIntOrNull() ?: t("maxlevel").toIntOrNull() ?: 0,
                t("source"), t("page"), nodes.any { it in QualityCatalog.CHOICE_NODES }, QualityCatalog.choicesFor(data, bonus),
                nodes.filter { !BonusHandlers.knows(it) }.distinct(),
            )
        }.sortedBy { it.name.lowercase() }
}

/** One power's points, as Chummer's `Power.PowerPoints` works them out. */
data class PowerLine(
    val guid: String, val name: String, val extra: String, val rating: Int, val freeLevels: Int, val levels: Boolean, val maxLevels: Int, val points: Double,
)

/** The power points of an adept: what MAG and improvements give, and what the powers use. */
data class PowerPoints(val total: Double, val used: Double, val powers: List<PowerLine>) {
    val left get() = total - used

    companion object {
        fun of(c: CharacterFile, settings: CharacterSettings, data: GameData): PowerPoints {
            val set = ImprovementSet(Improvement.parseAll(c.root), isCareer = c.root.text("created")?.trim().equals("True", true))
            val attrs = AttributeEngine.forSet(c, set, settings.toRuleSettings())
            val mysticAdept = c.root.text("adept")?.trim().equals("True", true) && c.root.text("magician")?.trim().equals("True", true)
            // A mystic adept's power points are the share of MAG given to the adept side (unless the settings give them their own MAG attribute).
            val mag = if (mysticAdept && !settings.bool("mysadeptsecondmagattribute")) c.root.text("magsplitadept")?.trim()?.toIntOrNull() ?: 0
            else if (!mysticAdept) attrs["MAG"]?.totalValue ?: 0 // a pure adept's power points are the whole of MAG
            else attrs["MAGAdept"]?.totalValue ?: 0
            val total = maxOf(mag + set.valueOf("AdeptPowerPoints"), 0.0)
            val skills = c.skills().filter { !it.isKnowledge }
            val lines = c.root.child("powers")?.children("power").orEmpty().map { p ->
                fun d(tag: String) = p.text(tag)?.trim()?.toDoubleOrNull() ?: 0.0
                val name = p.text("name")?.trim().orEmpty()
                val extra = p.text("extra")?.trim().orEmpty()
                val levels = p.text("levels")?.trim().equals("True", true)
                val rating = d("rating").toInt()
                val freeLevels = set.matching("AdeptPowerFreeLevels", name).filter { it.uniqueName == extra }.sumOf { it.rating }.toInt()
                val freePoints = set.matching("AdeptPowerFreePoints", name).filter { it.uniqueName == extra }.sumOf { it.rating } * 0.25
                var cap = if (!levels) 1 else (p.text("maxlevel")?.trim()?.toIntOrNull() ?: 0).let { if (it <= 0) Int.MAX_VALUE else it }
                if (levels) cap = minOf(cap, mag)
                if (levels && name.startsWith("Improved Ability") && extra.isNotEmpty()) {
                    val learned = skills.firstOrNull { it.name == extra }?.let { it.base + it.karma } ?: 0
                    cap = minOf(cap, if (settings.bool("increasedimprovedabilitymultiplier")) learned + (learned + 1) / 2 else (learned + 1) / 2)
                }
                val totalRating = minOf(rating + freeLevels, cap)
                val perLevel = d("pointsperlevel")
                val points = when {
                    rating == 0 -> 0.0
                    !levels && freeLevels > 0 -> 0.0
                    else -> {
                        var ret = d("extrapointcost")
                        ret += if (freeLevels * perLevel >= freePoints) rating * perLevel else totalRating * perLevel - freePoints
                        maxOf(ret, 0.0)
                    }
                }
                PowerLine(p.text("guid")?.trim().orEmpty(), name, extra, rating, freeLevels, levels, if (cap == Int.MAX_VALUE) 0 else cap, points)
            }
            return PowerPoints(total, lines.sumOf { it.points }, lines)
        }
    }
}

/**
 * Adept powers on a character, in the layout Chummer writes (checked against the powers in the 35 fixtures). Power points come
 * from MAG, so a power costs no karma or nuyen here (mystic adepts pay karma for power points; that is not built).
 */
class PowerEditor(
    private val file: CharacterFile, private val data: GameData, private val settings: CharacterSettings? = null,
    private val translate: (String) -> String? = { null },
) {
    private val doc get() = file.doc
    private val root get() = file.root
    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    private fun power(guid: String) = root.child("powers")?.children("power")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** Why this power cannot be added, or null. A power can be taken once for each name and choice. */
    fun problem(entryId: String, extra: String = ""): String? {
        val entry = data[entryId]?.element ?: return "unknown power"
        if (entry.tagName != "power") return "not a power"
        val adept = root.text("adept")?.trim().equals("True", true)
        if (!adept) return "the character is not an adept"
        val name = entry.child("name")?.textContent?.trim().orEmpty()
        if (root.child("powers")?.children("power").orEmpty().any { it.text("name")?.trim() == name && it.text("extra")?.trim().orEmpty() == extra }) return "already taken"
        val cfg = settings
        if (cfg != null) {
            val cost = (entry.child("points")?.textContent?.trim()?.toDoubleOrNull() ?: 0.0) + (entry.child("extrapointcost")?.textContent?.trim()?.toDoubleOrNull() ?: 0.0)
            if (PowerPoints.of(file, cfg, data).left + 1e-9 < cost) return "not enough power points"
        }
        return null
    }

    /** Add a power at [rating] (levels); a power without levels is taken once. Returns the new power's GUID. */
    fun add(entryId: String, rating: Int = 1, extra: String = ""): String {
        problem(entryId, extra)?.let { error("Cannot add this power: $it") }
        val entry = data[entryId]!!.element
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val levels = d("levels").equals("True", true)
        val p = el("power")
        fun add(tag: String, text: String? = null) = p.appendChild(el(tag, text))
        add("id", entryId.lowercase()); add("guid", guid); add("name", d("name")); add("extra", extra)
        add("pointsperlevel", d("points")); add("adeptway", d("adeptway").ifEmpty { "0" }); add("action", d("action"))
        add("rating", rating.coerceAtLeast(0).toString()); add("extrapointcost", d("extrapointcost").ifEmpty { "0" }); add("levels", if (levels) "True" else "False")
        add("maxlevel", (d("maxlevels").ifEmpty { d("maxlevel") }).ifEmpty { "0" }); add("discounted", "False"); add("discountedgeas", "False"); add("bonussource"); add("freepoints", "0")
        add("source", d("source")); add("page", d("page"))
        entry.child("bonus")?.let { p.appendChild(doc.importNode(it, true)) } ?: add("bonus")
        entry.child("adeptwayrequires")?.let { p.appendChild(doc.importNode(it, true)) } ?: add("adeptwayrequires")
        add("enhancements"); add("notes")
        (root.child("powers") ?: el("powers").also { root.appendChild(it) }).appendChild(p)
        applyBonus(p)
        return guid
    }

    /** Change a power's levels; fails outside 1 to its maximum, or for a power without levels. */
    fun setRating(guid: String, rating: Int): Boolean {
        val p = power(guid) ?: return false
        if (!p.text("levels")?.trim().equals("True", true)) return false
        val max = maxLevels(p)
        if (rating < 1 || rating > max) return false
        val old = p.text("rating")?.trim()?.toIntOrNull() ?: 0
        val cfg = settings
        if (rating > old && cfg != null) {
            val perLevel = p.text("pointsperlevel")?.trim()?.toDoubleOrNull() ?: 0.0
            if (PowerPoints.of(file, cfg, data).left + 1e-9 < (rating - old) * perLevel) return false
        }
        p.child("rating")!!.textContent = rating.toString()
        applyBonus(p)
        return true
    }

    fun remove(guid: String): Boolean {
        val p = power(guid) ?: return false
        removeImprovements(guid)
        p.parentNode.removeChild(p)
        return true
    }

    /** The most levels this power can have now: MAG, the data's own limit, and for Improved Ability half the skill. */
    fun maxLevels(p: Element): Int {
        val cfg = settings ?: return Int.MAX_VALUE
        val line = PowerPoints.of(file, cfg, data).powers.firstOrNull { it.guid.equals(p.text("guid")?.trim(), true) }
        return line?.maxLevels?.takeIf { it > 0 } ?: Int.MAX_VALUE
    }

    private fun removeImprovements(guid: String) {
        val imps = root.child("improvements") ?: return
        for (i in imps.children("improvement").filter { it.text("sourcename")?.trim().equals(guid, true) }) imps.removeChild(i)
    }

    /** (Re)create the improvements the power's bonus gives at its current total rating. */
    private fun applyBonus(p: Element) {
        val guid = p.text("guid")?.trim().orEmpty()
        removeImprovements(guid)
        val bonus = p.child("bonus")
        if (bonus == null || bonus.children().isEmpty()) return
        val name = p.text("name")?.trim().orEmpty()
        val extra = p.text("extra")?.trim().orEmpty()
        val rating = p.text("rating")?.trim()?.toIntOrNull() ?: 1
        val set = ImprovementSet(Improvement.parseAll(root), isCareer = true)
        val free = set.matching("AdeptPowerFreeLevels", name).filter { it.uniqueName == extra }.sumOf { it.rating }.toInt()
        // A power with no levels (rating 0 and no free levels) has no effect yet.
        if (rating + free <= 0) return
        val result = BonusHandlers.apply(bonus, BonusContext("Power", guid, rating + free, extra, name, translate, objects = EditorObjects(file, data)))
        val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
        for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
    }
}
