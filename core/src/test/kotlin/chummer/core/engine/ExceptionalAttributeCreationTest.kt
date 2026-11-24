package chummer.core.engine

import chummer.core.DataPack
import chummer.core.child
import chummer.core.children
import chummer.core.data.CharacterSettings
import chummer.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issue #1: Exceptional Attribute on Magic must let priority special attribute points take Magic to 7. */
class ExceptionalAttributeCreationTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun id(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    private fun magician(): Pair<chummer.core.CharacterFile, CharacterEditor> {
        // Metatype E (Human, 1 special point), attributes B, talent A (Magic 6), skills C, resources D.
        val choice = PriorityChoice("E", "B", "A", "C", "D", "Magician", true)
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "Test", metatypeId = id("metatypes.xml", "metatype", "Human")), choice)
        return c to CharacterEditor(c, data, settings = settings)
    }

    private fun magic(c: chummer.core.CharacterFile) = c.root.child("attributes")!!.children("attribute").first { it.text("name") == "MAG" }

    @Test
    fun exceptionalAttributeOnMagicRaisesTheMaximumToSeven() {
        val (c, editor) = magician()
        val rules = settings.toRuleSettings()
        assertEquals(6, AttributeEngine.forFile(c, rules)["MAG"]!!.totalMaximum)
        editor.addQuality(id("qualities.xml", "quality", "Exceptional Attribute"), extra = "MAG")
        assertEquals(7, AttributeEngine.forFile(c, rules)["MAG"]!!.totalMaximum)
    }

    @Test
    fun aSpecialAttributePointTakesMagicFromSixToSeven() {
        val (c, editor) = magician()
        editor.addQuality(id("qualities.xml", "quality", "Exceptional Attribute"), extra = "MAG")
        val before = AttributeEngine.forFile(c, settings.toRuleSettings())["MAG"]!!.value
        assertEquals(6, before)
        assertTrue("Magic should rise to 7 with the special attribute point", editor.changeAttribute("MAG", 1))
        assertEquals(7, AttributeEngine.forFile(c, settings.toRuleSettings())["MAG"]!!.value)
        assertEquals(Pool(1, 1), PointsLedger.of(c, data)!!.special)
    }
}
