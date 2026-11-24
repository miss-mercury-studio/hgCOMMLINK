package chummer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {
    @Test
    fun readsReleasesAndSkipsUnreleased() {
        val r = Changelog.parse(
            """
            # Changelog
            Intro text.
            ## [Unreleased]
            - not shown
            ## [0.2.0] - 2026-12-01
            ### Added
            - First thing
              that wraps
            ### Fixed
            - Second thing
            ## [0.1.0] - 2026-11-22
            - A bare bullet
            """.trimIndent(),
        )
        assertEquals(listOf("0.2.0", "0.1.0"), r.map { it.version })
        assertEquals("2026-12-01", r[0].date)
        assertEquals(listOf("Added", "Fixed"), r[0].sections.map { it.title })
        assertEquals(listOf("First thing that wraps"), r[0].sections[0].items)
        assertEquals("", r[1].sections.single().title)
    }

    /** The real file: every release has a date, and the newest version matches app.versionName so a release cannot ship with a stale log. */
    @Test
    fun shippedChangelogMatchesTheAppVersion() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").isFile }
        val releases = Changelog.parse(File(root, "CHANGELOG.md").readText())
        assertTrue(releases.isNotEmpty())
        assertTrue(releases.all { it.date.isNotBlank() && it.sections.isNotEmpty() })
        val version = File(root, "gradle.properties").readLines().first { it.startsWith("app.versionName=") }.substringAfter("=").trim()
        assertEquals(version, releases.first().version)
    }
}
