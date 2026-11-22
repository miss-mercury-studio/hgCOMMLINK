package chummer.core.engine

import chummer.core.data.CharacterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The default.xml profile that ships with the app: what characters saved with an old default.xml fall back to. */
class BundledProfileTest {
    private val src = File(System.getProperty("chummer.src"))
    private val bundled = File("../app/src/main/assets/chummer-profiles")

    @Test
    fun theBundledDefaultIsFoundByItsFileNameAndKeepsSpecializationsStrict() {
        assertTrue("run from the core module", bundled.isDirectory)
        val s = CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), listOf(bundled))
        assertFalse("default.xml was not found", s.isFallback)
        assertEquals("default", s.name)
        assertEquals("SumtoTen", s.buildMethod)
        // The specialization option is off: specializations on skills bought with karma cost karma, as in Chummer's own profiles.
        assertFalse(s.bool("allowpointbuyspecializationsonkarmaskills", true))
    }

    @Test
    fun aUsersOwnFileOfTheSameNameWinsAndUnknownNamesStillFallBack() {
        val dir = File.createTempFile("profiles", "").let { it.delete(); it.mkdirs(); it }
        File(bundled, "default.xml").readText().replace(">False</allowpointbuyspecializationsonkarmaskills>", ">True</allowpointbuyspecializationsonkarmaskills>")
            .also { File(dir, "default.xml").writeText(it) }
        val mine = CharacterSettings.find("default.xml", File(src, "Chummer/data/settings.xml"), listOf(dir, bundled))
        assertTrue(mine.bool("allowpointbuyspecializationsonkarmaskills"))
        val unknown = CharacterSettings.find("nothing.xml", File(src, "Chummer/data/settings.xml"), listOf(bundled))
        assertTrue(unknown.isFallback)
        dir.deleteRecursively()
    }
}
