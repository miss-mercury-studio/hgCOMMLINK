package chummer.core.engine

import chummer.core.DataPack
import chummer.core.data.CharacterSettings
import chummer.core.engine.SkillGroupRules.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issues #3 and #5: a group whose members differ is locked as in Chummer, and its rank shows at once. */
class SkillGroupRulesTest {
    private fun m(base: Int = 0, karma: Int = 0) = Member(base, 0, karma, 0)

    @Test
    fun aFreshGroupCanBeRaisedWithPointsAndKarma() {
        val s = SkillGroupRules.state(listOf(m(), m(), m()), priorityBuild = true, creation = true, settings = null)
        assertTrue(s.baseUnbroken); assertTrue(s.karmaUnbroken); assertFalse(s.locked)
    }

    @Test
    fun aMemberWithPointsOfItsOwnBreaksTheGroup() {
        val s = SkillGroupRules.state(listOf(m(base = 2), m(), m()), priorityBuild = true, creation = true, settings = null)
        assertFalse(s.baseUnbroken); assertFalse(s.karmaUnbroken); assertTrue(s.locked)
    }

    @Test
    fun membersLevelWithEachOtherKeepTheKarmaUnbrokenEvenWhenPointsAreBroken() {
        val s = SkillGroupRules.state(listOf(m(base = 2), m(base = 2), m(karma = 2)), priorityBuild = true, creation = true, settings = null)
        assertFalse(s.baseUnbroken); assertTrue(s.karmaUnbroken); assertFalse(s.locked)
    }

    @Test
    fun karmaOnlyOnASkillDoesNotBreakTheGroup() {
        // The highest own base is 0, and every member is at least that, so the group can still be raised with karma.
        val s = SkillGroupRules.state(listOf(m(karma = 3), m(), m()), priorityBuild = false, creation = false, settings = null)
        assertFalse(s.baseUnbroken); assertTrue(s.karmaUnbroken)
    }

    @Test
    fun theKarmaBuildNeverUsesPoints() {
        val s = SkillGroupRules.state(listOf(m(), m()), priorityBuild = false, creation = true, settings = null)
        assertFalse(s.baseUnbroken); assertTrue(s.karmaUnbroken)
    }

    @Test
    fun aGroupWithNoMembersIsLocked() {
        assertTrue(SkillGroupRules.state(emptyList(), true, true, null).locked)
    }

    // The real data: Athletics has Running, Gymnastics and others.
    private val src = File(System.getProperty("chummer.src"))
    private val data by lazy { DataPack.load(File(src, "Chummer/data")) }
    private val settings by lazy { CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), emptyList()) }
    private fun id(file: String, tag: String, name: String) = data.byId.entries.first { it.value.file == file && it.value.element.tagName == tag && it.value.name == name }.key

    private fun priorityCharacter(): Pair<chummer.core.CharacterFile, CharacterEditor> {
        val choice = PriorityChoice("D", "B", "E", "A", "C", "Mundane", true) // skills A: plenty of skill and group points; Mundane is the talent at E
        val c = NewCharacter.createPriority(data, settings, NewCharacterRequest(name = "Test", metatypeId = id("metatypes.xml", "metatype", "Human")), choice)
        return c to CharacterEditor(c, data, settings = settings)
    }

    @Test
    fun theGroupRankShowsAtOnceAndLocksWhenASkillIsBoughtOnItsOwn() {
        val (c, ed) = priorityCharacter()
        fun athletics() = Sheet.build(c, data, settings).skillGroups.first { it.name == "Athletics" }
        assertEquals(0, athletics().rank)
        assertTrue(ed.changeSkillGroup("Athletics", 1))
        assertEquals("a group bought with points shows its rank straight away", 1, athletics().rank)
        assertFalse(athletics().locked)
        // A member bought on its own with points: the group is broken and locked, like Chummer's.
        assertTrue(ed.changeSkill("Running", 1))
        assertTrue(athletics().locked)
        assertFalse("a locked group cannot be raised", ed.changeSkillGroup("Athletics", 1))
        assertFalse("nor lowered with points", ed.changeSkillGroup("Athletics", -1))
    }
}
