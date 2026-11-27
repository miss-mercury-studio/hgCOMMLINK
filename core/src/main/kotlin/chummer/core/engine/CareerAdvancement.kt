package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import java.time.LocalDateTime

/** Moving an installed ware item from grade [from] to the next grade [to] costs [cost] nuyen (the difference in price); [affordable] says whether the character has it. */
data class WareUpgrade(val from: String, val to: String, val cost: Double, val affordable: Boolean)

/** What can be changed on an installed ware item while the character is in creation: its rating range (0 to 0 when it has no rating), and whether its grade can be chosen. */
data class WareEditInfo(val grade: String, val rating: Int, val minRating: Int, val maxRating: Int, val gradeEditable: Boolean)

/**
 * The effect of giving an installed ware item a grade and rating: its price (with the plug-ins in it) and essence before and after, and
 * [problem] when the change is not allowed (null when it is). [newEssence] is the item's own essence; the final check uses the whole character.
 */
data class WareEditPreview(val cost: Double, val newCost: Double, val essence: Double, val newEssence: Double, val problem: String?)

/** Starts the `extra` of the log entry an upgrade writes, followed by "oldGrade:newGrade"; the log's undo puts the old grade back. */
const val WARE_GRADE_PREFIX = "WareGrade:"

/**
 * Spending karma on a finished (career) character, as Chummer's career screen does: each purchase costs karma from the
 * character's balance, is written to the expense log with its undo record, and raises the rating by one step.
 *
 * Costs follow the character's settings and the karma-cost improvements:
 *  - attribute: (value + 1) x attribute cost (first point costs the attribute cost);
 *  - active skill: new-skill price for the first rating, then (rating + 1) x improve price;
 *  - knowledge skill or language: the same with the knowledge prices;
 *  - skill group: new-group price, then (rating + 1) x improve price;
 *  - specialization: the settings' price.
 */
