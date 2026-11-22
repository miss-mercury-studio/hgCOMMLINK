package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** A martial arts style for a picker, with the techniques the data lists for it. */
data class MartialArtOption(val id: String, val name: String, val source: String, val page: String, val techniques: List<String>, val unsupported: List<String>)

/** A technique for a picker. */
data class TechniqueOption(val id: String, val name: String, val source: String, val page: String, val unsupported: List<String>)

object MartialArtCatalog {
    fun arts(data: DataPack): List<MartialArtOption> =
        data.byId.entries.filter { it.value.file == "martialarts.xml" && it.value.element.tagName == "martialart" }.map { (id, e) ->
            val el = e.element
            MartialArtOption(
                id, el.text("name")?.trim().orEmpty(), el.text("source")?.trim().orEmpty(), el.text("page")?.trim().orEmpty(),
                el.child("techniques")?.children("technique").orEmpty().mapNotNull { it.text("name")?.trim() },
                el.child("bonus")?.let { BonusHandlers.apply(it, BonusContext("MartialArt", "", 1)).unhandled.distinct() }.orEmpty(),
            )
        }.sortedBy { it.name.lowercase() }

    /** The techniques of one style, as the data names them (every technique can be taken by any style in Chummer's list, but a style names its own). */
    fun techniquesFor(data: DataPack, artId: String): List<TechniqueOption> {
        val names = data[artId]?.element?.child("techniques")?.children("technique").orEmpty().mapNotNull { it.text("name")?.trim() }.toSet()
        return data.byId.entries.filter { it.value.file == "martialarts.xml" && it.value.element.tagName == "technique" && it.value.name in names }.map { (id, e) ->
            val el = e.element
            TechniqueOption(
                id, el.text("name")?.trim().orEmpty(), el.text("source")?.trim().orEmpty(), el.text("page")?.trim().orEmpty(),
                el.child("bonus")?.let { BonusHandlers.apply(it, BonusContext("MartialArtTechnique", "", 1)).unhandled.distinct() }.orEmpty(),
            )
        }.sortedBy { it.name.lowercase() }
    }
}

/**
 * Martial arts on a character, in the layout Chummer writes (checked against the fixtures: a style with its techniques nested under it,
 * a style's bonus kept as improvements from the `MartialArt` source and a technique's from `MartialArtTechnique`, both with the item's GUID
 * as the source name). What a style costs is the karma ledger's business: the style's karma plus the technique price for every
 * technique after the first.
 */
class MartialArtEditor(private val file: CharacterFile, private val data: GameData) {
    private val doc get() = file.doc
    private val root get() = file.root
    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    fun art(guid: String) = root.child("martialarts")?.children("martialart")?.firstOrNull { it.text("guid")?.trim().equals(guid, true) }

    /** Add a style; returns its GUID. The same style cannot be taken twice. */
    fun addArt(entryId: String): String {
        val entry = data[entryId]?.element ?: error("No martial art with id $entryId")
        require(entry.tagName == "martialart") { "Not a martial art" }
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        require(root.child("martialarts")?.children("martialart").orEmpty().none { it.text("name")?.trim() == d("name") }) { "Already known" }
        val guid = UUID.randomUUID().toString()
        val a = el("martialart")
        fun add(tag: String, text: String? = null) = a.appendChild(el(tag, text))
        add("name", d("name")); add("guid", guid); add("source", d("source")); add("page", d("page")); add("rating", "1")
        add("cost", d("cost").ifEmpty { "7" }); add("isquality", "False"); a.appendChild(el("martialarttechniques")); add("notes")
        (root.child("martialarts") ?: el("martialarts").also { root.appendChild(it) }).appendChild(a)
        entry.child("bonus")?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext("MartialArt", guid, 1, "", d("name"), objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    /** Add a technique to the style with [artGuid]; returns its GUID. */
    fun addTechnique(artGuid: String, entryId: String): String {
        val art = art(artGuid) ?: error("No martial art with that GUID")
        val entry = data[entryId]?.element ?: error("No technique with id $entryId")
        require(entry.tagName == "technique") { "Not a technique" }
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val list = art.child("martialarttechniques") ?: el("martialarttechniques").also { art.appendChild(it) }
        require(list.children("martialarttechnique").none { it.text("name")?.trim() == d("name") }) { "Already known" }
        val guid = UUID.randomUUID().toString()
        val t = el("martialarttechnique")
        fun add(tag: String, text: String? = null) = t.appendChild(el(tag, text))
        add("guid", guid); add("sourceid", entryId.lowercase()); add("name", d("name")); add("notes"); add("source", d("source")); add("page", d("page"))
        list.appendChild(t)
        entry.child("bonus")?.let { bonus ->
            val result = BonusHandlers.apply(bonus, BonusContext("MartialArtTechnique", guid, 1, "", d("name"), objects = EditorObjects(file, data)))
            val imps = root.child("improvements") ?: el("improvements").also { root.appendChild(it) }
            for (imp in result.improvements) imps.appendChild(ImprovementWriter.toElement(doc, imp))
        }
        return guid
    }

    private fun removeImprovements(vararg guids: String) {
        val ids = guids.map { it.lowercase() }.toSet()
        val imps = root.child("improvements") ?: return
        for (i in imps.children("improvement").filter { it.text("sourcename")?.trim()?.lowercase() in ids }) imps.removeChild(i)
    }

    /** Take a style out, with its techniques and everything they gave. */
    fun removeArt(guid: String): Boolean {
        val a = art(guid) ?: return false
        removeImprovements(guid, *a.child("martialarttechniques")?.children("martialarttechnique").orEmpty().mapNotNull { it.text("guid")?.trim() }.toTypedArray())
        a.parentNode.removeChild(a)
        return true
    }

    fun removeTechnique(guid: String): Boolean {
        val t = root.child("martialarts")?.children("martialart").orEmpty().flatMap { it.child("martialarttechniques")?.children("martialarttechnique").orEmpty() }
            .firstOrNull { it.text("guid")?.trim().equals(guid, true) } ?: return false
        removeImprovements(guid)
        t.parentNode.removeChild(t)
        return true
    }
}
