package chummer.core.engine

import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spirits: the force and bound limits, the saved layout, and that they stay apart from sprites. */
class SpiritTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key
    private fun magician(): chummer.core.CharacterFile {
        val t = PriorityTable(data).talents("B").first { it.value == "Magician" }
        val c = NewCharacter.createPriority(
            data, settings, NewCharacterRequest(name = "T", metatypeId = human()),
            PriorityChoice("E", "A", "B", "C", "D", "Magician", true, talentSkills = PriorityTable(data).skillChoices(t).take(t.pickCount)),
        )
        CharacterEditor(c, data, settings = settings).setTradition(TraditionCatalog.list(data).first { it.name == "Hermetic" }.id)
        return c
    }

    @Test
    fun spiritsAreBoundUpToCharismaAndNoStrongerThanMagic() {
        val c = magician()
        val spirits = SpiritEditor(c, data, settings)
        val mag = AttributeEngine.forFile(c, settings.toRuleSettings())["MAG"]!!.totalValue
        assertEquals(mag, spirits.maxLevel())
        val options = spirits.options()
        // The tradition's own five come first (Hermetic: fire, air, man, water, earth).
        assertEquals(setOf("Spirit of Fire", "Spirit of Air", "Spirit of Man", "Spirit of Water", "Spirit of Earth"), options.take(5).map { it.name }.toSet())
        val air = options.first { it.name == "Spirit of Air" }.id
        assertNull(spirits.add(air, "Too strong", force = mag + 1, services = 0))
        val guid = spirits.add(air, "Zephyr", force = mag, services = 0)!!
        val s = c.root.child("spirits")!!.children("spirit").single()
        assertEquals(listOf("guid", "name", "crittername", "services", "force", "bound", "fettered", "type", "file", "relative", "notes", "mainmugshotindex", "mugshots"), s.children().map { it.tagName })
        assertEquals("Spirit", s.text("type")); assertEquals("True", s.text("bound"))
        // Past the bound limit the next one is added unbound, not refused; a minion does not count.
        val limit = spirits.registeredLimit()
        repeat(limit) { spirits.add(air, "n$it", force = 1, services = 0) }
        val all = spirits.spirits()
        assertEquals(limit, all.count { it.bound })
        assertFalse(all.last().bound)
        // Sprites and spirits are kept apart.
        assertTrue(SpriteEditor(c, data, settings).sprites().isEmpty())
        assertTrue(spirits.update(guid, critterName = "Gale", force = 1, services = 3))
        assertEquals(SpriteLine(guid, "Spirit of Air", "Gale", 1, 3, true), spirits.spirits().first { it.guid == guid })
        assertTrue(spirits.remove(guid))
        assertNotNull(Sheet.build(c, data, settings).spirits)
    }
}
