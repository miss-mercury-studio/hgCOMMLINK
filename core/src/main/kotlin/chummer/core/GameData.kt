package chummer.core

import org.w3c.dom.Element

/**
 * Read access to Chummer's game data, looked up by the GUID a character stores in `<sourceid>`.
 * The rest of the app depends on this interface, not on how the data is held in memory,
 * so the storage can change (lazy loading, a compact index) without touching callers.
 */
interface GameData {
    /** The data entry with this GUID (case-insensitive), or null. */
    operator fun get(id: String): DataEntry?
}

/** One entry from the data files. [element] is the raw XML so no field is lost. */
class DataEntry(val file: String, val element: Element) {
    val name: String? get() = element.text("name")
    val category: String? get() = element.text("category")
}
