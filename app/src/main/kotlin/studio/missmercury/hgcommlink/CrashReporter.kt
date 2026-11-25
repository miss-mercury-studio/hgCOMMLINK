package studio.missmercury.hgcommlink

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import chummer.core.CrashReport
import chummer.core.data.GameDataVersion
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installed once, before any screen: remembers the last crash so the next launch can offer to report it. */
class HgApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

/**
 * Crash reports without a server. When the app dies, its stack trace is written to a file in the app's private storage. The next launch
 * shows the text and lets the player open a pre-filled GitHub issue or share it anywhere (Discord, mail). Nothing is sent by the app
 * itself: it asks for no network permission, and the player sees the whole report first. It holds no character names or files.
 */
object CrashReporter {
    /** Where issues go. GitHub redirects renamed or moved repos, but keep this current. */
    const val REPO = "miss-mercury-studio/hgCOMMLINK"

    private fun file(context: Context) = File(File(context.filesDir, "crash").apply { mkdirs() }, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(app).writeText(
                    CrashReport.format(appVersion(app), device(), android(), now(), thread.name, error.stackTraceToString(), gameData(app)),
                )
            } catch (_: Throwable) {
                // Never let reporting hide the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report saved by the last crash, or null. */
    fun pending(context: Context): String? = file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    /** The game data in use, from what was saved when it was last identified (a crash report must not load the data). */
    fun gameData(context: Context): String? = GameDataInfo.saved(context)?.let { v ->
        GameDataVersion.identify(v.fingerprint, v.build, GameDataInfo.supported(context), v.source, v.importedAt, v.release, v.commit).line
    }

    fun device() = "${Build.MANUFACTURER} ${Build.MODEL}"
    fun android() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    /** A pre-filled "Bug report" issue. [report] is the crash text, or null for a plain problem report. */
    fun bugUrl(context: Context, report: String?): String {
        val fields = linkedMapOf("version" to appVersion(context), "device" to "${device()}, Android ${android()}")
        gameData(context)?.let { fields["gamedata"] = it }
        if (report != null) fields["steps"] = "The app closed on me while I was: "
        val title = report?.substringAfter("\n\n", report)?.let { "Crash: " + CrashReport.headline(it) }
        return CrashReport.issueUrl(REPO, "bug_report.yml", listOf("bug", "from-app"), title, fields, report)
    }

    /** A pre-filled "Feedback" issue. */
    fun feedbackUrl(context: Context): String =
        CrashReport.issueUrl(REPO, "feedback.yml", listOf("feedback", "from-app"), null, emptyMap())

    /** Opens a link in the browser; false if the phone has none. */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** The system share sheet with the report as text, for Discord, mail or notes. */
    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "hgCOMMLINK crash report").putExtra(Intent.EXTRA_TEXT, report)
        context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
