package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text

/**
 * Summoned and bound spirits, kept in the same `spirits` list as sprites with type `Spirit`. Rules from Chummer: the force is at most
 * Magic (twice that once created; the shown value or the augmented one by the `spiritforcebasedontotalmag` setting), the number of bound
 * spirits at most the `boundspiritexpression` setting (Charisma by default), and a spirit added past that limit starts unbound. Watchers
 * and homunculi are ritual minions and do not count. The spirits offered are those of the tradition (combat, detection, health, illusion
 * and manipulation) and the other spirits in the critter data.
 */
class SpiritEditor(file: CharacterFile, data: GameData, settings: CharacterSettings) : SpriteEditor(file, data, settings, "Spirit") {
    private fun flag(tag: String) = root.text(tag)?.trim().equals("True", true)

    override fun countsAgainst(name: String) = name != "Watcher" && name != "Homunculus"

    override fun options(): List<SpriteOption> {
        val pack = data as? DataPack ?: return emptyList()
        val tradition = root.child("tradition")
        val own = listOf("spiritcombat", "spiritdetection", "spirithealth", "spiritillusion", "spiritmanipulation")
            .mapNotNull { tradition?.text(it)?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val all = pack.byId.entries.filter { it.value.file == "critters.xml" && it.value.element.tagName == "metatype" && it.value.element.text("category")?.trim() == "Spirits" }
            .map { (id, e) -> SpriteOption(id, e.element.text("name")?.trim().orEmpty(), e.element.text("source")?.trim().orEmpty()) }
        // The tradition's own five first, so the common choices are at the top.
        return all.sortedWith(compareBy({ if (it.name in own) 0 else 1 }, { it.name.lowercase() }))
    }

    override fun maxLevel(): Int {
        if (!flag("magenabled")) return 0
        val mag = AttributeEngine.forFile(file, settings.toRuleSettings())["MAG"]
        val v = if (settings.bool("spiritforcebasedontotalmag")) mag?.totalValue ?: 0 else mag?.value ?: 0
        return if (v <= 0) 0 else if (created) v * 2 else v
    }

    override fun registeredLimit(): Int = standardRound(
        settings.evaluate("boundspiritexpression", mapOf("CHA" to attribute("CHA").toDouble(), "LOG" to attribute("LOG").toDouble()), "{CHA}"),
    )

    fun spirits() = sprites()
    fun countsFor(name: String) = countsAgainst(name)
}

/** What the Magic tab shows about spirits: the list, the highest force, and how many may be bound. */
data class SpiritSection(val spirits: List<SpriteLine>, val maxForce: Int, val boundLimit: Int, val bound: Int)
