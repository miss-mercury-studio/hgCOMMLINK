package chummer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    private val trace = """
        java.lang.IllegalArgumentException: Key "a-" was already used.
        	at androidx.compose.ui.internal.InlineClassHelperKt.throwIllegalArgumentException(InlineClassHelper.kt:36)
        	at studio.missmercury.hgcommlink.SheetScreenKt.Skills(SheetScreen.kt:406)
    """.trimIndent()

    @Test
    fun theReportHoldsTheVersionDeviceAndTraceAndNothingElse() {
        val r = CrashReport.format("0.1.2 (102)", "samsung SM-S928U1", "17 (API 37)", "2026-11-24 12:30", "main", trace)
        assertTrue(r.startsWith("hgCOMMLINK 0.1.2 (102)"))
        assertTrue(r.contains("Device: samsung SM-S928U1"))
        assertTrue(r.contains("Key \"a-\" was already used"))
    }

    @Test
    fun aLongTraceKeepsTheTopAndTheCausedByLines() {
        val deep = (1..400).joinToString("\n") { "\tat some.deep.Frame$it(File.kt:$it)" }
        val long = "java.lang.RuntimeException: outer\n$deep\nCaused by: java.lang.IllegalStateException: the real cause\n\tat the.Cause.here(Cause.kt:1)"
        val t = CrashReport.trimTrace(long)
        assertTrue(t.length <= 3500)
        assertTrue(t.startsWith("java.lang.RuntimeException: outer"))
        assertTrue("the root cause survives", t.contains("Caused by: java.lang.IllegalStateException: the real cause"))
        assertTrue(t.contains("frames left out"))
    }

    @Test
    fun aShortTraceIsUntouched() = assertEquals(trace, CrashReport.trimTrace(trace))

    @Test
    fun theHeadlineIsTheExceptionAndItsMessage() =
        assertEquals("IllegalArgumentException: Key \"a-\" was already used.", CrashReport.headline(trace))

    @Test
    fun theIssueLinkIsEncodedAndFillsTheFormFields() {
        val url = CrashReport.issueUrl("org/repo", "bug_report.yml", listOf("bug", "from-app"), "Crash: x y", mapOf("version" to "0.1.2", "device" to "S24 Ultra"), "line one\nline & two")
        assertTrue(url.startsWith("https://github.com/org/repo/issues/new?template=bug_report.yml"))
        assertTrue(url.contains("labels=bug%2Cfrom-app"))
        assertTrue(url.contains("title=Crash%3A%20x%20y"))
        assertTrue(url.contains("version=0.1.2"))
        assertTrue(url.contains("crash=line%20one%0Aline%20%26%20two"))
        assertFalse("no raw spaces", url.contains(" "))
    }

    @Test
    fun aHugeCrashTextIsCutSoTheLinkStaysUsable() {
        val url = CrashReport.issueUrl("org/repo", "bug_report.yml", listOf("bug"), "t", mapOf("version" to "1"), "x".repeat(50_000))
        assertTrue(url.length <= CrashReport.URL_LIMIT + 100)
        assertTrue(url.contains("cut%20to%20fit"))
    }
}
