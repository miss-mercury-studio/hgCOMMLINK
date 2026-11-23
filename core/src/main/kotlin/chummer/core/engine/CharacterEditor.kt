package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** The colour Chummer 5.226 gives the notes of new items (older files hold the name "Chocolate" for the same colour). */
internal const val NOTES_COLOR = "#E3813C"

/**
 * Changes to a character: adding and removing items, with the improvements those items grant kept in step.
 *
 * The edits are made on the character file's own document, in the layout Chummer writes, so the file stays
 * openable in desktop Chummer and the sheet can be rebuilt from it after every change.
 */
class CharacterEditor(
    val file: CharacterFile,
    private val data: GameData,
    private val translate: (String) -> String? = { null },
    /** Needed for buying attributes and skills (their limits and the stored derived values come from the rules). */
    private val settings: CharacterSettings? = null,
) {
    private val doc get() = file.doc
    private val root get() = file.root

    private fun element(tag: String, text: String? = null): Element =
        doc.createElement(tag).also { if (text != null) it.textContent = text }

    private fun container(tag: String): Element =
        root.child(tag) ?: element(tag).also { root.appendChild(it) }

    private fun entryText(entry: Element, tag: String): String? = entry.child(tag)?.textContent?.trim()

    private fun entryFlag(entry: Element, tag: String, default: Boolean): String =
        (entryText(entry, tag)?.equals("True", true) ?: default).let { if (it) "True" else "False" }

    /**
     * Add a quality from the game data. [extra] is the user's choice for qualities that ask for one (a skill, a
     * text), [levels] the level for qualities that have several. [qualitySource] is "Selected" for a quality the
     * user bought, or "Metatype" for a free one the metatype grants (those do not count toward the quality limit).
     * Returns the new item's GUID.
     */
    fun addQuality(entryId: String, extra: String = "", levels: Int = 1, qualitySource: String = "Selected", karmaOverride: Int? = null, addedBy: String = ""): String {
        val entry = data[entryId]?.element ?: error("No quality with id $entryId in the game data")
        val guid = UUID.randomUUID().toString()
        val name = entryText(entry, "name").orEmpty()

        val q = element("quality")
        fun add(tag: String, text: String? = null) = q.appendChild(element(tag, text))
        add("sourceid", entryId.lowercase())
        add("guid", guid)
        add("name", name)
        add("extra", extra)
        add("bp", (karmaOverride?.toString() ?: entryText(entry, "karma")) ?: "0")
        add("implemented", "True")
        add("contributetobp", entryFlag(entry, "contributetobp", true))
        add("contributetolimit", if (qualitySource != "Selected") "False" else entryFlag(entry, "contributetolimit", true))
        add("stagedpurchase", "False")
        add("doublecareer", entryFlag(entry, "doublecareer", true))
        add("canbuywithspellpoints", entryFlag(entry, "canbuywithspellpoints", false))
        add("metagenic", entryFlag(entry, "metagenic", false))
        add("print", entryFlag(entry, "print", true))
        add("qualitytype", entryText(entry, "category") ?: "Positive")
        add("qualitysource", qualitySource)
        add("mutant", entryFlag(entry, "mutant", false))
        add("source", entryText(entry, "source").orEmpty())
        add("page", entryText(entry, "page").orEmpty())
        add("sourcename", addedBy)
        for (tag in listOf("bonus", "firstlevelbonus", "naturalweapons")) {
            val node = entry.child(tag)
            q.appendChild(if (node != null) doc.importNode(node, true) else element(tag))
        }
        add("notes")
        add("notesColor", NOTES_COLOR)
        // The bonus is applied before the quality is listed, so qualities it adds (Glamour adds Distinctive Style) come first.
        val ctx = BonusContext("Quality", guid, levels, extra, name, translate, objects = EditorObjects(file, data), addQuality = { n, x ->
            val other = (data as? chummer.core.DataPack)?.byId?.entries?.firstOrNull {
                it.value.file == "qualities.xml" && it.value.element.tagName == "quality" && it.value.element.child("name")?.textContent?.trim() == n
            }?.key
            other?.let { addQuality(it, x, qualitySource = "Improvement", karmaOverride = 0, addedBy = name) }
        })
        grantBonus(entry.child("bonus"), ctx)
        container("qualities").appendChild(q)
        return guid
    }

    /**
     * Why a quality cannot be added to a Karma-build character in creation, or null if it can: Chummer does not allow
     * positive or negative qualities beyond the settings' karma limit. Other prerequisites are not checked yet.
     */
    fun qualityLimitProblem(entryId: String): String? {
        val cfg = settings ?: return null
        if (root.text("created")?.trim().equals("True", true) || root.text("buildmethod")?.trim() != "Karma") return null
        val entry = data[entryId]?.element ?: return null
        val karma = (entry.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0) * (cfg.karmaCost["karmaquality"] ?: 1)
        val ledger = KarmaLedger.of(file, cfg, data)
        return if (entry.child("category")?.textContent?.trim().equals("Negative", true)) {
            if (ledger.negativeQualities + karma > ledger.qualityLimit) "Negative qualities would be ${ledger.negativeQualities + karma}/${ledger.qualityLimit} karma" else null
        } else {
            if (ledger.positiveQualities + karma > ledger.qualityLimit) "Positive qualities would be ${ledger.positiveQualities + karma}/${ledger.qualityLimit} karma" else null
        }
    }

    /**
     * Why a quality's `required` and `forbidden` blocks (and its chargen-only, career-only and priority-only flags) rule it out
     * for this character, or null if they do not. Item kinds [Prerequisites] does not read never refuse.
     */
    fun qualityRequirementProblem(entryId: String): String? {
        val entry = data[entryId]?.element ?: return null
        val result = Prerequisites.check(entry, file, data, settings?.toRuleSettings() ?: RuleSettings())
        return result.problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Run a purchase step ([buy], one point of an attribute, skill or group) for a character in creation and take it back with
     * [undo] if it leaves the karma overspent when the step before it did not: Chummer's creation screen does not let a purchase
     * go past the karma left. Returns false when the step failed or was taken back. Characters in career, or with no settings,
     * are not limited here (career purchases check their own price).
     */
    fun withinKarma(buy: () -> Boolean, undo: () -> Unit): Boolean {
        val cfg = settings
        val limited = cfg != null && !root.text("created")?.trim().equals("True", true)
        val before = if (limited) KarmaLedger.of(file, cfg!!, data).remaining else 0
        if (!buy()) return false
        if (!limited) return true
        val after = KarmaLedger.of(file, cfg!!, data).remaining
        if (after < 0 && after < before) { undo(); return false }
        return true
    }

    private fun attributeRow(name: String): Element? =
        root.child("attributes")?.children("attribute")?.firstOrNull { it.text("name")?.trim() == name }

    /**
     * Buy ([delta] = 1) or give back ([delta] = -1) one point of an attribute with karma, as in Chummer's creation
     * screen. Fails (returns false) at the metatype maximum, or when no karma point is left to give back. The row's
     * stored total and the stored condition monitor values are updated, as Chummer does on saving.
     */
    fun changeAttribute(name: String, delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val rules = (settings ?: error("Settings are needed to change attributes")).toRuleSettings()
        val row = attributeRow(name) ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        val base = row.text("base")?.trim()?.toIntOrNull() ?: 0
        val now = AttributeEngine.forFile(file, rules)[name] ?: return false
        if (delta > 0 && now.value >= now.totalMaximum) return false
        val points = PointsLedger.of(file, data)
        val pool = points?.let { if (name in PointsLedger.SPECIAL) it.special else if (name in PointsLedger.STANDARD) it.attributes else null }
        if (!stepRow(row, delta, base, karma, pool)) return false
        AttributeEngine.forFile(file, rules)[name]?.let { row.child("totalvalue")?.textContent = it.totalValue.toString() }
        refreshCalculatedValues()
        return true
    }

    /** A skill row's name: files from Chummer 5.226 store it, older ones only the id of the skill in the data. */
    private fun rowName(row: Element): String? =
        row.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: data[row.text("suid")?.trim().orEmpty()]?.element?.child("name")?.textContent?.trim()

    /** Buy or give back one rating point of an active skill with karma. Fails at the creation maximum or with no karma point to give back. */
    fun changeSkill(name: String, delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val rules = (settings ?: error("Settings are needed to change skills")).toRuleSettings()
        val row = root.child("newskills")?.child("skills")?.children("skill")
            ?.firstOrNull { rowName(it) == name && it.text("isknowledge")?.trim() != "True" } ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        val base = row.text("base")?.trim()?.toIntOrNull() ?: 0
        if (delta > 0 && base + karma >= rules.maxSkillRatingCreate) return false
        return stepRow(row, delta, base, karma, PointsLedger.of(file, data, settings = settings)?.skills)
    }

    /**
     * One step on a row that can be paid with free points (`base`) or karma: a raise uses a free point while the pool has one and
     * karma after that; giving back takes karma first, then a free point. Without a pool (the Karma build) everything is karma.
     */
    private fun stepRow(row: Element, delta: Int, base: Int, karma: Int, pool: Pool?): Boolean {
        if (delta > 0) {
            if (pool != null && pool.left > 0) row.child("base")!!.textContent = (base + 1).toString()
            else row.child("karma")!!.textContent = (karma + 1).toString()
        } else when {
            karma > 0 -> row.child("karma")!!.textContent = (karma - 1).toString()
            pool != null && base > 0 -> row.child("base")!!.textContent = (base - 1).toString()
            else -> return false
        }
        return true
    }

    /**
     * Buy or give back one step of a skill group with karma. Raising a group raises every member skill by one, so it stops
     * when a member would pass the creation maximum; giving back needs a karma step in the group.
     */
    fun changeSkillGroup(name: String, delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val rules = (settings ?: error("Settings are needed to change skill groups")).toRuleSettings()
        val groups = root.child("newskills")?.child("groups") ?: return false
        val row = groups.children("group").firstOrNull { it.text("name")?.trim() == name } ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        val base = row.text("base")?.trim()?.toIntOrNull() ?: 0
        if (delta > 0) {
            val members = root.child("newskills")!!.child("skills")!!.children("skill").filter {
                data[it.text("suid")?.trim().orEmpty()]?.element?.child("skillgroup")?.textContent?.trim() == name
            }
            val highest = members.maxOfOrNull { m -> (m.text("base")?.trim()?.toIntOrNull() ?: 0) + (m.text("karma")?.trim()?.toIntOrNull() ?: 0) + karma + base } ?: (karma + base)
            if (highest + 1 > rules.maxSkillRatingCreate) return false
        }
        return stepRow(row, delta, base, karma, PointsLedger.of(file, data)?.groups)
    }

    private fun knowledgeRow(guid: String): Element? =
        root.child("newskills")?.child("knoskills")?.children("skill")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /**
     * Add a knowledge skill or language from the game data at rating 0. [type] is the kind the user picked (Academic,
     * Interest, Language, Professional or Street); it defaults to the data's category. A native language costs nothing.
     * Returns the new skill's GUID.
     */
    fun addKnowledgeSkill(entryId: String, type: String? = null, native: Boolean = false): String {
        val entry = data[entryId]?.element ?: error("No knowledge skill with id $entryId in the game data")
        val guid = UUID.randomUUID().toString()
        val kind = type ?: entry.child("category")?.textContent?.trim().orEmpty()
        val skill = element("skill")
        fun add(tag: String, text: String? = null) = skill.appendChild(element(tag, text))
        add("guid", guid)
        add("suid", entryId.lowercase())
        add("isknowledge", "True")
        add("skillcategory", kind)
        add("requiresgroundmovement", "False")
        add("requiresswimmovement", "False")
        add("requiresflymovement", "False")
        add("karma", "0")
        add("base", "0")
        add("notes")
        add("notesColor", "Chocolate")
        add("name", entry.child("name")?.textContent?.trim())
        add("buywithkarma", "False")
        add("type", kind)
        add("isnativelanguage", if (native) "True" else "False")
        val newSkills = root.child("newskills") ?: error("The character has no skills section")
        val list = newSkills.child("knoskills") ?: element("knoskills").also { newSkills.appendChild(it) }
        list.appendChild(skill)
        return guid
    }

    /**
     * Buy ([delta] = 1) or give back (-1) a rating of a knowledge skill. Buying uses the free knowledge points first and
     * then karma, unless [withKarma] is true. Giving back takes karma before points. Fails at the creation maximum, for a
     * native language, or with nothing to give back.
     */
    fun changeKnowledgeSkill(guid: String, delta: Int, withKarma: Boolean = false): Boolean {
        require(delta == 1 || delta == -1)
        val cfg = settings ?: error("Settings are needed to change knowledge skills")
        val row = knowledgeRow(guid) ?: return false
        if (row.text("isnativelanguage")?.trim() == "True") return false
        val base = row.text("base")?.trim()?.toIntOrNull() ?: 0
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        if (delta > 0) {
            if (base + karma >= cfg.toRuleSettings().maxKnowledgeSkillRatingCreate) return false
            val pointsLeft = KarmaLedger.of(file, cfg, data).knowledgePointsLeft
            if (!withKarma && pointsLeft > 0) row.child("base")!!.textContent = (base + 1).toString()
            else row.child("karma")!!.textContent = (karma + 1).toString()
        } else {
            when {
                karma > 0 -> row.child("karma")!!.textContent = (karma - 1).toString()
                base > 0 -> row.child("base")!!.textContent = (base - 1).toString()
                else -> return false
            }
        }
        return true
    }

    fun removeKnowledgeSkill(guid: String): Boolean {
        val row = knowledgeRow(guid) ?: return false
        row.parentNode.removeChild(row)
        return true
    }

    /**
     * Put one more karma point into nuyen ([delta] = 1) or take one back (-1) while the character is in creation; the budget
     * follows the settings' karma-to-nuyen expression and `<nuyen>` is brought up to date. Stops at the settings' maximum.
     */
    fun changeNuyenBp(delta: Int): Boolean {
        require(delta == 1 || delta == -1)
        val cfg = settings ?: error("Settings are needed to change the nuyen budget")
        val now = root.text("nuyenbp")?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        val max = if (cfg.bool("unrestrictednuyen")) Int.MAX_VALUE else cfg.int("nuyenmaxbp", 10)
        val next = now + delta
        if (next < 0 || next > max) return false
        root.child("nuyenbp")?.textContent = next.toString()
        NuyenLedger.sync(file, cfg, data)
        return true
    }

    /** Adept powers: see [PowerEditor]. */
    fun addPower(entryId: String, rating: Int = 1, extra: String = ""): String = PowerEditor(file, data, settings, translate).add(entryId, rating, extra)
    fun changePowerLevel(guid: String, delta: Int): Boolean {
        val p = root.child("powers")?.children("power")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        return PowerEditor(file, data, settings, translate).setRating(guid, (p.text("rating")?.trim()?.toIntOrNull() ?: 0) + delta)
    }
    fun removePower(guid: String): Boolean = PowerEditor(file, data, settings, translate).remove(guid)
    fun powerProblem(entryId: String, extra: String = ""): String? = PowerEditor(file, data, settings, translate).problem(entryId, extra)

    /** Rewrite the values Chummer keeps beside the character (condition monitor boxes and thresholds). */
    private fun refreshCalculatedValues() {
        val cfg = settings ?: return
        val set = ImprovementSet(Improvement.parseAll(root), isCareer = false)
        val attrs = AttributeEngine.forSet(file, set, cfg.toRuleSettings())
        val d = DerivedStats(set, attrs, 6.0, cfg)
        val values = mapOf(
            "physicalcm" to d.physicalCM, "physicalcmthresholdoffset" to d.physicalCMThresholdOffset,
            "physicalcmoverflow" to d.cmOverflow, "stuncm" to d.stunCM, "stuncmthresholdoffset" to d.stunCMThresholdOffset,
        )
        val cv = root.child("calculatedvalues") ?: return
        for ((tag, v) in values) cv.child(tag)?.textContent = v.toString()
    }

    /** Remove the quality or spell with this GUID and every improvement it granted. Returns false if there is none. */
    fun removeItem(guid: String): Boolean {
        val item = (root.children("qualities") + root.children("spells") + root.children("complexforms")).flatMap { it.children() }
            .firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        item.parentNode.removeChild(item)
        removeImprovementsOf(guid)
        return true
    }

    /** Add a spell, ritual or preparation from the game data. Returns the new item's GUID. */
    fun addSpell(entryId: String, extra: String = "", alchemical: Boolean = false): String {
        val entry = data[entryId]?.element ?: error("No spell with id $entryId in the game data")
        val guid = UUID.randomUUID().toString()
        val name = entryText(entry, "name").orEmpty()
        val spell = element("spell")
        fun add(tag: String, text: String? = null) = spell.appendChild(element(tag, text))
        add("sourceid", entryId.lowercase())
        add("guid", guid)
        add("name", name)
        add("descriptors", entryText(entry, "descriptor"))
        add("category", entryText(entry, "category"))
        add("type", entryText(entry, "type"))
        add("range", entryText(entry, "range"))
        add("damage", entryText(entry, "damage"))
        add("duration", entryText(entry, "duration"))
        add("dv", entryText(entry, "dv"))
        add("useskill", entryText(entry, "useskill"))
        add("limited", "False")
        add("extended", "False")
        add("customextended", "False")
        add("alchemical", if (alchemical) "True" else "False")
        add("source", entryText(entry, "source"))
        add("page", entryText(entry, "page"))
        add("extra", extra)
        add("notes")
        add("notesColor", NOTES_COLOR)
        add("freebonus", "False")
        add("barehandedadept", "False")
        add("improvementsource", "Spell")
        add("grade", "0")
        grantBonus(entry.child("bonus"), BonusContext("Spell", guid, 1, extra, name, translate, objects = EditorObjects(file, data)))
        container("spells").appendChild(spell)
        return guid
    }

    /** Give an active skill a specialization in creation (one per skill, on a skill with a rating). Returns its GUID, or null. */
    fun addSpecialization(skillGuid: String, name: String, hasRating: Boolean): String? = SkillSpecEditor(file, data).add(skillGuid, name, hasRating)
    fun removeSpecialization(specGuid: String): Boolean = SkillSpecEditor(file, data).remove(specGuid)

    /** Add a complex form from the game data, in the layout Chummer saves. Returns the new item's GUID. */
    fun addComplexForm(entryId: String, extra: String = ""): String {
        val entry = data[entryId]?.element ?: error("No complex form with id $entryId in the game data")
        require(entry.tagName == "complexform") { "Not a complex form" }
        val guid = UUID.randomUUID().toString()
        val name = entryText(entry, "name").orEmpty()
        val form = element("complexform")
        fun add(tag: String, text: String? = null) = form.appendChild(element(tag, text))
        add("guid", guid); add("name", name); add("target", entryText(entry, "target")); add("duration", entryText(entry, "duration"))
        add("fv", entryText(entry, "fv")); add("extra", extra); add("source", entryText(entry, "source")); add("page", entryText(entry, "page"))
        add("notes"); add("grade", "0")
        grantBonus(entry.child("bonus"), BonusContext("ComplexForm", guid, 1, extra, name, translate, objects = EditorObjects(file, data)))
        container("complexforms").appendChild(form)
        return guid
    }

    /** What a metatype's own bonus does for a new character: its improvements, and the flags and objects they bring (the magic switch, the critter tab, natural weapons). */
    fun grantMetatypeBonus(bonus: Element?, sourceName: String, friendly: String = sourceName) =
        grantBonus(bonus, BonusContext("Metatype", sourceName, 1, "", friendly, translate, objects = EditorObjects(file, data)))

    /** A power the metatype gives (Shapeshifters' Shift, a free spirit's powers): added to the critter powers free of karma. */
    fun addMetatypePower(name: String, rating: Int = 0, extra: String = ""): String? {
        val id = (data as? chummer.core.DataPack)?.byId?.entries?.firstOrNull { it.value.file == "critterpowers.xml" && it.value.element.tagName == "power" && it.value.name == name }?.key ?: return null
        return CritterPowerEditor(file, data).add(id, rating, extra, given = true)
    }

    private fun grantBonus(bonus: Element?, ctx: BonusContext) {
        if (bonus == null || bonus.children().isEmpty()) return
        val result = BonusHandlers.apply(bonus, ctx)
        val list = container("improvements")
        for (imp in result.improvements) list.appendChild(ImprovementWriter.toElement(doc, imp))
        applyEffects(result.improvements)
    }

    private fun setFlag(tag: String) { root.child(tag)?.textContent = "True" }

    /** Things a bonus changes besides improvements: which magic or resonance features are switched on, and the skills they unlock. */
    private fun applyEffects(improvements: List<Improvement>) {
        for (i in improvements) {
            when {
                i.type == "Attribute" && i.uniqueName == "enableattribute" -> when (i.improvedName) {
                    "MAG" -> { setFlag("magenabled"); markEssenceAtSpecialStart() }
                    "RES" -> { setFlag("resenabled"); markEssenceAtSpecialStart() }
                    "DEP" -> setFlag("depenabled")
                }
                i.type == "SpecialTab" -> when (i.improvedName) {
                    "Magician" -> setFlag("magician")
                    "Adept" -> setFlag("adept")
                    "Technomancer" -> setFlag("technomancer")
                    "Critter" -> setFlag("critter")
                }
                i.type == "SpecialSkills" && i.improvedName == "Magician" -> addMagicalSkills()
            }
        }
    }

    /** Chummer remembers the essence at the moment magic or resonance was switched on (it is unset until then). */
    private fun markEssenceAtSpecialStart() {
        val e = root.child("essenceatspecialstart") ?: return
        if (!e.textContent.trim().startsWith("-7922")) return
        val rules = settings?.toRuleSettings() ?: RuleSettings()
        val set = ImprovementSet(Improvement.parseAll(root), isCareer = false)
        val ess = EssenceEngine(set, data, true, rules.essenceDecimals, rules.dontRoundEssenceInternally).essence(6.0, file.augmentations())
        e.textContent = if (ess == Math.rint(ess)) ess.toLong().toString() else ess.toString()
    }

    /** The magical active skills (and their skill groups) a Magician gets, inserted in name order. */
    private fun addMagicalSkills() {
        val pack = data as? chummer.core.DataPack ?: return
        val ns = root.child("newskills") ?: return
        val skillList = ns.child("skills") ?: return
        val groupList = ns.child("groups") ?: return
        val have = skillList.children("skill").mapNotNull { it.text("suid")?.trim()?.lowercase() }.toSet()
        val wanted = pack.byId.values
            .filter { it.file == "skills.xml" && it.element.tagName == "skill" && (it.element.parentNode as? Element)?.tagName == "skills" }
            .map { it.element }
            .filter { it.child("category")?.textContent?.trim() == "Magical Active" }
            .filter { it.child("exotic")?.textContent?.trim()?.equals("True", true) != true }
            .filter { it.child("id")?.textContent?.trim()?.lowercase() !in have }
            .sortedBy { it.child("name")?.textContent?.trim()?.lowercase().orEmpty() }
        fun insertSorted(list: Element, item: Element, name: String) {
            val before = list.children().firstOrNull { (it.text("name")?.trim()?.lowercase().orEmpty()) > name.lowercase() }
            if (before != null) list.insertBefore(item, before) else list.appendChild(item)
        }
        for (s in wanted) insertSorted(skillList, SkillElements.skill(doc, s), s.child("name")?.textContent?.trim().orEmpty())
        val haveGroups = groupList.children("group").mapNotNull { it.text("name")?.trim() }.toSet()
        wanted.mapNotNull { it.child("skillgroup")?.textContent?.trim()?.ifEmpty { null } }.distinct()
            .filter { it !in haveGroups }.forEach { g -> insertSorted(groupList, SkillElements.group(doc, g), g) }
    }

    /**
     * Choose the character's magical tradition (or, with [type] "RES", technomancer stream) from the game data. Any earlier
     * choice is replaced. Returns the new GUID.
     */
    fun setTradition(entryId: String, type: String = "MAG"): String {
        val entry = data[entryId]?.element ?: error("No tradition with id $entryId in the game data")
        val guid = UUID.randomUUID().toString()
        val t = element("tradition")
        fun add(tag: String, text: String? = null) = t.appendChild(element(tag, text))
        add("sourceid", entryId.lowercase())
        add("guid", guid)
        add("traditiontype", type)
        add("name", entryText(entry, "name"))
        add("extra")
        add("spiritform", "Materialization")
        add("drain", entryText(entry, "drain"))
        add("source", entryText(entry, "source"))
        add("page", entryText(entry, "page"))
        val spirits = entry.child("spirits")
        for (tag in listOf("spiritcombat", "spiritdetection", "spirithealth", "spiritillusion", "spiritmanipulation")) add(tag, spirits?.child(tag)?.textContent?.trim())
        add("spirits")
        add("bonus")
        root.child("tradition")?.let { root.removeChild(it) }
        val anchor = root.child("totaless")
        if (anchor != null) root.insertBefore(t, anchor.nextSibling) else root.appendChild(t)
        return guid
    }

    private fun removeImprovementsOf(guid: String) {
        val list = root.child("improvements") ?: return
        // A Mentor Spirit quality brought a mentor spirit with it (the marker improvement holds its GUID): the spirit and what it made go too.
        val mentors = list.children("improvement").filter { it.text("sourcename")?.trim().equals(guid, true) && it.text("improvementttype")?.trim() == "MentorSpirit" }
            .mapNotNull { it.text("improvedname")?.trim()?.takeIf { g -> g.isNotEmpty() } }
        for (m in mentors) {
            root.child("mentorspirits")?.children("mentorspirit")?.firstOrNull { it.text("guid")?.trim().equals(m, true) }?.let { it.parentNode.removeChild(it) }
            for (i in list.children("improvement")) if (i.text("sourcename")?.trim().equals(m, true) && i.text("improvementsource")?.trim() == "MentorSpirit") list.removeChild(i)
        }
        for (i in list.children("improvement")) {
            if (i.text("sourcename")?.trim().equals(guid, true)) list.removeChild(i)
        }
    }
}

/** Writes an improvement in the element layout Chummer uses in `.chum5` files. */
object ImprovementWriter {
    private fun number(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    fun toElement(doc: org.w3c.dom.Document, i: Improvement): Element {
        val e = doc.createElement("improvement")
        fun add(tag: String, text: String = "") { e.appendChild(doc.createElement(tag).also { if (text.isNotEmpty()) it.textContent = text }) }
        // Chummer leaves the element out when there is no unique name.
        if (i.uniqueName.isNotEmpty()) add("unique", i.uniqueName)
        add("target", i.target)
        add("improvedname", i.improvedName)
        add("sourcename", i.sourceName)
        add("min", number(i.minimum))
        add("max", number(i.maximum))
        add("aug", number(i.augmented))
        add("augmax", number(i.augmentedMaximum))
        add("val", number(i.value))
        add("rating", number(i.rating))
        add("exclude", i.exclude)
        add("condition", i.condition)
        add("improvementttype", i.type)
        add("improvementsource", i.source)
        add("custom", if (i.custom) "True" else "False")
        add("customname")
        add("customid")
        add("customgroup", i.customGroup)
        add("addtorating", if (i.addToRating) "1" else "0")
        add("enabled", if (i.enabled) "1" else "0")
        add("order", "0")
        add("notes")
        add("notesColor", NOTES_COLOR)
        return e
    }
}

/** A quality that can be added, with what the editor can and cannot do with it yet. */
data class QualityOption(
    val id: String,
    val name: String,
    val category: String,
    val karma: Int,
    /** The quality asks the user to choose or type something (a name, a skill, a mentor spirit, a tradition). */
    val needsText: Boolean,
    /** What the user can choose from, when the data says (mentor spirits, skills, traditions, the qualities a node offers); empty means free text. */
    val choices: List<String> = emptyList(),
    /** Bonus node types with no handler yet; the quality can still be added, but those bonuses will be missing. */
    val unsupported: List<String>,
)

object QualityCatalog {
    /**
     * Every quality in the game data. Prerequisites (`required` and `forbidden`) and karma limits are not
     * enforced yet, so this lists them all.
     */
    fun list(data: chummer.core.DataPack): List<QualityOption> =
        data.byId.entries.filter { it.value.file == "qualities.xml" && it.value.element.tagName == "quality" }.map { (id, e) ->
            val el = e.element
            val bonusNodes = el.child("bonus")?.children().orEmpty().map { it.tagName.lowercase() }
            val choices = choicesFor(data, el.child("bonus"))
            QualityOption(
                id = id, name = el.child("name")?.textContent?.trim().orEmpty(),
                category = el.child("category")?.textContent?.trim().orEmpty(),
                karma = el.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0,
                needsText = bonusNodes.any { it in CHOICE_NODES },
                choices = choices,
                unsupported = bonusNodes.filter { !BonusHandlers.knows(it) },
            )
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))

    /** Bonus nodes that need something from the user. */
    internal val CHOICE_NODES = setOf(
        "selecttext", "selectskill", "selectweapon", "selectarmor", "selecttradition", "selectmentorspirit", "selectquality",
        "selectattributes", "hardwires", "selectrestricted",
    )

    private fun names(data: chummer.core.DataPack, file: String, tag: String, inParent: String? = null): List<String> =
        data.byId.values.filter { it.file == file && it.element.tagName == tag && (inParent == null || (it.element.parentNode as? Element)?.tagName == inParent) }
            .mapNotNull { it.name }.distinct().sortedBy { it.lowercase() }

    /** The list to choose from for the first choice node of a bonus, or empty when the user has to type it. */
    internal fun choicesFor(data: chummer.core.DataPack, bonus: Element?): List<String> {
        val node = bonus?.children().orEmpty().firstOrNull { it.tagName.lowercase() in CHOICE_NODES } ?: return emptyList()
        return when (node.tagName.lowercase()) {
            "selectmentorspirit" -> names(data, "mentors.xml", "mentor")
            "selecttradition" -> TraditionCatalog.list(data).map { it.name }.distinct().sorted()
            "selectskill", "hardwires" -> names(data, "skills.xml", "skill", "skills")
            "selectquality" -> node.children("quality").map { it.textContent.trim() }
            "selectattributes" -> listOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG", "MAG", "RES")
            else -> emptyList()
        }
    }
}

/** A knowledge skill or language that can be added. */
data class KnowledgeOption(val id: String, val name: String, val category: String)

object KnowledgeCatalog {
    fun list(data: chummer.core.DataPack): List<KnowledgeOption> =
        data.byId.entries.filter { it.value.file == "skills.xml" && (it.value.element.parentNode as? Element)?.tagName == "knowledgeskills" }.map { (id, e) ->
            KnowledgeOption(id, e.element.child("name")?.textContent?.trim().orEmpty(), e.element.child("category")?.textContent?.trim().orEmpty())
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
}

/** The XML Chummer writes for a skill and a skill group, shared by new characters and unlocked skills. */
internal object SkillElements {
    fun skill(doc: org.w3c.dom.Document, data: Element): Element {
        fun el(tag: String, text: String? = null) = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        fun flag(tag: String) = if (data.child(tag)?.textContent?.trim().equals("True", true)) "True" else "False"
        return el("skill").also { e ->
            e.appendChild(el("guid", UUID.randomUUID().toString()))
            e.appendChild(el("suid", data.child("id")?.textContent?.trim()))
            e.appendChild(el("isknowledge", "False"))
            e.appendChild(el("skillcategory", data.child("category")?.textContent?.trim()))
            e.appendChild(el("requiresgroundmovement", flag("requiresgroundmovement")))
            e.appendChild(el("requiresswimmovement", flag("requiresswimmovement")))
            e.appendChild(el("requiresflymovement", flag("requiresflymovement")))
            e.appendChild(el("karma", "0"))
            e.appendChild(el("base", "0"))
            e.appendChild(el("notes"))
            e.appendChild(el("notesColor", NOTES_COLOR))
            e.appendChild(el("name", data.child("name")?.textContent?.trim()))
            e.appendChild(el("buywithkarma", "False"))
        }
    }

    fun group(doc: org.w3c.dom.Document, name: String): Element {
        fun el(tag: String, text: String? = null) = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }
        return el("group").also { e ->
            e.appendChild(el("karma", "0"))
            e.appendChild(el("base", "0"))
            e.appendChild(el("isbroken", "False"))
            e.appendChild(el("id", UUID.randomUUID().toString()))
            e.appendChild(el("name", name))
        }
    }
}

/** A spell, ritual or preparation that can be added. */
data class SpellOption(val id: String, val name: String, val category: String, val type: String, val range: String, val dv: String, val descriptors: String)

object SpellCatalog {
    fun list(data: chummer.core.DataPack): List<SpellOption> =
        data.byId.entries.filter { it.value.file == "spells.xml" && it.value.element.tagName == "spell" }.map { (id, e) ->
            fun t(tag: String) = e.element.child(tag)?.textContent?.trim().orEmpty()
            SpellOption(id, t("name"), t("category"), t("type"), t("range"), t("dv"), t("descriptor"))
        }.sortedWith(compareBy({ it.category }, { it.name.lowercase() }))
}

/** A magical tradition that can be chosen. */
data class TraditionOption(val id: String, val name: String, val drain: String)

object TraditionCatalog {
    fun list(data: chummer.core.DataPack): List<TraditionOption> =
        data.byId.entries.filter { it.value.file == "traditions.xml" && it.value.element.tagName == "tradition" }.map { (id, e) ->
            TraditionOption(id, e.element.child("name")?.textContent?.trim().orEmpty(), e.element.child("drain")?.textContent?.trim().orEmpty())
        }.sortedBy { it.name.lowercase() }
}