class CareerAdvancement(
    private val file: CharacterFile,
    private val data: GameData,
    private val settings: CharacterSettings,
) {
    companion object {
        /** The most Chummer's Sell Item box accepts, in percent. */
        const val MAX_SELL_PERCENT = 9999.99
    }

    private val root get() = file.root
    private val rules = settings.toRuleSettings()
    val log = ExpenseLog(file, data, settings)
    private fun k(key: String, default: Int) = settings.karmaCost[key] ?: default
    private val improvements get() = ImprovementSet(Improvement.parseAll(root), isCareer = true)

    /** What an upgrade costs and why it may not be possible. [cost] is null when the item is at its maximum. */
    data class Offer(val cost: Int?, val from: Int, val canAfford: Boolean) {
        val available get() = cost != null && canAfford
    }

    private fun offer(cost: Int?, from: Int) = Offer(cost, from, cost != null && cost <= log.karma)

    /** Apply the settings' karma-cost modifiers (`...KarmaCost` adds, `...KarmaCostMultiplier` scales, both in percent). */
    private fun modified(base: Int, rating: Int, vararg sources: Pair<String, String>): Int {
        var extra = 0.0
        var multiplier = 1.0
        val set = improvements
        for ((name, kind) in sources) {
            for (i in set.matching(kind, name, unconditionalOnly = false)) {
                if ((i.maximum == 0.0 || rating <= i.maximum) && i.minimum <= rating) extra += i.value
            }
            for (i in set.matching(kind + "Multiplier", name, unconditionalOnly = false)) {
                if ((i.maximum == 0.0 || rating <= i.maximum) && i.minimum <= rating) multiplier *= i.value / 100.0
            }
        }
        return if (multiplier != 1.0) standardRound(base * multiplier + extra) else base + standardRound(extra)
    }

    // ---- attributes

    fun attributeOffer(name: String): Offer {
        val now = AttributeEngine.forFile(file, rules)[name] ?: return Offer(null, 0, false)
        if (now.value >= now.totalMaximum) return Offer(null, now.value, false)
        val unit = k("karmaattribute", 5)
        val next = now.value + 1
        val base = if (now.value == 0) unit else next * unit
        val cost = maxOf(modified(base, next, name to "AttributeKarmaCost"), minOf(1, unit))
        return offer(cost, now.value)
    }

    /** Raise an attribute by one with karma. Returns false when it is at its maximum or too expensive. */
    fun improveAttribute(name: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val o = attributeOffer(name)
        if (!o.available) return false
        val row = root.child("attributes")?.children("attribute")?.firstOrNull { it.text("name")?.trim() == name } ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        row.child("karma")!!.textContent = (karma + 1).toString()
        AttributeEngine.forFile(file, rules)[name]?.let { row.child("totalvalue")?.textContent = it.totalValue.toString() }
        log.add("Karma", -o.cost!!.toDouble(), "Attribute $name ${o.from} -> ${o.from + 1}", "ImproveAttribute", objectId = name, now = now)
        return true
    }

    // ---- skills

    private fun groupKarmaOf(skillGroup: String) = file.skillGroups()[skillGroup]?.let { it.first + it.second } ?: 0

    private fun skillRow(guid: String) = file.skills().firstOrNull { it.guid.equals(guid, true) }

    /** The skill's total base rating: bought and free points plus its group's. */
    private fun totalBase(s: CharacterFile.SkillRow): Int {
        val group = if (s.isKnowledge) "" else data[s.suid]?.element?.child("skillgroup")?.textContent?.trim().orEmpty()
        return s.base + s.karma + if (group.isEmpty()) 0 else groupKarmaOf(group)
    }

    fun skillOffer(guid: String): Offer {
        val s = skillRow(guid) ?: return Offer(null, 0, false)
        val total = totalBase(s)
        val max = if (s.isKnowledge) rules.maxKnowledgeSkillRating else rules.maxSkillRating
        if (total >= max) return Offer(null, total, false)
        val newPrice = if (s.isKnowledge) k("karmanewknowledgeskill", 1) else k("karmanewactiveskill", 2)
        val improvePrice = if (s.isKnowledge) k("karmaimproveknowledgeskill", 1) else k("karmaimproveactiveskill", 2)
        val base = if (total == 0) newPrice else (total + 1) * improvePrice
        val cost = if (s.isKnowledge) modified(base, total + 1, s.name to "KnowledgeSkillKarmaCost", s.category to "SkillCategoryKarmaCost")
        else modified(base, total + 1, s.name to "ActiveSkillKarmaCost", s.category to "SkillCategoryKarmaCost")
        return offer(cost, total)
    }

    /** Raise a skill (active or knowledge, by its GUID) by one rating with karma. */
    fun improveSkill(guid: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val o = skillOffer(guid)
        if (!o.available) return false
        val s = skillRow(guid) ?: return false
        val ns = root.child("newskills") ?: return false
        val row = (ns.child("skills")?.children("skill").orEmpty() + ns.child("knoskills")?.children("skill").orEmpty())
            .first { it.text("guid")?.trim().equals(guid, true) }
        row.child("karma")!!.textContent = (s.karma + 1).toString()
        val label = if (s.isKnowledge) "Knowledge Skill" else "Skill"
        log.add("Karma", -o.cost!!.toDouble(), "$label ${s.name} ${o.from} -> ${o.from + 1}", if (o.from == 0) "AddSkill" else "ImproveSkill", objectId = s.guid, now = now)
        return true
    }

    // ---- skill groups

    fun groupOffer(name: String): Offer {
        val g = file.skillGroups()[name] ?: return Offer(null, 0, false)
        val rating = g.first + g.second
        if (rating >= rules.maxSkillRating) return Offer(null, rating, false)
        if (!SkillGroupRules.stateOf(file, data, settings, name).karmaUnbroken) return Offer(null, rating, false)
        val base = if (rating == 0) k("karmanewskillgroup", 5) else (rating + 1) * k("karmaimproveskillgroup", 5)
        return offer(modified(base, rating + 1, name to "SkillGroupKarmaCost"), rating)
    }

    fun improveGroup(name: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val o = groupOffer(name)
        if (!o.available) return false
        val row = root.child("newskills")?.child("groups")?.children("group")?.first { it.text("name")?.trim() == name }
        val karma = row?.text("karma")?.trim()?.toIntOrNull() ?: return false
        row.child("karma")!!.textContent = (karma + 1).toString()
        log.add("Karma", -o.cost!!.toDouble(), "Skill Group $name ${o.from} -> ${o.from + 1}", if (o.from == 0) "AddSkill" else "ImproveSkill", objectId = row.text("id")?.trim().orEmpty(), now = now)
        return true
    }

    // ---- specializations

    private fun specs(row: CharacterFile.SkillRow) = row.specializations

    fun specializationOffer(guid: String): Offer {
        val s = skillRow(guid) ?: return Offer(null, 0, false)
        val total = totalBase(s)
        if (total <= 0 || specs(s).isNotEmpty()) return Offer(null, total, false)
        val price = if (s.isKnowledge) k("karmaknowledgespecialization", 7) else k("karmaspecialization", 7)
        val set = improvements
        var extra = 0.0
        var multiplier = 1.0
        for (i in set.matching("SkillCategorySpecializationKarmaCost", s.category, unconditionalOnly = false)) if (i.minimum <= total) extra += i.value
        for (i in set.matching("SkillCategorySpecializationKarmaCostMultiplier", s.category, unconditionalOnly = false)) if (i.minimum <= total) multiplier *= i.value / 100.0
        val cost = if (multiplier != 1.0) standardRound(price * multiplier + extra) else price + standardRound(extra)
        return offer(cost, total)
    }

    /**
     * The specializations on offer for an active skill: the game data's own list, then the options that improvements add for it (a martial art's
     * style, kept as `SkillSpecializationOption` with the skill as the improved name and the specialization as the unique name), without repeats.
     */
    fun specializationChoices(guid: String): List<String> {
        return SkillSpecEditor(file, data).choices(guid, improvements.all)
    }

    /** Learn a specialization for a skill that has none, paying the settings' price. */
    fun addSpecialization(guid: String, name: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val text = name.trim()
        val o = specializationOffer(guid)
        if (!o.available || text.isEmpty()) return false
        val s = skillRow(guid) ?: return false
        val ns = root.child("newskills") ?: return false
        val row = (ns.child("skills")?.children("skill").orEmpty() + ns.child("knoskills")?.children("skill").orEmpty())
            .first { it.text("guid")?.trim().equals(guid, true) }
        val doc = file.doc
        val specId = java.util.UUID.randomUUID().toString()
        val spec = doc.createElement("spec")
        for ((t, v) in listOf("guid" to specId, "name" to text, "free" to "False", "expertise" to "False")) spec.appendChild(doc.createElement(t).also { it.textContent = v })
        val list = row.child("specs") ?: doc.createElement("specs").also { row.appendChild(it) }
        list.appendChild(spec)
        log.add("Karma", -o.cost!!.toDouble(), "Learned Specialization ${s.name} ($text)", "AddSpecialization", objectId = specId, now = now)
        return true
    }

    // ---- qualities

    /**
     * What a quality costs in career: its karma times the settings' quality multiplier, doubled unless the settings or the
     * quality say otherwise. Free for a metagenic quality's discount cases is not modelled. Negative qualities cost 0 (the
     * log still records them). Null for an unknown id.
     */
    fun qualityCost(entryId: String): Int? {
        val entry = data[entryId]?.element ?: return null
        val karma = entry.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0
        val negative = entry.child("category")?.textContent?.trim().equals("Negative", true)
        if (negative) return 0
        var cost = karma * k("karmaquality", 1)
        if (!settings.bool("dontdoublequalitypurchases") && entry.child("doublecareer")?.textContent?.trim() != "False") cost *= 2
        return cost
    }

    /** Buy a quality with karma; refused when the character cannot pay. Returns the new quality's GUID, or null. */
    fun addQuality(entryId: String, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val entry = data[entryId]?.element ?: return null
        val cost = qualityCost(entryId) ?: return null
        if (cost > log.karma) return null
        val guid = CharacterEditor(file, data, settings = settings).addQuality(entryId, extra)
        val name = entry.child("name")?.textContent?.trim().orEmpty() + if (extra.isBlank()) "" else " ($extra)"
        val negative = cost == 0 && entry.child("category")?.textContent?.trim().equals("Negative", true)
        log.add("Karma", -cost.toDouble(), (if (negative) "Gained Negative Quality " else "Gained Positive Quality ") + name, "AddQuality", objectId = guid, now = now)
        return guid
    }

    /** What removing a quality costs: nothing for a positive one, karma (doubled unless the settings say otherwise) for a negative one. */
    fun qualityRemovalCost(guid: String): Int? {
        val q = root.child("qualities")?.children("quality")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return null
        if (q.text("qualitytype")?.trim().equals("Positive", true)) return 0
        val bp = q.text("bp")?.trim()?.toIntOrNull() ?: 0
        var cost = -(bp * k("karmaquality", 1))
        if (!settings.bool("dontdoublequalityrefunds")) cost *= 2
        return maxOf(cost, 0)
    }

    /** Remove a quality and its improvements, paying karma when it is a negative one. False when it cannot be paid or is unknown. */
    fun removeQuality(guid: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val cost = qualityRemovalCost(guid) ?: return false
        if (cost > log.karma) return false
        val q = root.child("qualities")!!.children("quality").first { it.text("guid")?.trim().equals(guid, true) }
        val name = q.text("name")?.trim().orEmpty() + (q.text("extra")?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        val sourceId = q.text("sourceid")?.trim().orEmpty()
        val negative = !q.text("qualitytype")?.trim().equals("Positive", true)
        if (!CharacterEditor(file, data, settings = settings).removeItem(guid)) return false
        // Buying off a negative quality can be undone: the quality is re-added from the data (its source id is the entry's object).
        if (negative) log.add("Karma", -cost.toDouble(), "Removed Negative Quality $name", "RemoveQuality", objectId = sourceId, extra = q.text("extra")?.trim().orEmpty(), now = now)
        return true
    }

    // ---- spells

    /** The karma price of learning a spell, ritual or (when [alchemical]) preparation, as `Character.SpellKarmaCost`. Null for an unknown id. */
    fun spellCost(entryId: String, alchemical: Boolean = false): Int? {
        val entry = data[entryId]?.element ?: return null
        val category = if (alchemical) "Preparations" else entry.child("category")?.textContent?.trim().let { if (it == "Rituals") it else "Spells" }
        val set = improvements
        var v = k("karmaspell", 5).toDouble()
        fun applies(i: Improvement) = i.enabled && (i.improvedName == category || i.improvedName.isEmpty())
        v += set.all.filter { it.type == "NewSpellKarmaCost" && applies(it) }.sumOf { it.value * it.rating }
        var multiplier = 1.0
        set.all.filter { it.type == "NewSpellKarmaCostMultiplier" && applies(it) }.forEach { multiplier *= it.value / 100.0 }
        return maxOf(standardRound(v * multiplier), 0)
    }

    fun addSpell(entryId: String, now: LocalDateTime = LocalDateTime.now(), alchemical: Boolean = false): String? {
        val cost = spellCost(entryId, alchemical) ?: return null
        if (cost > log.karma) return null
        val guid = CharacterEditor(file, data, settings = settings).addSpell(entryId, alchemical = alchemical)
        val name = data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty()
        log.add("Karma", -cost.toDouble(), "Learned Spell $name", "AddSpell", objectId = guid, now = now)
        return guid
    }

    /** The character's sprites (in creation and career alike; sprites cost no karma). */
    fun sprites() = SpriteEditor(file, data, settings)

    /** The character's summoned and bound spirits. */
    fun spirits() = SpiritEditor(file, data, settings)

    // ---- initiation and metamagics

    fun initiation() = InitiationEditor(file, data, settings)

    fun mentors() = MentorEditor(file, data)

    /**
     * Take the next initiation (submersion) grade with karma, and 10,000 nuyen more for schooling. Null when the grade passes Magic (Resonance), or
     * karma or nuyen fall short. Chummer's entries are "Initiate Grade 0 -> 1" (karma, undoable) and, for schooling, the same text in nuyen.
     */
    fun initiate(group: Boolean = false, ordeal: Boolean = false, schooling: Boolean = false, now: LocalDateTime = LocalDateTime.now()): String? {
        val ed = initiation()
        val cost = ed.nextCost(group, ordeal, schooling)
        val before = ed.grade()
        if (cost > log.karma || (schooling && ExpenseLog(file).nuyen < 10000)) return null
        val guid = ed.initiate(group, ordeal, schooling) ?: return null
        val reason = (if (ed.technomancer) "Submersion Grade" else "Initiate Grade") + " $before -> ${before + 1}"
        log.add("Karma", -cost.toDouble(), reason, "ImproveInitiateGrade", objectId = guid, now = now)
        if (schooling) log.add("Nuyen", -10000.0, reason, "ImproveInitiateGrade", objectId = guid, now = now)
        return guid
    }

    /** Add a metamagic (echo) to the latest grade. The first of a grade is free; each after costs the metamagic price. */
    fun addMetamagic(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val ed = initiation()
        val grade = ed.grade()
        val cost = if (ed.costsKarma(grade)) ed.extraMetamagicCost() else 0
        if (cost > log.karma) return null
        val guid = ed.addMetamagic(entryId, grade) ?: return null
        if (cost > 0) log.add("Karma", -cost.toDouble(), (if (ed.technomancer) "Echo " else "Metamagic ") + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddMetamagic", objectId = guid, now = now)
        return guid
    }

    /** Add an enhancement (Street Grimoire) to the latest grade for the enhancement price (2 karma). */
    fun addEnhancement(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val ed = initiation()
        val cost = ed.enhancementPrice()
        if (cost > log.karma) return null
        val guid = ed.addEnhancement(entryId) ?: return null
        log.add("Karma", -cost.toDouble(), "Enhancement " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddEnhancement", objectId = guid, now = now)
        return guid
    }

    /** Add an enchantment or ritual at the latest grade: free when nothing else came with that grade yet, else the spell price. */
    fun addGradeSpell(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val ed = initiation()
        val grade = ed.grade()
        val cost = if (ed.costsKarma(grade)) (spellCost(entryId) ?: return null) else 0
        if (cost > log.karma) return null
        val guid = ed.addGradeSpell(entryId, grade) ?: return null
        if (cost > 0) log.add("Karma", -cost.toDouble(), "Learned Spell " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddSpell", objectId = guid, now = now)
        return guid
    }

    // ---- critter powers

    fun critterPowers() = CritterPowerEditor(file, data)

    /** Buy a critter power with its karma price (Chummer's career dialog). Null when it cannot be had or paid. */
    fun addCritterPower(entryId: String, rating: Int = 0, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val entry = data[entryId]?.element ?: return null
        val cost = entry.child("karma")?.textContent?.trim()?.toIntOrNull() ?: 0
        if (cost > log.karma) return null
        val guid = critterPowers().add(entryId, rating, extra) ?: return null
        log.add("Karma", -cost.toDouble(), "Purchased Critter Power " + entry.child("name")?.textContent?.trim().orEmpty(), "AddCritterPower", objectId = guid, now = now)
        return guid
    }

    // ---- A.I. programs

    fun aiPrograms() = AiProgramEditor(file, data)

    /** What a program costs now: the settings' price (and cost improvements) for a normal or an advanced one. */
    fun aiProgramCost(advanced: Boolean) = aiProgramPrice(k(if (advanced) "karmanewaiadvancedprogram" else "karmanewaiprogram", if (advanced) 8 else 5), improvements, advanced)

    /** Learn a program for karma (Chummer's career dialog). Null when it is known, its requirement is missing, or the karma falls short. */
    fun addAiProgram(entryId: String, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val entry = data[entryId]?.element ?: return null
        val advanced = entry.child("category")?.textContent?.trim() == "Advanced Programs"
        val cost = aiProgramCost(advanced)
        if (cost > log.karma) return null
        val guid = aiPrograms().add(entryId, extra) ?: return null
        log.add("Karma", -cost.toDouble(), "Learned Program " + entry.child("name")?.textContent?.trim().orEmpty(), "AddAIProgram", objectId = guid, now = now)
        return guid
    }

    // ---- complex forms

    /** The karma price of learning a complex form (Chummer's `ComplexFormKarmaCost`). */
    fun complexFormCost() = ComplexForms.price(k("karmanewcomplexform", 4), improvements)

    /** Learn a complex form with karma. Null when there is no room (twice Resonance plus bonuses), not enough karma, or it is already known. */
    fun addComplexForm(entryId: String, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = complexFormCost()
        if (cost > log.karma || ComplexForms.room(file, data, settings) <= 0) return null
        val name = data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty()
        if (root.child("complexforms")?.children("complexform").orEmpty().any { it.text("name")?.trim() == name && it.text("extra")?.trim().orEmpty() == extra }) return null
        val guid = CharacterEditor(file, data, settings = settings).addComplexForm(entryId, extra)
        log.add("Karma", -cost.toDouble(), "Learned Complex Form $name", "AddComplexForm", objectId = guid, now = now)
        return guid
    }

    // ---- gear

    /** What a gear purchase costs in nuyen, or null when the entry is unknown or its price formula cannot be read. */
    fun gearCost(entryId: String, rating: Int? = null, qty: Double = 1.0): Double? {
        val entry = data[entryId]?.element ?: return null
        val min = entry.child("minrating")?.textContent?.trim()?.toIntOrNull() ?: 0
        val max = entry.child("rating")?.textContent?.trim()?.toIntOrNull() ?: 0
        val r = if (max <= 0) 0 else (rating ?: maxOf(min, 1)).coerceIn(min, max)
        return GearEditor.totalCost(entry, r, qty)
    }

    /** Why gear cannot be bought right now, in words for a player; null when it can. */
    fun gearBlockReason(entryId: String, rating: Int? = null, qty: Double = 1.0): String? {
        val cost = gearCost(entryId, rating, qty) ?: return "The price of that item cannot be worked out yet"
        if (cost > nuyenLeft() + 0.004) return "Not enough nuyen"
        if (availabilityBlocked(entryId, rating)) return "Its availability is over the limit of ${settings.int("availability", 12)} for a new character"
        return null
    }

    /** Buy gear with nuyen: adds the item and writes a "Purchased Gear" entry. Returns its GUID, or null when it cannot be paid or priced. */
    fun buyGear(entryId: String, rating: Int? = null, qty: Double = 1.0, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = gearCost(entryId, rating, qty) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val guid = GearEditor(file, data, settings).addOrStack(entryId, rating, qty, extra)
        val name = data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty()
        charge(cost, "Purchased Gear $name", "AddGear", guid, qty, now)
        return guid
    }


    // ---- creation: the same purchases are paid from the karma-to-nuyen budget instead of the career log

    private val inCreation = root.text("created")?.trim() != "True"

    /** Nuyen the character can still spend: what is left of the creation budget, or the career balance. */
    private fun nuyenLeft(): Double = if (inCreation) NuyenLedger.of(file, settings, data).remaining else log.nuyen

    /** Pay for a purchase. In career it is written to the log; in creation the budget is derived from what is carried, so only `<nuyen>` is kept up to date. */
    private fun charge(cost: Double, reason: String, kind: String, guid: String, qty: Double, now: LocalDateTime) {
        if (inCreation) NuyenLedger.sync(file, settings, data) else log.add("Nuyen", -cost, reason, kind, objectId = guid, qty = qty, now = now)
    }

    /** Creation only: an item whose availability is over the settings' limit cannot be bought (Chummer does not limit career purchases). */
    private fun availabilityBlocked(entryId: String, rating: Int?, extra: Int = 0): Boolean {
        if (!inCreation) return false
        val avail = data[entryId]?.element?.child("avail")?.textContent.orEmpty()
        val min = data[entryId]?.element?.child("minrating")?.textContent?.trim()?.toIntOrNull() ?: 0
        val v = Availability.parse(avail, rating ?: 1, min) ?: return false
        return v.number + extra > settings.int("availability", 12)
    }

    private fun gradeAvailability(entry: org.w3c.dom.Element, grade: String): Int {
        val pack = data as? chummer.core.DataPack ?: return 0
        val bio = entry.tagName == "bioware"
        return CyberwareEditor.grades(pack, bio).firstOrNull { it.child("name")?.textContent?.trim() == grade }
            ?.child("avail")?.textContent?.trim()?.removePrefix("+")?.toIntOrNull() ?: 0
    }

    /** Take a bought item out again while the character is in creation (career uses the log's undo). False when there is no such item, or in career. */
    fun removePurchase(guid: String): Boolean {
        if (!inCreation) return false
        val removed = removeAny(guid)
        if (removed) NuyenLedger.sync(file, settings, data)
        return removed
    }

    private fun removeVehicle(guid: String): Boolean = (data as? chummer.core.DataPack)?.let { VehicleEditor(file, it).remove(guid) } == true

    private fun removeVehicleMod(guid: String): Boolean = (data as? chummer.core.DataPack)?.let { VehicleEditor(file, it).removeMod(guid) } == true

    private fun removeLifestyle(guid: String): Boolean = (data as? chummer.core.DataPack)?.let { LifestyleEditor(file, it).remove(guid) } == true

    // ---- lifestyles

    /** Buy [months] months of a lifestyle (paid from the nuyen left); returns its GUID, or null when it cannot be paid for. */
    fun buyLifestyle(entryId: String, months: Int, now: LocalDateTime = LocalDateTime.now()): String? {
        val pack = data as? chummer.core.DataPack ?: return null
        val editor = LifestyleEditor(file, pack)
        val left = nuyenLeft()
        val guid = runCatching { editor.add(entryId, months) }.getOrNull() ?: return null
        val cost = editor.total(guid)
        // The ledger prices a lifestyle with the character's adjustments, so the price is known only once it is on the sheet.
        if (cost > left + 0.004) { editor.remove(guid); return null }
        charge(cost, "Purchased Lifestyle " + pack[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddLifestyle", guid, 0.0, now)
        return guid
    }

    private fun removeAny(guid: String, includeArts: Boolean = true): Boolean {
        val aw = ArmorWeaponEditor(file, data)
        return removeVehicle(guid) || removeVehicleMod(guid) || removeLifestyle(guid) || GearEditor(file, data, settings).removeGear(guid) || aw.removeWeapon(guid) || aw.removeArmor(guid) ||
            aw.removeAccessory(guid) || aw.removeArmorMod(guid) || CyberwareEditor(file, data).remove(guid) || (includeArts && removeMartialArtItem(guid))
    }

    /**
     * Sell an item in career for [percent] of what it is worth (its price with whatever is fitted to it, as the nuyen ledger prices it):
     * the item leaves the character and the nuyen is added to the log as "Selling Item". Returns the nuyen received, or null when there
     * is no such item or the character is in creation. A sale is not undoable from the log.
     */
    fun sellPurchase(guid: String, percent: Int, now: LocalDateTime = LocalDateTime.now()): Double? = sellPurchase(guid, percent.toDouble(), now)

    /** As above for any percent Chummer's Sell Item box takes: 0 to 9999.99, to two decimal places. */
    fun sellPurchase(guid: String, percent: Double, now: LocalDateTime = LocalDateTime.now()): Double? {
        if (inCreation || percent.isNaN() || percent < 0.0 || percent > MAX_SELL_PERCENT + 1e-9) return null
        val name = nameOfItem(guid) ?: return null
        val before = NuyenLedger.of(file, settings, data).spent
        if (!removeAny(guid, includeArts = false)) return null
        val value = Math.round((before - NuyenLedger.of(file, settings, data).spent).coerceAtLeast(0.0) * percent) / 100.0
        // A sale worth nothing (an included part, a free item) leaves no log entry.
        if (value > 0.0) log.add("Nuyen", value, "Selling Item $name", "SellItem", objectId = guid, now = now)
        return value
    }

    /** Take an item out of a career character without any nuyen changing hands (a free or included item). False when there is no such item, or in creation. */
    fun discardPurchase(guid: String): Boolean = !inCreation && removeAny(guid, includeArts = false)

    private fun nameOfItem(guid: String): String? {
        val all = file.doc.getElementsByTagName("guid")
        for (i in 0 until all.length) {
            val g = all.item(i) as org.w3c.dom.Element
            if (g.textContent.trim().equals(guid, true)) return (g.parentNode as? org.w3c.dom.Element)?.text("name")?.trim()
        }
        return null
    }

    // ---- armor and weapons

    fun armorCost(entryId: String, rating: Int? = null): Double? {
        val entry = data[entryId]?.element ?: return null
        val max = entry.child("rating")?.textContent?.trim()?.toIntOrNull() ?: 0
        return GearEditor.unitCost(entry, if (max > 0) (rating ?: 1).coerceIn(1, max) else 0)
    }

    fun weaponCost(entryId: String): Double? = data[entryId]?.element?.let { GearEditor.unitCost(it, 0) }

    // ---- vehicles and drones

    fun vehicleCost(entryId: String): Double? = data[entryId]?.element?.takeIf { it.tagName == "vehicle" }?.let { GearEditor.unitCost(it, 0) }

    /** Buy a vehicle or drone (with the mods and gear it comes with); returns its GUID, or null when it cannot be paid for or is over the creation availability limit. */
    fun buyVehicle(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val pack = data as? chummer.core.DataPack ?: return null
        val editor = VehicleEditor(file, pack)
        if (editor.problem(entryId) != null) return null
        val cost = vehicleCost(entryId) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, null)) return null
        val guid = editor.add(entryId)
        charge(cost, "Purchased Vehicle " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddVehicle", guid, 0.0, now)
        return guid
    }

    /** The mods that may be fitted to this vehicle or drone (what its details and the settings' drone-mods rule allow). */
    fun vehicleModOptions(vehicleGuid: String): List<ItemOption> =
        (data as? chummer.core.DataPack)?.let { VehicleEditor(file, it).modOptions(vehicleGuid, settings.bool("dronemods")) }.orEmpty()

    fun vehicleModCost(vehicleGuid: String, entryId: String, rating: Int? = null): Double? =
        (data as? chummer.core.DataPack)?.let { VehicleEditor(file, it).modCost(vehicleGuid, entryId, rating) }

    /** Why a mod cannot be bought for a vehicle right now, in words for a player; null when it can. */
    fun vehicleModBlockReason(vehicleGuid: String, entryId: String, rating: Int? = null): String? {
        val pack = data as? chummer.core.DataPack ?: return "No game data"
        VehicleEditor(file, pack).modProblem(vehicleGuid, entryId, settings.bool("dronemods"))?.let { return "That mod does not fit this vehicle" }
        val cost = vehicleModCost(vehicleGuid, entryId, rating) ?: return "The price of that mod cannot be worked out yet"
        if (cost > nuyenLeft() + 0.004) return "Not enough nuyen"
        if (availabilityBlocked(entryId, rating)) return "Its availability is over the limit of ${settings.int("availability", 12)} for a new character"
        return null
    }

    /** Buy a mod for a vehicle or drone; returns the mod's GUID, or null when it does not fit, cannot be paid for or is over the creation availability limit. */
    fun buyVehicleMod(vehicleGuid: String, entryId: String, rating: Int? = null, now: LocalDateTime = LocalDateTime.now()): String? {
        if (vehicleModBlockReason(vehicleGuid, entryId, rating) != null) return null
        val pack = data as? chummer.core.DataPack ?: return null
        val cost = vehicleModCost(vehicleGuid, entryId, rating) ?: return null
        val guid = VehicleEditor(file, pack).addMod(vehicleGuid, entryId, rating, settings.bool("dronemods"))
        charge(cost, "Purchased Vehicle Mod " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddVehicleMod", guid, 0.0, now)
        return guid
    }

    /** Buy gear for a vehicle or drone (it goes in the vehicle's own gear, not the character's); returns its GUID, or null when it cannot be paid for. */
    fun buyVehicleGear(vehicleGuid: String, entryId: String, rating: Int? = null, qty: Double = 1.0, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val vehicle = root.child("vehicles")?.children("vehicle")?.firstOrNull { it.text("guid")?.trim().equals(vehicleGuid, true) } ?: return null
        val cost = gearCost(entryId, rating, qty) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val container = vehicle.child("gears") ?: file.doc.createElement("gears").also { vehicle.appendChild(it) }
        val guid = GearEditor(file, data, settings).addOrStack(entryId, rating, qty, extra, into = container, parentId = vehicleGuid)
        charge(cost, "Purchased Gear " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddGear", guid, qty, now)
        return guid
    }

    fun buyArmor(entryId: String, rating: Int? = null, now: LocalDateTime = LocalDateTime.now()): String? {
        val editor = ArmorWeaponEditor(file, data)
        if (editor.armorProblem(entryId) != null) return null
        val cost = armorCost(entryId, rating) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val guid = editor.addArmor(entryId, rating)
        charge(cost, "Purchased Armor " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddArmor", guid, 0.0, now)
        return guid
    }

    fun buyWeapon(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val editor = ArmorWeaponEditor(file, data)
        if (editor.weaponProblem(entryId) != null) return null
        val cost = weaponCost(entryId) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, 0)) return null
        val guid = editor.addWeapon(entryId)
        charge(cost, "Purchased Weapon " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddWeapon", guid, 0.0, now)
        return guid
    }

    // ---- accessories and armor mods

    private fun storedNumber(parent: org.w3c.dom.Element?, tag: String) = parent?.text(tag)?.trim()?.toDoubleOrNull() ?: 0.0

    fun accessoryCost(weaponGuid: String, entryId: String, rating: Int? = null): Double? {
        val weapon = root.child("weapons")?.children("weapon")?.firstOrNull { it.text("guid")?.trim().equals(weaponGuid, true) } ?: return null
        val entry = data[entryId]?.element ?: return null
        val max = entry.child("rating")?.textContent?.trim()?.toIntOrNull() ?: 0
        val r = if (max <= 0) 0 else (rating ?: 1).coerceIn(1, max)
        return ArmorWeaponEditor(file, data).accessoryCost(entry, r, storedNumber(weapon, "cost"))
    }

    /** The gear that may be plugged into this gear item. */
    fun gearPluginOptions(parentGuid: String): List<GearOption> {
        val pack = data as? chummer.core.DataPack ?: return emptyList()
        val parent = GearEditor(file, data, settings).find(parentGuid) ?: return emptyList()
        val entry = parent.text("sourceid")?.trim()?.let { data[it]?.element } ?: parent
        return GearPlugins.options(pack, parent, entry)
    }

    /** Buy gear to plug into another gear item: pays for it, adds it inside the parent, and writes a "Purchased Gear" entry. */
    fun buyGearPlugin(parentGuid: String, entryId: String, rating: Int? = null, qty: Double = 1.0, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        if (gearPluginOptions(parentGuid).none { it.id.equals(entryId, true) }) return null
        val cost = gearCost(entryId, rating, qty) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val editor = GearEditor(file, data, settings)
        val parent = editor.find(parentGuid) ?: return null
        val container = parent.child("children") ?: file.doc.createElement("children").also { parent.appendChild(it) }
        val guid = editor.addOrStack(entryId, rating, qty, extra, into = container, parentId = parentGuid)
        charge(cost, "Purchased Gear " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddGear", guid, qty, now)
        return guid
    }

    /** Switch a gear item (a plug-in, say) on or off. */
    fun setGearEquipped(guid: String, equipped: Boolean): Boolean = GearEditor(file, data, settings).setEquipped(guid, equipped)

    /** Fold identical loose gear stacks into this one (Chummer's Merge Quantity). */
    fun mergeGear(guid: String): Boolean = GearEditor(file, data, settings).mergeStacks(guid)

    /** Switch a weapon accessory on or off (no cost; one that is off adds nothing to the weapon). */
    fun setAccessoryEquipped(accessoryGuid: String, equipped: Boolean): Boolean = ArmorWeaponEditor(file, data).setAccessoryEquipped(accessoryGuid, equipped)

    // Wearing armor and armor sets: no cost, so no expense entry.
    fun setArmorEquipped(guid: String, equipped: Boolean): Boolean = ArmorWeaponEditor(file, data).setArmorEquipped(guid, equipped)
    fun setArmorModEquipped(guid: String, equipped: Boolean): Boolean = ArmorWeaponEditor(file, data).setArmorModEquipped(guid, equipped)
    fun setArmorGearEquipped(guid: String, equipped: Boolean): Boolean = ArmorWeaponEditor(file, data).setArmorGearEquipped(guid, equipped)
    fun addArmorSet(name: String): Boolean = ArmorWeaponEditor(file, data).addArmorSet(name) != null
    fun renameArmorSet(guid: String, name: String): Boolean = ArmorWeaponEditor(file, data).renameArmorSet(guid, name)
    fun removeArmorSet(guid: String): Boolean = ArmorWeaponEditor(file, data).removeArmorSet(guid)
    fun moveArmorToSet(armorGuid: String, setGuid: String): Boolean = ArmorWeaponEditor(file, data).moveArmorToSet(armorGuid, setGuid)
    fun setArmorSetEquipped(setGuid: String, equipped: Boolean): Boolean = ArmorWeaponEditor(file, data).setArmorSetEquipped(setGuid, equipped) > 0

    /** The accessories that may be fitted to this weapon (those whose weapon requirements it meets). */
    fun accessoryOptions(weaponGuid: String): List<ItemOption> {
        val pack = data as? chummer.core.DataPack ?: return emptyList()
        val weapon = ArmorWeaponEditor(file, data).weaponEntry(weaponGuid) ?: return ArmorWeaponCatalog.accessories(pack)
        return ArmorWeaponCatalog.accessoriesFor(pack, weapon)
    }

    /** The mods that may be fitted to this armor (those its category, name and parts allow). */
    fun armorModOptions(armorGuid: String): List<ItemOption> {
        val pack = data as? chummer.core.DataPack ?: return emptyList()
        val saved = root.child("armors")?.children("armor")?.firstOrNull { it.text("guid")?.trim().equals(armorGuid, true) } ?: return ArmorWeaponCatalog.armorMods(pack)
        val entry = saved.text("sourceid")?.trim()?.let { data[it]?.element } ?: saved
        val installed = saved.child("armormods")?.children("armormod").orEmpty().mapNotNull { it.text("name")?.trim() }
        return ArmorWeaponCatalog.armorModsFor(pack, entry, installed)
    }

    fun buyAccessory(weaponGuid: String, entryId: String, mount: String? = null, rating: Int? = null, now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = accessoryCost(weaponGuid, entryId, rating) ?: return null
        if (ArmorWeaponEditor(file, data).accessoryProblem(weaponGuid, entryId) != null) return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val guid = ArmorWeaponEditor(file, data).addAccessory(weaponGuid, entryId, mount, rating)
        charge(cost, "Purchased Weapon Accessory " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddWeaponAccessory", guid, 0.0, now)
        return guid
    }

    fun armorModCost(entryId: String, rating: Int? = null): Double? {
        val entry = data[entryId]?.element ?: return null
        val max = entry.child("maxrating")?.textContent?.trim()?.toIntOrNull() ?: 0
        return GearEditor.unitCost(entry, if (max > 1) (rating ?: 1).coerceIn(1, max) else 0)
    }

    fun buyArmorMod(armorGuid: String, entryId: String, rating: Int? = null, now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = armorModCost(entryId, rating) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val guid = ArmorWeaponEditor(file, data).addArmorMod(armorGuid, entryId, rating)
        charge(cost, "Purchased Armor Mod " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddArmorMod", guid, 0.0, now)
        return guid
    }

    // ---- cyberware and bioware

    /** The character's essence now, from the installed ware and the improvements. */
    fun essenceNow(): Double {
        val rules = settings.toRuleSettings()
        val set = ImprovementSet(Improvement.parseAll(root), isCareer = true)
        return EssenceEngine(set, data, true, rules.essenceDecimals, rules.dontRoundEssenceInternally).essence(6.0, file.augmentations())
    }

    /** The plug-ins the installed item [parentGuid] takes, with rating limits worked out for it (limb upgrades depend on the limb). */
    fun pluginOptions(parentGuid: String): List<WareOption> {
        val editor = CyberwareEditor(file, data)
        val sourceId = editor.findWare(parentGuid)?.text("sourceid")?.trim().orEmpty()
        val pack = data as? chummer.core.DataPack ?: return emptyList()
        return WareCatalog.pluginsFor(pack, sourceId, editor, parentGuid)
    }

    fun wareCost(entryId: String, grade: String, rating: Int? = null, parentGuid: String? = null): Double? = CyberwareEditor(file, data).cost(entryId, grade, rating, parentGuid)
    fun wareEssence(entryId: String, grade: String, rating: Int? = null, parentGuid: String? = null): Double? = CyberwareEditor(file, data).essence(entryId, grade, rating, parentGuid)

    /**
     * Buy and install cyberware or bioware: pays the price at the grade, refuses what the character cannot pay or what
     * would take essence below zero, and writes a "Purchased Cyberware" entry. Returns the item's GUID, or null.
     */
    fun buyWare(entryId: String, grade: String = "Standard", rating: Int? = null, parentGuid: String? = null, now: LocalDateTime = LocalDateTime.now()): String? {
        // An A.I. has no body to fit ware in, and desktop Chummer cannot open a file that gives it any.
        if (isAI(file)) return null
        val editor = CyberwareEditor(file, data)
        val entry = data[entryId]?.element ?: return null
        if (grade in settings.bannedWareGrades) return null
        val parent = parentGuid?.let { editor.findWare(it) ?: return null }
        // A plug-in is bought at its parent's grade and must fit the parent's capacity.
        val useGrade = parent?.text("grade")?.trim()?.ifEmpty { null } ?: grade
        if (parent == null) { if (CyberwareEditor.problem(entry) != null) return null }
        else if (editor.plugProblem(parentGuid, entryId) != null) return null
        val cost = editor.cost(entryId, useGrade, rating, parentGuid) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating ?: 1, gradeAvailability(entry, useGrade))) return null
        // A plug-in costs essence only when it adds to its parent's.
        val preview = if (parent != null && entry.child("addtoparentess") == null) 0.0 else editor.essence(entryId, useGrade, rating, parentGuid) ?: return null
        if (essenceNow() - preview < 0) return null
        val guid = editor.add(entryId, useGrade, rating, parentGuid = parentGuid)
        if (essenceNow() < -0.0001) { editor.remove(guid); return null }
        charge(cost, "Purchased Cyberware " + entry.child("name")?.textContent?.trim().orEmpty(), "AddCyberware", guid, 0.0, now)
        return guid
    }

    /**
     * What moving an installed ware item up one grade would do: the next grade, the nuyen it costs (only the difference between the two grades'
     * prices, for the item and the plug-ins in it) and whether the character can pay it now. Null for an item that cannot be upgraded.
     */
    fun wareUpgradeOffer(guid: String): WareUpgrade? {
        if (inCreation) return null
        val pack = data as? chummer.core.DataPack ?: return null
        val item = root.child("cyberwares")?.children("cyberware")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return null
        // An item that forces its grade (it only comes in one) has nowhere to go.
        if (item.text("forcegrade")?.trim().orEmpty().isNotEmpty()) return null
        val bio = item.text("improvementsource")?.trim() == "Bioware"
        val from = item.text("grade")?.trim().orEmpty()
        val editor = CyberwareEditor(file, data)
        val offered = WareCatalog.gradeNames(pack, bio)
        if (from !in offered) return null
        fun essence(grade: String) = data.gradeEssence(bio, grade) ?: 1.0
        fun price(grade: String) = editor.gradeCostMultiplier(bio, grade)
        // Only grades from the character's enabled books. The settings' list of banned grades is a creation rule in Chummer (a career character can use any grade), so it is not applied here.
        val books = settings.books
        val inBooks = CyberwareEditor.grades(pack, bio).filter { g -> books.isEmpty() || g.child("source")?.textContent?.trim() in books }.mapNotNull { it.child("name")?.textContent?.trim() }.toSet()
        // The next grade is the nearest step up: one that gives more essence back and costs more. That skips a grade like Omegaware (cheaper than Standard, same essence) and
        // puts Greyware between Alphaware and Betaware, as its figures do.
        val to = offered.filter { it in inBooks && essence(it) < essence(from) - 1e-9 && price(it) > price(from) + 1e-9 }
            .minWithOrNull(compareByDescending<String> { essence(it) }.thenBy { price(it) }) ?: return null
        val difference = price(to) - price(from)
        fun basePrice(e: org.w3c.dom.Element): Double? {
            var total = CyberwareEditor.formula(e.text("cost").orEmpty(), e.text("rating")?.trim()?.toIntOrNull() ?: 0) ?: return null
            for (child in e.child("children")?.children("cyberware").orEmpty()) total += basePrice(child) ?: return null
            return total
        }
        // Rounded to the cent: 4999.999999999999 nuyen is a floating-point slip, not a price.
        val cost = Math.round(((basePrice(item) ?: return null) * difference).coerceAtLeast(0.0) * 100.0) / 100.0
        return WareUpgrade(from, to, cost, cost <= nuyenLeft() + 0.004)
    }

    // ---- creation: change the grade and rating of an item already bought

    private fun wareBase(e: org.w3c.dom.Element, ratingOverride: Int? = null): Double? {
        var total = CyberwareEditor.formula(e.text("cost").orEmpty(), ratingOverride ?: e.text("rating")?.trim()?.toIntOrNull() ?: 0) ?: return null
        for (child in e.child("children")?.children("cyberware").orEmpty()) total += wareBase(child) ?: return null
        return total
    }

    private fun isPlugin(item: org.w3c.dom.Element) = (item.parentNode?.parentNode as? org.w3c.dom.Element)?.tagName == "cyberware"

    /** What may be edited on the installed item [guid] in creation; null in career, for an unknown item, or for one with neither a grade to choose nor a rating. */
    fun wareEditInfo(guid: String): WareEditInfo? {
        if (!inCreation) return null
        val editor = CyberwareEditor(file, data)
        val item = editor.findWare(guid) ?: return null
        val entry = data[item.text("sourceid")?.trim().orEmpty()]?.element ?: return null
        val plugin = isPlugin(item)
        val (min, max) = editor.range(entry, if (plugin) item.parentNode.parentNode as org.w3c.dom.Element else null)
        // A plug-in always has its parent's grade, and an item that forces its grade (or has none) cannot be moved.
        val gradeEditable = !plugin && item.text("forcegrade")?.trim().orEmpty().isEmpty() && item.text("grade")?.trim() != "None"
        if (!gradeEditable && max <= 0) return null
        return WareEditInfo(item.text("grade")?.trim().orEmpty(), item.text("rating")?.trim()?.toIntOrNull() ?: 0, if (max > 0) maxOf(min, 1) else 0, max, gradeEditable)
    }

    /** Preview giving [guid] the grade [grade] and rating [rating] in creation (no change is made). Null when the item cannot be edited. */
    fun wareEditPreview(guid: String, grade: String, rating: Int?): WareEditPreview? {
        val info = wareEditInfo(guid) ?: return null
        val editor = CyberwareEditor(file, data)
        val item = editor.findWare(guid) ?: return null
        val entryId = item.text("sourceid")?.trim().orEmpty()
        val entry = data[entryId]?.element ?: return null
        val pack = data as? chummer.core.DataPack ?: return null
        val bio = entry.tagName == "bioware"
        val rated = info.maxRating > 0
        val newRating = if (rated) (rating ?: info.rating).coerceIn(info.minRating, info.maxRating) else null
        val newGrade = if (info.gradeEditable) grade else info.grade
        // The price is the item's and its plug-ins' base prices times the grade's multiplier; a plug-in is priced at its parent's grade, which does not change here.
        val old = (wareBase(item) ?: return null) * editor.gradeCostMultiplier(bio, info.grade)
        val new = (wareBase(item, newRating) ?: return null) * editor.gradeCostMultiplier(bio, newGrade)
        // A plug-in costs essence only when it adds to its parent's.
        val countsEssence = !isPlugin(item) || entry.child("addtoparentess") != null
        val oldEss = if (countsEssence) editor.essence(entryId, info.grade, if (rated) info.rating else null) ?: 0.0 else 0.0
        val newEss = if (countsEssence) editor.essence(entryId, newGrade, newRating) ?: 0.0 else 0.0
        val problem = when {
            info.gradeEditable && newGrade != info.grade && newGrade !in WareCatalog.gradeNames(pack, bio, settings.bannedWareGrades) -> "that grade is not allowed"
            new - old > nuyenLeft() + 0.004 -> "not enough nuyen"
            essenceNow() - (newEss - oldEss) < -0.0001 -> "not enough essence"
            (newGrade != info.grade || newRating != info.rating) && availabilityBlocked(entryId, newRating, if (info.gradeEditable) gradeAvailability(entry, newGrade) else 0) -> "availability is over the limit"
            else -> null
        }
        return WareEditPreview(Math.round(old * 100) / 100.0, Math.round(new * 100) / 100.0, oldEss, newEss, problem)
    }

    /**
     * Give an installed item (and the plug-ins in it) a new grade and rating while the character is in creation. The change is undone and false is
     * returned when it is not allowed or leaves the character short of nuyen or essence.
     */
    fun editWare(guid: String, grade: String, rating: Int?): Boolean {
        val info = wareEditInfo(guid) ?: return false
        val preview = wareEditPreview(guid, grade, rating) ?: return false
        // The preview's essence is the item's own figure; the check after the change uses the whole character, so only that one can refuse for essence.
        if (preview.problem != null && preview.problem != "not enough essence") return false
        val editor = CyberwareEditor(file, data)
        val newRating = if (info.maxRating > 0) (rating ?: info.rating).coerceIn(info.minRating, info.maxRating) else null
        val newGrade = if (info.gradeEditable) grade else info.grade
        if (newGrade == info.grade && (newRating == null || newRating == info.rating)) return true
        fun apply(g: String, r: Int?): Boolean = (!info.gradeEditable || editor.setGrade(guid, g)) && (r == null || r == editor.findWare(guid)?.text("rating")?.trim()?.toIntOrNull() || editor.setRating(guid, r))
        if (!apply(newGrade, newRating)) { apply(info.grade, info.rating.takeIf { info.maxRating > 0 }); return false }
        // The real check, with everything the character carries.
        if (NuyenLedger.of(file, settings, data).remaining < -0.004 || essenceNow() < -0.0001) {
            apply(info.grade, info.rating.takeIf { info.maxRating > 0 })
            return false
        }
        NuyenLedger.sync(file, settings, data)
        return true
    }

    /** What can be edited on every installed ware item, plug-ins included (by GUID); empty in career. */
    fun wareEdits(): Map<String, WareEditInfo> {
        if (inCreation.not()) return emptyMap()
        val out = HashMap<String, WareEditInfo>()
        fun walk(list: org.w3c.dom.Element?) {
            for (w in list?.children("cyberware").orEmpty()) {
                w.text("guid")?.trim()?.let { g -> wareEditInfo(g)?.let { out[g] = it } }
                walk(w.child("children"))
            }
        }
        walk(root.child("cyberwares"))
        return out
    }

    /** The upgrade on offer for every top-level ware item (by GUID); items that cannot be upgraded are left out. */
    fun wareUpgrades(): Map<String, WareUpgrade> =
        root.child("cyberwares")?.children("cyberware").orEmpty().mapNotNull { w -> w.text("guid")?.trim()?.let { g -> wareUpgradeOffer(g)?.let { g to it } } }.toMap()

    /**
     * Upgrade an installed ware item (and its plug-ins) to the next grade, paying only the difference in price. Returns the log entry's GUID,
     * or null when the item cannot be upgraded or the character cannot pay. The entry can be undone from the log, which puts the old grade back.
     */
    fun upgradeWare(guid: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val offer = wareUpgradeOffer(guid) ?: return null
        if (!offer.affordable) return null
        val item = CyberwareEditor(file, data).findWare(guid) ?: return null
        val kind = if (item.text("improvementsource")?.trim() == "Bioware") "Bioware" else "Cyberware"
        if (!CyberwareEditor(file, data).setGrade(guid, offer.to)) return null
        // Chummer has no log kind for an upgrade; a manual spend is the safe one (undoing it on the desktop only gives the nuyen back). Our own undo reads the grades from [extra].
        return log.add("Nuyen", -offer.cost, "Upgraded $kind ${item.text("name")?.trim().orEmpty()} to ${offer.to}", "ManualSubtract", objectId = guid, extra = WARE_GRADE_PREFIX + offer.from + ":" + offer.to, now = now)
    }

    /** Buy gear for an armor's capacity: pays for it, installs it in the armor, and writes a "Purchased Gear" entry. */
    fun buyArmorGear(armorGuid: String, entryId: String, rating: Int? = null, qty: Double = 1.0, now: LocalDateTime = LocalDateTime.now()): String? {
        val editor = ArmorWeaponEditor(file, data)
        if (editor.armorGearProblem(armorGuid, entryId) != null) return null
        val cost = gearCost(entryId, rating, qty) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val guid = editor.addArmorGear(armorGuid, entryId, rating, qty)
        charge(cost, "Purchased Gear " + data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty(), "AddGear", guid, qty, now)
        return guid
    }

    /** The loose ammunition a weapon can be loaded from. */
    fun compatibleAmmo(weaponGuid: String): List<ArmorWeaponEditor.AmmoStack> = ArmorWeaponEditor(file, data).compatibleAmmo(weaponGuid)

    /**
     * Load ammunition from a gear stack into a weapon's clip [slot] (the first empty clip when 0), [rounds] of it (a full clip when null); a clip that
     * holds the same ammunition is topped up.
     */
    fun loadAmmo(weaponGuid: String, ammoGuid: String, slot: Int = 0, rounds: Int? = null): Boolean = ArmorWeaponEditor(file, data).loadAmmo(weaponGuid, ammoGuid, slot, rounds)

    /** Make clip [slot] the one the weapon fires from. */
    fun setActiveClip(weaponGuid: String, slot: Int): Boolean = ArmorWeaponEditor(file, data).setActiveClip(weaponGuid, slot)

    /** Spend rounds from a clip (the active one when [slot] is 0), as firing does. */
    fun fireRounds(weaponGuid: String, rounds: Int, slot: Int = 0): Boolean = ArmorWeaponEditor(file, data).fireRounds(weaponGuid, rounds, slot)

    // ---- martial arts

    /** What a style costs in karma now (its own price, normally 7), or null when the style is unknown. */
    fun martialArtCost(entryId: String): Int? = data[entryId]?.element?.let { it.child("cost")?.textContent?.trim()?.toIntOrNull() ?: 7 }

    /** What a technique costs: nothing for a style's first, the settings' technique price for each after. */
    fun techniqueCost(artGuid: String): Int? {
        val art = MartialArtEditor(file, data).art(artGuid) ?: return null
        return if ((art.child("martialarttechniques")?.children("martialarttechnique")?.size ?: 0) > 0) k("karmamaneuver", 5) else 0
    }

    /** Learn a style: costs its karma in career, nothing is logged in creation (the karma ledger counts it). Returns the style's GUID, or null. */
    fun buyMartialArt(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = martialArtCost(entryId) ?: return null
        if (!inCreation && cost > log.karma) return null
        val guid = runCatching { MartialArtEditor(file, data).addArt(entryId) }.getOrNull() ?: return null
        if (!inCreation) log.add("Karma", -cost.toDouble(), "Learned Martial Art " + data[entryId]!!.element.child("name")?.textContent?.trim().orEmpty(), "AddMartialArt", objectId = guid, now = now)
        return guid
    }

    fun buyTechnique(artGuid: String, entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = techniqueCost(artGuid) ?: return null
        if (!inCreation && cost > log.karma) return null
        val guid = runCatching { MartialArtEditor(file, data).addTechnique(artGuid, entryId) }.getOrNull() ?: return null
        if (!inCreation) log.add("Karma", -cost.toDouble(), "Learned Technique " + data[entryId]!!.element.child("name")?.textContent?.trim().orEmpty(), "AddMartialArtTechnique", objectId = guid, now = now)
        return guid
    }

    /** In creation, take a style or technique out again (the karma ledger follows). */
    fun removeMartialArtItem(guid: String): Boolean {
        if (!inCreation) return false
        val editor = MartialArtEditor(file, data)
        return editor.removeTechnique(guid) || editor.removeArt(guid)
    }

    /** Take the rounds out of a weapon's clip [slot] (the last loaded one when 0), back into the loose ammunition. */
    fun unloadAmmo(weaponGuid: String, slot: Int = 0): Boolean = ArmorWeaponEditor(file, data).unloadAmmo(weaponGuid, slot)

    /** Capacity each top-level ware item has left for plug-ins (only items that take plug-ins). */
    fun pluginCapacity(): Map<String, Double> {
        val editor = CyberwareEditor(file, data)
        val out = HashMap<String, Double>()
        for (w in root.child("cyberwares")?.children("cyberware").orEmpty()) {
            val entry = data[w.text("sourceid")?.trim().orEmpty()]?.element ?: continue
            if (entry.child("allowsubsystems")?.children()?.isEmpty() != false) continue
            out[w.text("guid")!!.trim()] = editor.capacityOf(w) - editor.capacityUsed(w)
        }
        return out
    }
}
