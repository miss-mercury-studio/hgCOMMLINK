package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text

/**
 * Everything the character sheet shows, calculated once from a character file, the game data and the
 * character's settings. The UI only formats this; it holds no rules.
 */
data class Sheet(
    val name: String,
    val alias: String,
    val metatype: String,
    val buildMethod: String,
    val isCareer: Boolean,
    val karma: String,
    val nuyen: String,
    val attributes: List<AttributeLine>,
    val essence: Double,
    val limits: Limits,
    val initiativeValue: Int,
    val initiativeDice: Int,
    val physicalCM: Int,
    val stunCM: Int,
    val cmOverflow: Int,
    val armor: ArmorLines,
    val soak: Int,
    val liftWeight: Int,
    val carryWeight: Int,
    val pools: List<Pair<String, Int>>,
    val activeSkills: List<SkillResult>,
    val knowledgeSkills: List<SkillResult>,
    /** Skill groups with the karma put into them, and knowledge skills and languages with their purchases. */
    val skillGroups: List<GroupLine>,
    val knowledge: List<KnowledgeLine>,
    val qualities: List<QualityLine>,
    val sections: SheetSections,
    /** Karma spent and remaining; only for a Karma-build character still in creation. */
    val karmaLedger: KarmaLedger?,
    /** The magic section, present when the character has magic switched on. */
    val magic: MagicLines?,
    /** What stops this character from being finished, for a Karma-build character in creation. */
    val review: CreationReview?,
    /** What karma can buy and the expense log; only for a career character. */
    val career: CareerLines? = null,
    /** Ground, swim and fly movement as the printed sheet gives it; empty when the metatype has none ("Special"). */
    val movement: String = "",
    /** The nuyen budget and plug-in room of a Karma-build character in creation, where gear is bought from karma-bought nuyen. */
    val creationBuy: CreationBuy? = null,
    /** Creation: each active skill's specialization and what it could take (by skill name). */
    val skillSpecs: Map<String, SkillSpecInfo> = emptyMap(),
    /** An adept's power points and powers; null for anyone who is not an adept. */
    val powers: PowerPoints? = null,
    val isMagician: Boolean = false,
    /** Limit modifiers that apply only under a condition (not in the limits above). */
    val limitNotes: List<LimitLine> = emptyList(),
    /** Free points of a Priority or Sum-to-Ten character in creation; null for other builds. */
    val points: PointsLedger? = null,
    /** The technomancer's complex forms, when Resonance is switched on. */
    val complexForms: ComplexFormSection? = null,
    /** Initiation (submersion) for a character with magic or resonance, once created. */
    val initiation: InitiationSection? = null,
    /** Mentor spirits with the advantage choices they offer. */
    val mentors: List<MentorLine> = emptyList(),
    /** Summoned and bound spirits of a character with magic. */
    val spirits: SpiritSection? = null,
    /** Critter powers, shown for critters and for any character that has some. */
    val critterPowers: List<CritterPowerLine>? = null,
    /** An A.I.'s programs (shown when Depth is switched on). */
    val aiPrograms: List<AiProgramLine>? = null,
    /** Damage taken and the wound modifier it causes (already applied to initiative and the skill pools). */
    val damage: DamageLine = DamageLine(0, 0, 0, 0, 0, 0, 3),
) {
    /** Boxes filled on each condition monitor, the sizes of the tracks, the wound modifier (zero or negative) and the boxes per penalty step. */
    data class DamageLine(val physical: Int, val stun: Int, val physicalMax: Int, val stunMax: Int, val overflowMax: Int, val wound: Int, val threshold: Int)
    data class CreationBuy(val ledger: NuyenLedger, val pluginCapacity: Map<String, Double>)
    /**
     * Career spending: the karma price of the next step of each attribute, skill (by GUID) and skill group (null at the
     * maximum), the GUID of each active skill by name, and the expense log, newest first.
     */
    data class CareerLines(
        val attributeCosts: Map<String, Int?>, val skillCosts: Map<String, Int?>, val skillIds: Map<String, String>,
        val groupCosts: Map<String, Int?>, val expenses: List<ExpenseEntry>, val undoable: Set<String>,
        /** Skills that can still learn a specialization (by GUID): its price and the choices the game data lists. */
        val specCosts: Map<String, Int>, val specChoices: Map<String, List<String>>,
        /** Karma it costs to remove each quality, by GUID (0 for a positive one). */
        val qualityRemoval: Map<String, Int>,
        /** Capacity each installed ware item has left for plug-ins, by GUID (only items that take plug-ins). */
        val pluginCapacity: Map<String, Double>,
    )

    data class SpellLine(val guid: String, val name: String, val category: String, val type: String, val range: String, val duration: String, val dv: String, val descriptors: String, val alchemical: Boolean = false)
    data class MagicLines(val tradition: String, val spells: List<SpellLine>)
    data class GroupLine(val name: String, val karma: Int)
    data class KnowledgeLine(
        val guid: String, val name: String, val type: String, val points: Int, val karma: Int,
        val native: Boolean, val rating: Int, val pool: Int,
    )
    data class AttributeLine(val name: String, val total: Int, val maximum: Int)
    data class Limits(val physical: Int, val mental: Int, val social: Int, val astral: Int)
    data class ArmorLines(val general: Int, val encumbrance: Int, val byType: List<Pair<String, Int>>)
    data class QualityLine(val guid: String, val name: String, val positive: Boolean, val extra: String)

    companion object {
        /** The build methods whose creation screens are supported. */
        private val CREATION_BUILDS = setOf("Karma", "Priority", "SumtoTen")
        private val ORDER = listOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL", "EDG", "MAG", "RES", "DEP")

        fun build(c: CharacterFile, data: GameData, settings: CharacterSettings): Sheet {
            val rules = settings.toRuleSettings()
            val career = c.root.text("created")?.trim().equals("True", true)
            val set = ImprovementSet(Improvement.parseAll(c.root), career)
            val attrs = AttributeEngine.forSet(c, set, rules)

            val essenceEngine = EssenceEngine(set, data, true, rules.essenceDecimals, rules.dontRoundEssenceInternally)
            val essence = essenceEngine.essence(6.0, c.augmentations())
            val d = DerivedStats(set, attrs, essence, settings)

            val str = attrs["STR"]?.totalValue ?: 0
            val armor = ArmorEngine(
                c.armors(), set, str,
                uncappedAccessories = settings.bool("uncappedarmoraccessorybonuses"),
                noEncumbrance = settings.bool("noarmorencumbrance"),
                armorDegradation = settings.bool("armordegredation"),
            )
            val general = armor.rating()
            val byType = listOf("Fire" to "FireArmor", "Cold" to "ColdArmor", "Electricity" to "ElectricityArmor", "Acid" to "AcidArmor", "Falling" to "FallingArmor")
                .map { (label, type) -> label to armor.rating(type) }

            val magic = flag(c, "magenabled")
            val resonance = flag(c, "resenabled")
            val physicalFilled = c.root.text("physicalcmfilled")?.trim()?.toIntOrNull() ?: 0
            val stunFilled = c.root.text("stuncmfilled")?.trim()?.toIntOrNull() ?: 0
            val wound = d.woundModifier(physicalFilled, stunFilled)
            val skillEngine = SkillEngine(set, attrs, rules, career, magicEnabled = magic, resonanceEnabled = resonance, woundModifier = wound)
            val groups = c.skillGroups()
            val skills = SkillEngine.skillsOf(c, data).map { raw ->
                raw to skillEngine.calculate(raw, groups[raw.group]?.first ?: 0, groups[raw.group]?.second ?: 0)
            }
            val active = skills.filter { !it.first.isKnowledge && it.second.enabled }.map { it.second }.sortedBy { it.name }
            val knowledge = skills.filter { it.first.isKnowledge }.map { it.second }.sortedBy { it.name }

            val s = c.summary()
            val shown = ORDER.mapNotNull { n ->
                val a = attrs[n] ?: return@mapNotNull null
                if ((n == "MAG" && !magic) || (n == "RES" && !resonance) || (n == "DEP" && !flag(c, "depenabled"))) return@mapNotNull null
                AttributeLine(n, a.totalValue, a.totalMaximum)
            }
            val res = d.resistances()
            return Sheet(
                name = s.name, alias = s.alias, metatype = s.metatype, buildMethod = s.buildMethod, isCareer = career,
                karma = s.karma, nuyen = s.nuyen, attributes = shown, essence = essence,
                limits = Limits(d.limitPhysical, d.limitMental, d.limitSocial, d.limitAstral),
                initiativeValue = d.initiative.value + wound, initiativeDice = d.initiative.dice,
                physicalCM = d.physicalCM, stunCM = d.stunCM, cmOverflow = d.cmOverflow,
                damage = DamageLine(physicalFilled, stunFilled, d.physicalCM, d.stunCM, d.cmOverflow, wound, d.cmThreshold),
                armor = ArmorLines(general, armor.encumbrance(), byType),
                soak = (attrs["BOD"]?.totalValue ?: 0) + general + standardRound(set.valueOf("DamageResistance")),
                liftWeight = d.liftWeight, carryWeight = d.carryWeight,
                pools = listOf(
                    "Composure" to d.composure, "Judge Intentions" to d.judgeIntentions, "Memory" to d.memory,
                    "Lift & Carry" to d.liftAndCarry, "Surprise" to d.surprise, "Dodge (defense)" to d.dodge,
                    "Fatigue resist" to d.fatigueResist, "Direct spell, mana" to (res["directmanaresist"] ?: 0),
                    "Direct spell, physical" to (res["directphysicalresist"] ?: 0),
                ),
                activeSkills = active, knowledgeSkills = knowledge,
                skillGroups = c.skillGroups().map { (n, v) -> GroupLine(n, v.second) }.sortedBy { it.name },
                knowledge = c.skills().filter { it.isKnowledge }.map { row ->
                    val r = skills.firstOrNull { it.first.name == row.name && it.first.isKnowledge }?.second
                    KnowledgeLine(row.guid, row.name, row.type.ifEmpty { row.category }, row.base, row.karma, row.isNativeLanguage, r?.rating ?: 0, r?.pool ?: 0)
                }.sortedBy { it.name.lowercase() },
                qualities = qualities(c),
                review = if (!career && s.buildMethod in CREATION_BUILDS) CreationReview.of(c, settings, data) else null,
                magic = if (flag(c, "magenabled")) MagicLines(
                    c.root.child("tradition")?.text("name")?.trim().orEmpty(),
                    c.root.child("spells")?.children("spell").orEmpty().map {
                        SpellLine(
                            it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("category").orEmpty().trim(),
                            it.text("type").orEmpty().trim(), it.text("range").orEmpty().trim(), it.text("duration").orEmpty().trim(),
                            it.text("dv").orEmpty().trim(), it.text("descriptors").orEmpty().trim(), it.text("alchemical")?.trim().equals("True", true),
                        )
                    },
                ) else null,
                movement = Movement.of(c, data, set, attrs)?.full().orEmpty(),
                powers = if (flag(c, "adept")) PowerPoints.of(c, settings, data) else null,
                isMagician = flag(c, "magician"),
                limitNotes = LimitModifiers.of(c),
                skillSpecs = if (!career && s.buildMethod in CREATION_BUILDS) SkillSpecEditor(c, data).info(active.filter { it.rating > 0 }.map { it.name }.toSet()) else emptyMap(),
                creationBuy = if (!career && s.buildMethod in CREATION_BUILDS) CreationBuy(NuyenLedger.of(c, settings, data), CareerAdvancement(c, data, settings).pluginCapacity()) else null,
                career = if (career) careerLines(c, data, settings, shown.map { it.name }) else null,
                karmaLedger = if (!career && s.buildMethod in CREATION_BUILDS) KarmaLedger.of(c, settings, data) else null,
                points = if (!career) PointsLedger.of(c, data, settings = settings) else null,
                mentors = MentorEditor(c, data).mentors(),
                aiPrograms = if (flag(c, "depenabled")) AiProgramEditor(c, data).programs() else null,
                critterPowers = CritterPowerEditor(c, data).powers().takeIf { it.isNotEmpty() || flag(c, "critter") },
                spirits = if (flag(c, "magenabled")) SpiritEditor(c, data, settings).let { e -> SpiritSection(e.spirits(), e.maxLevel(), e.registeredLimit(), e.spirits().count { it.bound && e.countsFor(it.name) }) } else null,
                initiation = if (career && !flag(c, "initiationdisabled") && (flag(c, "magenabled") || flag(c, "resenabled"))) InitiationEditor(c, data, settings).section() else null,
                complexForms = if (flag(c, "resenabled")) ComplexForms.section(c, data, settings, settings.karmaCost["karmanewcomplexform"] ?: 4) else null,
                sections = SheetSections.read(c, WeaponEngine(c.root, set, str, d.limitPhysical).all(), essenceEngine, data),
            )
        }

        private fun careerLines(c: CharacterFile, data: GameData, settings: CharacterSettings, attributes: List<String>): CareerLines {
            val adv = CareerAdvancement(c, data, settings)
            val log = ExpenseLog(c)
            val rows = c.skills()
            val entries = log.entries()
            return CareerLines(
                attributeCosts = attributes.associateWith { adv.attributeOffer(it).cost },
                skillCosts = rows.associate { it.guid to adv.skillOffer(it.guid).cost },
                skillIds = rows.filter { !it.isKnowledge }.associate { it.name to it.guid },
                groupCosts = c.skillGroups().keys.associateWith { adv.groupOffer(it).cost },
                expenses = entries.reversed(),
                undoable = entries.filter { log.canUndo(it) }.map { it.guid }.toSet(),
                pluginCapacity = adv.pluginCapacity(),
                qualityRemoval = c.root.child("qualities")?.children("quality").orEmpty().mapNotNull { q -> q.text("guid")?.trim()?.let { g -> adv.qualityRemovalCost(g)?.let { g to it } } }.toMap(),
                specCosts = rows.mapNotNull { r -> adv.specializationOffer(r.guid).cost?.let { r.guid to it } }.toMap(),
                specChoices = rows.filter { adv.specializationOffer(it.guid).cost != null }.associate { it.guid to adv.specializationChoices(it.guid) },
            )
        }

        private fun flag(c: CharacterFile, tag: String) = c.root.text(tag)?.trim().equals("True", true)

        private fun qualities(c: CharacterFile): List<QualityLine> =
            c.root.child("qualities")?.children("quality").orEmpty().map {
                QualityLine(
                    it.text("guid").orEmpty().trim(),
                    it.text("name").orEmpty().trim(),
                    it.text("qualitytype")?.trim().equals("Positive", true),
                    it.text("extra").orEmpty().trim(),
                )
            }
    }
}
