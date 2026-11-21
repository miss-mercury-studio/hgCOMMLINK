package chummer.core.data

import chummer.core.child
import chummer.core.children
import chummer.core.parseXml
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

/** A custom data folder as the settings list it: `GUID>version`, a load order and an enabled flag. */
data class CustomDataRef(val guid: String, val version: String, val order: Int, val enabled: Boolean)

/** A custom data folder found on disk. */
data class CustomDataDir(val dir: File, val guid: String, val name: String)

/**
 * Finds custom data folders by the GUID in their `manifest.xml`. Chummer ships some under
 * `Chummer/customdata`; campaigns add their own, so several roots can be searched.
 */
class CustomDataLocator(roots: List<File>) {
    private val byGuid: Map<String, CustomDataDir> = buildMap {
        for (root in roots) {
            for (dir in root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
                val manifest = File(dir, "manifest.xml")
                if (!manifest.isFile) continue
                val guid = try {
                    parseXml(manifest).documentElement.child("guid")?.textContent?.trim()?.lowercase()
                } catch (e: Exception) {
                    null
                } ?: continue
                putIfAbsent(guid, CustomDataDir(dir, guid, dir.name))
            }
        }
    }

    /** The enabled folders in load order. Folders that cannot be found are returned in [missing]. */
    fun resolve(refs: List<CustomDataRef>): Resolved {
        val found = ArrayList<CustomDataDir>()
        val missing = ArrayList<CustomDataRef>()
        for (r in refs.filter { it.enabled }.sortedBy { it.order }) {
            val d = byGuid[r.guid.lowercase()]
            if (d != null) found += d else missing += r
        }
        return Resolved(found, missing)
    }

    class Resolved(val dirs: List<CustomDataDir>, val missing: List<CustomDataRef>)
}

/**
 * Applies custom data folders to one base data file, in Chummer's order: all `override_` files,
 * then all `custom_` files, then all `amend_` files, each in folder order. A file belongs to a base
 * file when its name ends with `_` plus the base file's name (so `amend_gear.xml` amends `gear.xml`;
 * `amend_weapon.xml` matches nothing, because the base file is `weapons.xml`).
 */
object CustomData {
    fun apply(doc: Document, baseFileName: String, dirs: List<CustomDataDir>) {
        val files = dirs.flatMap { d ->
            d.dir.listFiles { f -> f.isFile && f.name.endsWith("_$baseFileName", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        }
        fun named(prefix: String) = files.filter { it.name.startsWith(prefix, ignoreCase = true) }
        for (f in named("override_")) applyOverride(doc, parseXml(f).documentElement)
        for (f in named("custom_")) applyCustom(doc, parseXml(f).documentElement)
        for (f in named("amend_")) DataAmender.applyFile(doc, parseXml(f).documentElement)
    }

    /** An item is identified by its id, or its name if it has no id. */
    private fun identity(item: Element): String? {
        val id = item.child("id")?.textContent
        if (id != null) return "id = " + DataAmender.cleanXPath(id)
        val name = item.child("name")?.textContent ?: return null
        return "name = " + DataAmender.cleanXPath(name)
    }

    private fun applyOverride(doc: Document, root: Element) {
        val xp = javax.xml.xpath.XPathFactory.newInstance().newXPath()
        for (container in root.children()) {
            for (item in container.children()) {
                val filter = identity(item) ?: continue
                val expr = "/chummer/${container.tagName}/${item.tagName}[$filter]"
                val found = xp.evaluate(expr, doc, javax.xml.xpath.XPathConstants.NODE) as? org.w3c.dom.Node ?: continue
                found.parentNode.replaceChild(doc.importNode(item, true), found)
            }
        }
    }

    private fun applyCustom(doc: Document, root: Element) {
        val xp = javax.xml.xpath.XPathFactory.newInstance().newXPath()
        val docRoot = doc.documentElement
        for (container in root.children()) {
            // Items that already exist (same id or name) are dropped so nothing is duplicated.
            for (item in container.children()) {
                val filter = identity(item) ?: continue
                val expr = "/chummer/${container.tagName}/${item.tagName}[$filter]"
                if (xp.evaluate(expr, doc, javax.xml.xpath.XPathConstants.NODE) != null) container.removeChild(item)
            }
            val existing = docRoot.children(container.tagName).firstOrNull { e ->
                e.attributes.length == container.attributes.length &&
                    (0 until e.attributes.length).all { i -> container.getAttribute(e.attributes.item(i).nodeName) == e.attributes.item(i).nodeValue }
            }
            if (existing != null) {
                var c = container.firstChild
                while (c != null) {
                    existing.appendChild(doc.importNode(c, true))
                    c = c.nextSibling
                }
            } else {
                docRoot.appendChild(doc.importNode(container, true))
            }
        }
    }
}
