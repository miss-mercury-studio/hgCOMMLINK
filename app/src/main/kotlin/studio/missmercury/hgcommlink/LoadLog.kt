package studio.missmercury.hgcommlink

import androidx.compose.runtime.mutableStateListOf

/**
 * What the app is really doing during a wait, one short line at a time, for the terminal-style loading screens. Lines may be added from any
 * thread; a screen reads [lines] and redraws as they arrive. [storage] is the scan of the characters folder, [data] the opening of a
 * character or a new-character dialog (game data, custom data, settings).
 */
class LoadLog {
    val lines = mutableStateListOf<String>()
    fun reset() { lines.clear() }
    fun add(line: String) { lines.add(line) }

    companion object {
        val storage = LoadLog()
        val data = LoadLog()
    }
}
