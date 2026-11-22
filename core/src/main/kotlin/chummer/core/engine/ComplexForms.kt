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
data class ComplexFormSection(
    val forms: List<ComplexFormLine>, val free: Int, val limit: Int, val price: Int,
    /** Sprites, the highest level one can have and how many may be registered. */
    val sprites: List<SpriteLine> = emptyList(), val spriteMax: Int = 0, val spriteLimit: Int = 0,
)

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
            sprites = data?.let { SpriteEditor(c, it, settings).sprites() }.orEmpty(), spriteMax = data?.let { SpriteEditor(c, it, settings).maxLevel() } ?: 0,
            spriteLimit = data?.let { SpriteEditor(c, it, settings).registeredLimit() } ?: 0,
        )
    }
}

/** A sprite for a picker. */
data class SpriteOption(val id: String, val name: String, val source: String)

/** A sprite on the character. */
data class SpriteLine(val guid: String, val name: String, val critterName: String, val force: Int, val services: Int, val bound: Boolean)

/**
 * Sprites, kept in the `spirits` list with type `Sprite` as Chummer saves them. In creation every sprite is registered and at
 * most the character's Charisma (the `registeredspriteexpression` setting) may be; its level is at most Resonance (twice that in career).
 */
open class SpriteEditor(protected val file: CharacterFile, protected val data: GameData, protected val settings: CharacterSettings, protected val kind: String = "Sprite") {
    protected val root get() = file.root
    protected val created get() = root.text("created")?.trim().equals("True", true)

    /** The sprites the character's stream offers (all of them when no stream is chosen). */
    open fun options(): List<SpriteOption> {
        val pack = data as? DataPack ?: return emptyList()
        val allowed = root.child("tradition")?.child("spirits")?.children("spirit").orEmpty().map { it.textContent.trim() }.toSet()
        return pack.byId.entries.filter { it.value.file == "critters.xml" && it.value.element.tagName == "metatype" && it.value.element.text("category")?.trim() == "Sprites" }
            .map { (id, e) -> SpriteOption(id, e.element.text("name")?.trim().orEmpty(), e.element.text("source")?.trim().orEmpty()) }
            .filter { allowed.isEmpty() || it.name in allowed }.sortedBy { it.name.lowercase() }
    }

    protected fun attribute(abbrev: String) = AttributeEngine.forFile(file, settings.toRuleSettings())[abbrev]?.totalValue ?: 0

    /** The highest level a sprite can have: Resonance in creation, twice that once the character is created. */
    open fun maxLevel(): Int = if (root.text("resenabled")?.trim().equals("True", true)) attribute("RES").let { if (created) it * 2 else it } else 0

    /** How many registered sprites the character may have (Chummer's `RegisteredSpriteLimit`, Charisma by default). */
    open fun registeredLimit(): Int = standardRound(settings.evaluate("registeredspriteexpression", mapOf("CHA" to attribute("CHA").toDouble(), "LOG" to attribute("LOG").toDouble()), "{CHA}"))

    fun sprites(): List<SpriteLine> = root.child("spirits")?.children("spirit").orEmpty().filter { it.text("type")?.trim() == kind }.map {
        SpriteLine(
            it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("crittername").orEmpty().trim(),
            it.text("force")?.trim()?.toIntOrNull() ?: 0, it.text("services")?.trim()?.toIntOrNull() ?: 0, it.text("bound")?.trim().equals("True", true),
        )
    }

    /** Whether a conjured thing of this name counts against the bound or registered limit (watchers and homunculi do not). */
    protected open fun countsAgainst(name: String) = true

    private fun registered() = sprites().count { it.bound && countsAgainst(it.name) }

    /** Add a sprite. In creation it is registered; the level defaults to the highest allowed. Null when the limit or level forbids it. */
    fun add(entryId: String, critterName: String = "", force: Int = maxLevel(), services: Int = 1): String? {
        val entry = data[entryId]?.element ?: return null
        val name = entry.child("name")?.textContent?.trim().orEmpty()
        val atLimit = countsAgainst(name) && registered() >= registeredLimit()
        // A sprite is registered (the limit stops it); a spirit is bound unless the limit is reached, and is then added unbound.
        val bound = if (kind == "Sprite") !created || services > 0 else !atLimit
        if (force < 1 || force > maxLevel() || (kind == "Sprite" && bound && atLimit)) return null
        val guid = java.util.UUID.randomUUID().toString()
        val doc = file.doc
        fun el(tag: String, text: String? = null) = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        val s = el("spirit")
        for ((tag, v) in listOf(
            "guid" to guid, "name" to entry.child("name")?.textContent?.trim().orEmpty(), "crittername" to critterName, "services" to services.toString(),
            "force" to force.toString(), "bound" to if (bound) "True" else "False", "fettered" to "False", "type" to kind,
            "file" to "", "relative" to "", "notes" to "", "mainmugshotindex" to "-1", "mugshots" to "",
        )) s.appendChild(el(tag, v))
        (root.child("spirits") ?: el("spirits").also { root.appendChild(it) }).appendChild(s)
        return guid
    }

    /**
     * Change a sprite: its name, level (1 up to the highest allowed), tasks owed, and whether it is registered. In creation every sprite stays
     * registered. Registering one more than the limit allows is refused. False when nothing was changed.
     */
    fun update(guid: String, critterName: String? = null, force: Int? = null, services: Int? = null, bound: Boolean? = null): Boolean {
        val s = root.child("spirits")?.children("spirit")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) && it.text("type")?.trim() == kind } ?: return false
        if (force != null && (force < 1 || force > maxLevel())) return false
        if (services != null && services < 0) return false
        val wasBound = s.text("bound")?.trim().equals("True", true)
        if (bound != null && bound != wasBound) {
            if (!created && !bound) return false
            if (bound && countsAgainst(s.text("name")?.trim().orEmpty()) && registered() >= registeredLimit()) return false
        }
        critterName?.let { s.child("crittername")?.textContent = it }
        force?.let { s.child("force")?.textContent = it.toString() }
        services?.let { s.child("services")?.textContent = it.toString() }
        bound?.let { s.child("bound")?.textContent = if (it) "True" else "False" }
        return true
    }

    fun remove(guid: String): Boolean {
        val s = root.child("spirits")?.children("spirit")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        s.parentNode.removeChild(s)
        return true
    }
}
