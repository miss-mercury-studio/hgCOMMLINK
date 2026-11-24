package chummer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A report names the game data in use, so a problem can be told apart from a data version the app was not checked with. */
class CrashReportGameDataTest {
    @Test
    fun theReportNamesTheGameData() {
        val text = CrashReport.format("0.1.2 (102, play)", "samsung SM-S928U1", "17 (API 37)", "2026-11-24 12:30", "main", "java.lang.Error: x", "Chummer 5.226.0 [b4bfed06]")
        assertTrue(text.lines()[1] == "Game data: Chummer 5.226.0 [b4bfed06]")
        assertFalse(CrashReport.format("0.1.2", "d", "a", "t", "main", "e").contains("Game data"))
    }

    @Test
    fun theBugReportLinkCarriesTheGameDataField() {
        val url = CrashReport.issueUrl("o/r", "bug_report.yml", listOf("bug"), null, linkedMapOf("version" to "0.1.2", "gamedata" to "Chummer 5.226.0 [b4bfed06]"))
        assertTrue(url.contains("gamedata=Chummer%205.226.0%20%5Bb4bfed06%5D"))
        assertEquals(1, Regex("gamedata=").findAll(url).count())
    }
}
