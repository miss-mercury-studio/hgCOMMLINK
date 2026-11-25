package chummer.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val supported by lazy { SupportedData.parse(File("../chummer-supported.txt").readText()) }

    private fun zip(vararg entries: Pair<String, String>): File = tmp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z -> for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }

    // --- fingerprint -----------------------------------------------------------------------------------------------------------

    @Test
    fun lineEndingsAndOtherFilesDoNotChangeTheFingerprint() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        File(a, "metatypes.xml").writeText("<x>\n</x>\n"); File(a, "skills.xml").writeText("<s/>\n")
        File(b, "metatypes.xml").writeText("<x>\r\n</x>\r\n"); File(b, "skills.xml").writeText("<s/>\r\n"); File(b, "readme.txt").writeText("x"); File(b, "x.xsd").writeText("y")
        assertEquals(GameDataFingerprint.ofDirectory(a), GameDataFingerprint.ofDirectory(b))
        File(b, "skills.xml").writeText("<s>changed</s>")
        assertFalse(GameDataFingerprint.ofDirectory(a) == GameDataFingerprint.ofDirectory(b))
    }

    /** The data this test run reads is one of the supported data sets, and the app computes the same fingerprint as tools/data_sets.py. */
    @Test
    fun theDataUnderTestIsOnTheSupportedList() {
        val fp = GameDataFingerprint.ofDirectory(File(System.getProperty("chummer.src"), "Chummer/data"))
        assertTrue("The Chummer data under test ($fp) is not in chummer-supported.txt. Run the core tests on it with tools/data_sets.py, then list it.", supported.any { it.fingerprint == fp })
    }

    // --- supported list and version --------------------------------------------------------------------------------------------

    @Test
    fun theSupportedListParsesAndNamesWhereToGetChummer() {
        assertTrue(supported.size >= 2)
        assertTrue(supported.any { it.source == "pin" })
        val rec = SupportedData.recommended(supported)
        assertNotNull(rec)
        assertEquals("https://github.com/chummer5a/chummer5a/releases/tag/${rec!!.releaseTag}", SupportedData.releaseUrl(rec.releaseTag!!))
        assertEquals(64, rec.fingerprint.length)
    }

    @Test
    fun aVersionIsSupportedOnlyWhenItsFingerprintIsListed() {
        val list = listOf(SupportedDataSet("a".repeat(64), "Chummer 5.1.0", "release:5.1.0"))
        val known = GameDataVersion.identify("a".repeat(64), "100", list, "zip", "2026-11-24")
        assertEquals(DataStatus.SUPPORTED, known.status)
        assertEquals("Chummer 5.1.0", known.label)
        val other = GameDataVersion.identify("b".repeat(64), "226", list, "zip", null)
        assertEquals(DataStatus.UNCHECKED, other.status)
        assertEquals("Chummer build 226", other.label)
        assertEquals("Unknown Chummer version", GameDataVersion.identify("c".repeat(64), null, list, "zip", null).label)
        assertEquals("bbbbbbbb", other.short)
        val nightly = GameDataVersion.identify("b".repeat(64), "226", list, "zip", null, "5.226.248", "dcdede6")
        assertEquals("Chummer 5.226.248", nightly.label)
        assertEquals("Chummer 5.226.248 (nightly, unverified) @dcdede6 [bbbbbbbb]", nightly.line)
        assertEquals("Chummer 5.1.0 (custom) [aaaaaaaa]", known.line)
        assertEquals("NIGHTLY", nightly.channel)
        assertEquals("STABLE", GameDataVersion.identify("b".repeat(64), null, list, "zip", null, "5.226.0", "0c354ec").channel)
        assertEquals(null, other.channel)
    }

    private fun versionResource(value: String): ByteArray =
        ByteArray(40) { 7 } + "ProductVersion".toByteArray(Charsets.UTF_16LE) + ByteArray(2) + ByteArray(2) + value.toByteArray(Charsets.UTF_16LE) + ByteArray(2) + ByteArray(20)

    @Test
    fun theExeVersionGivesTheReleaseAndCommit() {
        val nightly = ExeVersion.parse(versionResource("5.226.248+dcdede6e7ea23d126c4943490dfe37479a505bad"))!!
        assertEquals("5.226.248", nightly.release)
        assertEquals("dcdede6", nightly.commit)
        val stable = ExeVersion.parse(versionResource("5.226.0.0+0c354ec2b81da5ccd2c93648f93ec8f6831c030e"))!!
        assertEquals("5.226.0", stable.release)
        assertEquals("0c354ec", stable.commit)
        assertEquals(null, ExeVersion.parse(versionResource("not a version"))?.release)
        assertEquals(null, ExeVersion.parse(ByteArray(100)))
    }

    @Test
    fun aVersionSurvivesBeingSavedAndRead() {
        val v = GameDataVersion.identify("d".repeat(64), "226", emptyList(), "folder", "2026-11-24T10:00")
        assertEquals(v, GameDataVersion.fromText(v.toText()))
        assertNull(GameDataVersion.fromText("garbage"))
    }

    // --- zip import ------------------------------------------------------------------------------------------------------------

    @Test
    fun aReleaseZipWithFlatFoldersIsImported() {
        val z = zip(
            "Chummer5.exe" to "binary", "changelog.txt" to "Future Build 227\nApplication Changes:\n\nBuild 226\nApplication Changes:\n- x\n",
            "data/metatypes.xml" to "<m/>", "data/skills.xml" to "<s/>", "data/skills.xsd" to "schema", "data/sub/deep.xml" to "no",
            "lang/en-us.xml" to "<l/>", "lang/de-de.xml" to "no", "customdata/Neon/manifest.xml" to "<c/>", "customdata/schema.xsd" to "no",
        )
        val staging = tmp.newFolder("staging")
        val r = GameDataZip.extract(z, staging)
        assertEquals("226", r.build)
        assertEquals(4, r.files)
        assertTrue(File(staging, "chummer-data/metatypes.xml").isFile)
        assertTrue(File(staging, "chummer-data/skills.xml").isFile)
        assertFalse(File(staging, "chummer-data/skills.xsd").exists())
        assertFalse(File(staging, "chummer-data/sub").exists())
        assertTrue(File(staging, "chummer-lang/en-us.xml").isFile)
        assertFalse(File(staging, "chummer-lang/de-de.xml").exists())
        assertTrue(File(staging, "chummer-customdata/Neon/manifest.xml").isFile)
        assertFalse(File(staging, "chummer-customdata/schema.xsd").exists())
    }

    @Test
    fun aZipHoldingTheSourceTreeIsImportedFromItsChummerFolder() {
        val z = zip("chummer5a-master/Chummer/data/metatypes.xml" to "<m/>", "chummer5a-master/Chummer/lang/en-us.xml" to "<l/>", "chummer5a-master/README.md" to "x")
        val staging = tmp.newFolder("staging")
        assertEquals(2, GameDataZip.extract(z, staging).files)
        assertTrue(File(staging, "chummer-data/metatypes.xml").isFile)
    }

    @Test
    fun theShallowestDataFolderWinsWhenAZipHoldsTwoCopies() {
        val z = zip("data/metatypes.xml" to "<top/>", "old/Chummer/data/metatypes.xml" to "<old/>")
        val staging = tmp.newFolder("staging")
        GameDataZip.extract(z, staging)
        assertEquals("<top/>", File(staging, "chummer-data/metatypes.xml").readText())
    }

    @Test
    fun aZipWithoutGameDataIsRefusedInPlainWords() {
        try { GameDataZip.extract(zip("readme.txt" to "hi", "src/Program.cs" to "x"), tmp.newFolder("s1")); fail() }
        catch (e: GameDataImportException) { assertTrue(e.message!!.contains("does not hold Chummer's game data")) }
    }

    @Test
    fun aFileThatIsNotAZipIsRefused() {
        val f = tmp.newFile().also { it.writeText("this is not a zip") }
        try { GameDataZip.extract(f, tmp.newFolder("s2")); fail() }
        catch (e: GameDataImportException) { assertEquals("That file is not a zip file.", e.message) }
    }

    @Test
    fun entriesCannotWriteOutsideTheStagingFolder() {
        val staging = tmp.newFolder("staging")
        val z = zip("data/metatypes.xml" to "<m/>", "customdata/../../escape.xml" to "bad", "customdata/../../../escape2.xml" to "bad")
        GameDataZip.extract(z, staging)
        assertFalse(File(staging.parentFile, "escape.xml").exists())
        assertFalse(File(staging.parentFile.parentFile, "escape2.xml").exists())
    }

    @Test
    fun theBuildComesFromTheFirstBuildLineNotTheFutureOne() {
        assertEquals("226", GameDataZip.buildOf("Future Build 227\nApplication Changes:\n\nBuild 226\n- a\n\nBuild 225\n"))
        assertNull(GameDataZip.buildOf("nothing useful here"))
    }
}
