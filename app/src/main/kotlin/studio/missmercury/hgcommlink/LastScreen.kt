package studio.missmercury.hgcommlink

import android.content.Context

/**
 * Where the player was: the character they had open and its tab. It is kept on disk, not in memory, because Android drops the app's process when it
 * has been in the background for a while (switching to a browser and back is enough on some phones), and everything held in memory goes with it.
 * The next launch reads this to put the player back on that screen instead of at the character list.
 *
 * Only the character and its tab are kept: not what a sheet or a scroll position inside it was doing.
 */
class LastScreen(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_screen", Context.MODE_PRIVATE)

    /** The id of the character that was open, or null when the player was on the list. */
    fun fileId(): String? = prefs.getString("file", null)

    /** A character was opened. Its tab starts at the first one unless this is the character that was already open. */
    fun opened(fileId: String) {
        val edit = prefs.edit()
        if (prefs.getString("file", null) != fileId) edit.remove("tab")
        edit.putString("file", fileId).apply()
    }

    /** The player went back to the list, or the character is gone. */
    fun clear() = prefs.edit().clear().apply()

    /** The tab to show for [fileId] (a tab key such as "Gear"), the first one when none is saved for it. */
    fun tab(fileId: String): String = if (prefs.getString("file", null) == fileId) prefs.getString("tab", null) ?: "Overview" else "Overview"

    fun setTab(fileId: String, tab: String) {
        if (prefs.getString("file", null) == fileId) prefs.edit().putString("tab", tab).apply()
    }
}
