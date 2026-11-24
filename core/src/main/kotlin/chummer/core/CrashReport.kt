package chummer.core

import java.net.URLEncoder

/**
 * The text of a crash or problem report, and the link that opens a pre-filled GitHub issue for it. Pure Kotlin so it can be tested on
 * the desktop; the Android side only supplies the version, the device and the stack trace.
 *
 * Nothing in a report names a character or a file: only the app version, the phone model, the Android version, the time and the
 * stack trace. The player sees the whole text before anything is sent.
 */
object CrashReport {
    /** Browsers and GitHub accept long links, but not unlimited ones; stay well under the usual 8 KB. */
    const val URL_LIMIT = 6500

    /** How much of a stack trace is kept. The top frames and the "Caused by" lines carry the answer. */
    private const val TRACE_LIMIT = 3500

    fun format(app: String, device: String, android: String, time: String, thread: String, trace: String): String = buildString {
        appendLine("hgCOMMLINK $app")
        appendLine("Device: $device")
        appendLine("Android: $android")
        appendLine("Time: $time")
        appendLine("Thread: $thread")
        appendLine()
        append(trimTrace(trace))
    }

    /** Keeps the start of the trace and every "Caused by" line (with the frame after it), and drops the middle when it is too long. */
    fun trimTrace(trace: String, limit: Int = TRACE_LIMIT): String {
        val text = trace.trim()
        if (text.length <= limit) return text
        val lines = text.lines()
        val keep = BooleanArray(lines.size)
        for (i in lines.indices) if (i < 14 || lines[i].startsWith("Caused by")) {
            keep[i] = true
            if (i + 1 < lines.size) keep[i + 1] = true
        }
        val out = StringBuilder()
        var skipped = false
        for (i in lines.indices) {
            if (keep[i]) { out.appendLine(lines[i]); skipped = false }
            else if (!skipped) { out.appendLine("\t... frames left out ..."); skipped = true }
        }
        return out.toString().trim().take(limit)
    }

    /** "IllegalArgumentException: Key "a-" was already used" from the first line of a trace, for an issue title. */
    fun headline(trace: String): String {
        val first = trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        // Drop the package from the class name only, not from the message after it.
        val cls = first.substringBefore(':')
        val short = cls.substringAfterLast('.') + first.substring(cls.length)
        return short.take(90)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * A link to GitHub's new-issue page with the issue form ([template], such as `bug_report.yml`) filled in. The keys are the form
     * fields' ids. If the link would be too long, the crash text is cut until it fits.
     */
    fun issueUrl(repo: String, template: String, labels: List<String>, title: String?, fields: Map<String, String>, crash: String? = null): String {
        fun build(crashText: String?): String {
            val parts = ArrayList<String>()
            parts += "template=" + enc(template)
            if (labels.isNotEmpty()) parts += "labels=" + enc(labels.joinToString(","))
            if (title != null) parts += "title=" + enc(title)
            for ((k, v) in fields) parts += enc(k) + "=" + enc(v)
            if (crashText != null) parts += "crash=" + enc(crashText)
            return "https://github.com/$repo/issues/new?" + parts.joinToString("&")
        }
        var text = crash
        var url = build(text)
        while (text != null && url.length > URL_LIMIT && text.length > 200) {
            text = text.take((text.length * 0.8).toInt())
            url = build(text + "\n... cut to fit ...")
        }
        return url
    }
}
