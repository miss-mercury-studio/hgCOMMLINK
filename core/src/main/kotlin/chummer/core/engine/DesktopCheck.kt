package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.children

/**
 * Things in a character that desktop Chummer is known not to open. Each rule here is there because a real file failed in
 * Chummer; add one only when that has happened, so a warning always means something. A warning never stops anything.
 */
object DesktopCheck {
    /** Plain-words problems, empty when nothing known is wrong. */
    fun problems(c: CharacterFile): List<String> = buildList {
        // Chummer stalls while loading an A.I. that has cyberware or bioware (found with a 0.1.5 character).
        if (isAI(c) && c.root.child("cyberwares")?.children("cyberware").orEmpty().isNotEmpty()) {
            add("Chummer hangs on an A.I. with ware.")
        }
    }
}
