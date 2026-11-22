package chummer.core.engine

import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Specializations in creation: one per skill with a rating, from the data's list, costing a skill point. */
class CreationSpecializationTest {
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun human() = data.byId.entries.first { it.value.file == "metatypes.xml" && it.value.element.tagName == "metatype" && it.value.name == "Human" }.key

    @Test
    fun aSkillWithARatingTakesOneSpecializationForASkillPoint() {
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "T", metatypeId = human()), PriorityChoice("D", "A", "E", "B", "C", "Mundane", true))
        val editor = CharacterEditor(c, data, settings = settings)
        assertTrue(editor.changeSkill("Pistols", 1)); assertTrue(editor.changeSkill("Pistols", 1))
        val info = { SkillSpecEditor(c, data).info(setOf("Pistols"))["Pistols"]!! }
        val i = info()
        assertTrue(i.canAdd)
        assertTrue(i.choices.toString(), i.choices.isNotEmpty())
        // A skill without a rating cannot be specialized.
        val other = SkillSpecEditor(c, data).info(setOf("Pistols")).values.first { it.skillGuid != i.skillGuid }
        assertNull(editor.addSpecialization(other.skillGuid, "Anything", hasRating = false))
        val before = PointsLedger.of(c, data, settings = settings)!!.skills
        val guid = editor.addSpecialization(i.skillGuid, i.choices.first(), hasRating = true)
        assertNotNull(guid)
        // It costs a skill point, only one is allowed, and the sheet shows it.
        assertEquals(before.used + 1, PointsLedger.of(c, data, settings = settings)!!.skills.used)
        assertNull(editor.addSpecialization(i.skillGuid, i.choices.last(), hasRating = true))
        assertEquals(i.choices.first(), info().spec)
        assertFalse(info().canAdd)
        assertEquals(i.choices.first(), Sheet.build(c, data, settings).skillSpecs["Pistols"]!!.spec)
        assertTrue(editor.removeSpecialization(guid!!))
        assertEquals(before.used, PointsLedger.of(c, data, settings = settings)!!.skills.used)
    }
}
