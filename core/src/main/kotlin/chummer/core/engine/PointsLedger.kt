package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text

/** One pool of free points: how many there are and how many are spent. */
data class Pool(val total: Int, val used: Int) {
    val left get() = total - used
}

/**
 * The free points of a Priority or Sum-to-Ten character: attribute points, special attribute points (Edge, Magic, Resonance),
 * skill points and skill group points. The totals come from the priority letters ([PriorityTable]); what is spent is read from
 * the `base` of the rows, which is how Chummer stores points in creation (a rating is the metatype minimum plus base plus karma).
 * Checked against the 5.202 fixtures: the attribute bases add up to exactly the attribute points, and the skill group bases to
 * exactly the group points.
 */
data class PointsLedger(val attributes: Pool, val special: Pool, val skills: Pool, val groups: Pool) {
    companion object {
        val STANDARD = setOf("BOD", "AGI", "REA", "STR", "CHA", "INT", "LOG", "WIL")
        val SPECIAL = setOf("EDG", "MAG", "MAGAdept", "RES", "DEP")

        /** True for the build methods that give free points. */
        fun usesPoints(c: CharacterFile) = c.root.text("buildmethod")?.trim() in setOf("Priority", "SumtoTen")

        fun of(c: CharacterFile, data: GameData?, tableName: String = "Standard"): PointsLedger? {
            if (!usesPoints(c)) return null
            val pack = data as? DataPack ?: return null
            val a = PriorityTable(pack).allotment(c, tableName) ?: return null
            fun base(e: org.w3c.dom.Element) = e.text("base")?.trim()?.toIntOrNull() ?: 0
            val attrs = c.root.child("attributes")?.children("attribute").orEmpty()
            val standardUsed = attrs.filter { it.text("name")?.trim() in STANDARD }.sumOf(::base)
            val specialUsed = attrs.filter { it.text("name")?.trim() in SPECIAL }.sumOf(::base)
            val skillsUsed = c.root.child("newskills")?.child("skills")?.children("skill").orEmpty()
                .filter { it.text("isknowledge")?.trim() != "True" }.sumOf(::base)
            val groupsUsed = c.root.child("newskills")?.child("groups")?.children("group").orEmpty().sumOf(::base)
            return PointsLedger(
                Pool(a.attributePoints, standardUsed), Pool(a.specialPoints, specialUsed),
                Pool(a.skillPoints, skillsUsed), Pool(a.skillGroupPoints, groupsUsed),
            )
        }
    }
}
