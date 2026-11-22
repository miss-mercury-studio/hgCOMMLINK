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
) {
    data class SpellLine(val guid: String, val name: String, val category: String, val type: String, val range: String, val duration: String, val dv: String, val descriptors: String)
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
            val skillEngine = SkillEngine(set, attrs, rules, career, magicEnabled = magic, resonanceEnabled = resonance)
            val groups = c.skillGroups()
            val skills = SkillEngine.skillsOf(c, data).map { raw ->
                raw to skillEngine.calculate(raw, groups[raw.group]?.first ?: 0, groups[raw.group]?.second ?: 0)
            }
            val active = skills.filter { !it.first.isKnowledge && it.second.enabled }.map { it.second }.sortedBy { it.name }
            val knowledge = skills.filter { it.first.isKnowledge }.map { it.second }.sortedBy { it.name }

            val s = c.summary()
            val shown = ORDER.mapNotNull { n ->
                val a = attrs[n] ?: return@mapNotNull null
                if ((n == "MAG" && !magic) || (n == "RES" && !resonance) || n == "DEP") return@mapNotNull null
                AttributeLine(n, a.totalValue, a.totalMaximum)
            }
            val res = d.resistances()
            return Sheet(
                name = s.name, alias = s.alias, metatype = s.metatype, buildMethod = s.buildMethod, isCareer = career,
                karma = s.karma, nuyen = s.nuyen, attributes = shown, essence = essence,
                limits = Limits(d.limitPhysical, d.limitMental, d.limitSocial, d.limitAstral),
                initiativeValue = d.initiative.value, initiativeDice = d.initiative.dice,
                physicalCM = d.physicalCM, stunCM = d.stunCM, cmOverflow = d.cmOverflow,
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
                magic = if (flag(c, "magenabled")) MagicLines(
                    c.root.child("tradition")?.text("name")?.trim().orEmpty(),
                    c.root.child("spells")?.children("spell").orEmpty().map {
                        SpellLine(
                            it.text("guid").orEmpty().trim(), it.text("name").orEmpty().trim(), it.text("category").orEmpty().trim(),
                            it.text("type").orEmpty().trim(), it.text("range").orEmpty().trim(), it.text("duration").orEmpty().trim(),
                            it.text("dv").orEmpty().trim(), it.text("descriptors").orEmpty().trim(),
                        )
                    },
                ) else null,
                karmaLedger = if (!career && s.buildMethod == "Karma") KarmaLedger.of(c, settings, data) else null,
                sections = SheetSections.read(c, WeaponEngine(c.root, set, str, d.limitPhysical).all(), essenceEngine),
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
