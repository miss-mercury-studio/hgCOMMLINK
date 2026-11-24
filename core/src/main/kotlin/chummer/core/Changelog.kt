package chummer.core

/** One released version in CHANGELOG.md: `## [0.1.1] - 2026-11-24`, with its `###` sections of `-` lines. */
data class ChangelogRelease(val version: String, val date: String, val sections: List<ChangelogSection>)

data class ChangelogSection(val title: String, val items: List<String>)

/**
 * Reads the Keep-a-Changelog style file the app ships. Only released versions come back, newest first as written;
 * "Unreleased" and any text outside a version are skipped. A bullet's continuation lines (indented) join onto it.
 */
object Changelog {
    private val releaseHeading = Regex("""^##\s+\[?([0-9][^\]\s]*)]?\s*(?:[-–—]\s*(.*))?$""")

    fun parse(text: String): List<ChangelogRelease> {
        val releases = ArrayList<ChangelogRelease>()
        var version: String? = null
        var date = ""
        var sections = ArrayList<ChangelogSection>()
        var title: String? = null
        var items = ArrayList<String>()
        val intro = ArrayList<String>()

        fun closeSection() {
            if (items.isNotEmpty()) sections.add(ChangelogSection(title ?: "", items))
            items = ArrayList()
        }
        fun closeRelease() {
            closeSection()
            version?.let { releases.add(ChangelogRelease(it, date, sections)) }
            sections = ArrayList()
            title = null
        }

        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    closeRelease()
                    val m = releaseHeading.matchEntire(line)
                    version = m?.groupValues?.get(1)
                    date = m?.groupValues?.get(2)?.trim().orEmpty()
                }
                version == null -> intro.add(line)
                line.startsWith("### ") -> { closeSection(); title = line.removePrefix("### ").trim() }
                line.startsWith("- ") -> items.add(line.removePrefix("- ").trim())
                line.isNotBlank() && line[0].isWhitespace() && items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + line.trim()
            }
        }
        closeRelease()
        return releases
    }
}
