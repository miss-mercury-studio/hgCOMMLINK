package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import java.time.LocalDateTime

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

    /** The specializations the game data lists for an active skill (knowledge skills take any text). */
    fun specializationChoices(guid: String): List<String> {
        val s = skillRow(guid) ?: return emptyList()
        if (s.isKnowledge) return emptyList()
        return data[s.suid]?.element?.child("specs")?.children("spec").orEmpty().map { it.textContent.trim() }
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
        // Buying off a negative quality is not undoable in the app (Chummer re-adds it from the data), so it is logged as such.
        if (negative) log.add("Karma", -cost.toDouble(), "Removed Negative Quality $name", "RemoveQuality", objectId = sourceId, extra = q.text("extra")?.trim().orEmpty(), now = now)
        return true
    }

    // ---- spells

    /** The karma price of learning a spell or ritual (preparations are not told apart yet). Null for an unknown id. */
    fun spellCost(entryId: String): Int? {
        val entry = data[entryId]?.element ?: return null
        val category = if (entry.child("category")?.textContent?.trim() == "Rituals") "Rituals" else "Spells"
        val set = improvements
        var v = k("karmaspell", 5).toDouble()
        fun applies(i: Improvement) = i.enabled && (i.improvedName == category || i.improvedName.isEmpty())
        v += set.all.filter { it.type == "NewSpellKarmaCost" && applies(it) }.sumOf { it.value * it.rating }
        var multiplier = 1.0
        set.all.filter { it.type == "NewSpellKarmaCostMultiplier" && applies(it) }.forEach { multiplier *= it.value / 100.0 }
        return maxOf(standardRound(v * multiplier), 0)
    }

    fun addSpell(entryId: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = spellCost(entryId) ?: return null
        if (cost > log.karma) return null
        val guid = CharacterEditor(file, data, settings = settings).addSpell(entryId)
        val name = data[entryId]?.element?.child("name")?.textContent?.trim().orEmpty()
        log.add("Karma", -cost.toDouble(), "Learned Spell $name", "AddSpell", objectId = guid, now = now)
        return guid
    }

    /** The character's sprites (in creation and career alike; sprites cost no karma). */
    fun sprites() = SpriteEditor(file, data, settings)

    // ---- initiation and metamagics

    fun initiation() = InitiationEditor(file, data, settings)

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

    /** Buy gear with nuyen: adds the item and writes a "Purchased Gear" entry. Returns its GUID, or null when it cannot be paid or priced. */
    fun buyGear(entryId: String, rating: Int? = null, qty: Double = 1.0, extra: String = "", now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = gearCost(entryId, rating, qty) ?: return null
        if (cost > nuyenLeft() + 0.004 || availabilityBlocked(entryId, rating)) return null
        val guid = GearEditor(file, data, settings).addGear(entryId, rating, qty, extra)
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
        val v = Availability.parse(avail, rating ?: 1) ?: return false
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
        val aw = ArmorWeaponEditor(file, data)
        val removed = GearEditor(file, data, settings).removeGear(guid) || aw.removeWeapon(guid) || aw.removeArmor(guid) ||
            aw.removeAccessory(guid) || aw.removeArmorMod(guid) || CyberwareEditor(file, data).remove(guid) || removeMartialArtItem(guid)
        if (removed) NuyenLedger.sync(file, settings, data)
        return removed
    }

    // ---- armor and weapons

    fun armorCost(entryId: String, rating: Int? = null): Double? {
        val entry = data[entryId]?.element ?: return null
        val max = entry.child("rating")?.textContent?.trim()?.toIntOrNull() ?: 0
        return GearEditor.unitCost(entry, if (max > 0) (rating ?: 1).coerceIn(1, max) else 0)
    }

    fun weaponCost(entryId: String): Double? = data[entryId]?.element?.let { GearEditor.unitCost(it, 0) }

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

    fun buyAccessory(weaponGuid: String, entryId: String, mount: String? = null, rating: Int? = null, now: LocalDateTime = LocalDateTime.now()): String? {
        val cost = accessoryCost(weaponGuid, entryId, rating) ?: return null
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

    /** Load ammunition from a gear stack into a weapon's first empty clip. */
    fun loadAmmo(weaponGuid: String, ammoGuid: String): Boolean = ArmorWeaponEditor(file, data).loadAmmo(weaponGuid, ammoGuid)

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

    /** Take the rounds out of the weapon's last loaded clip, back into the loose ammunition. */
    fun unloadAmmo(weaponGuid: String): Boolean = ArmorWeaponEditor(file, data).unloadAmmo(weaponGuid)

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
