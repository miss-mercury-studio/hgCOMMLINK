package chummer.core.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Chummer's built-in profiles (Full House) list custom data by folder name, older profiles by GUID. Both must find the folder. */
class CustomDataByNameTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun foldersAreFoundByGuidOrByName() {
        val root = tmp.newFolder("customdata")
        for ((name, guid) in listOf("Alpha Errata" to "11111111-1111-1111-1111-111111111111", "Beta Errata" to "22222222-2222-2222-2222-222222222222")) {
            val dir = java.io.File(root, name).apply { mkdirs() }
            java.io.File(dir, "manifest.xml").writeText("<manifest><guid>$guid</guid><version>1</version></manifest>")
        }
        val refs = listOf(
            CustomDataRef("alpha errata", "", 0, true),
            CustomDataRef("22222222-2222-2222-2222-222222222222", "1.0", 1, true),
            CustomDataRef("Missing Folder", "", 2, true),
        )
        val resolved = CustomDataLocator(listOf(root)).resolve(refs)
        assertEquals(listOf("Alpha Errata", "Beta Errata"), resolved.dirs.map { it.name })
        assertEquals(listOf("Missing Folder"), resolved.missing.map { it.guid })
    }
}
