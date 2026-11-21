package chummer.core.data

import chummer.core.children
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Applies a custom data "amend" file to a base data document. This is a port of Chummer's
 * XmlManager.AmendNodeChildren, so it follows Chummer's behaviour exactly, including where
 * that behaviour is surprising (an unknown amendoperation falls through to the default rules).
 *
 * An amend node targets existing nodes with an XPath filter (xpathfilter="name = 'Pistols'"), or
 * by its own id or name child, and carries an operation: remove, replace, append, regexreplace,
 * recurse or addnode. With no operation: recurse if it has child elements, otherwise append if
 * there is no target, otherwise replace.
 *
 * Not ported yet: the bookkeeping that re-creates missing parent nodes when recursing into
 * nodes that do not exist in the base data.
 */
object DataAmender {
    private val xpath = XPathFactory.newInstance().newXPath()
    private val AMEND_ATTRS = listOf("isidnode", "xpathfilter", "amendoperation", "addifnotfound", "regexpattern")

    /** Apply every top-level node of an amend file (the children of its root) to the document. */
    fun applyFile(doc: Document, amendRoot: Element): Boolean {
        var changed = false
        for (node in amendRoot.children()) changed = amend(doc, node, "/chummer") || changed
        return changed
    }

    private fun select(doc: Document, expr: String): List<Node> {
        val list = xpath.evaluate(expr, doc, XPathConstants.NODESET) as NodeList
        return (0 until list.length).map { list.item(it) }
    }

    /** Quote a string for use in an XPath expression (Chummer's CleanXPath). */
    fun cleanXPath(s: String): String = when {
        '\'' !in s -> "'$s'"
        '"' !in s -> "\"$s\""
        else -> "concat(" + s.split('\'').joinToString(", \"'\", ") { "'$it'" } + ")"
    }

    private fun strip(e: Element) {
        for (a in AMEND_ATTRS) if (e.hasAttribute(a)) e.removeAttribute(a)
        e.children().forEach(::strip)
    }

    private fun importStripped(doc: Document, e: Element, deep: Boolean = true): Node {
        val copy = doc.importNode(e, deep)
        if (copy is Element) strip(copy)
        return copy
    }

    private fun amend(doc: Document, amending: Element, xpathBase: String): Boolean {
        var changed = false
        amending.removeAttribute("isidnode")
        var operation = amending.getAttribute("amendoperation").trim().uppercase()
        val hasAddIfNotFound = amending.hasAttribute("addifnotfound")
        var addIfNotFound = amending.getAttribute("addifnotfound").trim().equals("true", true)
        val regexPattern = amending.getAttribute("regexpattern")

        // Which existing nodes does this amend target?
        val filter = StringBuilder()
        if (amending.hasAttribute("xpathfilter")) {
            filter.append(amending.getAttribute("xpathfilter"))
        } else {
            val id = amending.children("id").firstOrNull()
            if (id != null) {
                filter.append("id = ").append(cleanXPath(id.textContent))
            } else {
                val name = amending.children("name").firstOrNull()
                // A name is only used as an identifier if other content is present or this is a removal.
                if (name != null && (operation == "REMOVE" || amending.children().any { it.tagName != "name" })) {
                    filter.append("name = ").append(cleanXPath(name.textContent))
                }
            }
            for (extra in amending.children().filter { it.getAttribute("isidnode").equals("True", true) }) {
                if (filter.isNotEmpty()) filter.append(" and ")
                filter.append(extra.tagName).append(" = ").append(cleanXPath(extra.textContent))
            }
        }
        val filterExpr = if (filter.isNotEmpty()) "[$filter]" else ""

        if (operation == "ADDNODE") {
            val parents = select(doc, xpathBase)
            for (p in parents) p.appendChild(importStripped(doc, amending))
            return parents.isNotEmpty()
        }

        val newXPath = "$xpathBase/${amending.tagName}$filterExpr"
        val targets = select(doc, newXPath)
        val elementChildren = if (operation.isEmpty() || operation == "RECURSE") amending.children() else emptyList()

        when (operation) {
            "REMOVE", "REPLACE", "APPEND" -> {}
            "REGEXREPLACE" -> if (regexPattern.isBlank()) operation = "REPLACE"
            // Recurse needs child elements to recurse into; without them the default rules apply.
            "RECURSE" -> if (elementChildren.isEmpty()) {
                val (op, forceAdd) = defaultOperation(elementChildren, targets.size, hasAddIfNotFound)
                operation = op
                if (forceAdd) addIfNotFound = true
            }
            else -> {
                val (op, forceAdd) = defaultOperation(elementChildren, targets.size, hasAddIfNotFound)
                operation = op
                if (forceAdd) addIfNotFound = true
            }
        }

        if (targets.isNotEmpty() || (operation == "RECURSE" && !addIfNotFound)) {
            if (operation == "RECURSE") {
                for (child in elementChildren) changed = amend(doc, child, newXPath) || changed
            } else {
                for (target in targets) {
                    val parent = target.parentNode
                    when (operation) {
                        "REMOVE" -> parent?.removeChild(target)
                        "REPLACE" -> parent?.replaceChild(importStripped(doc, amending), target)
                        "APPEND" -> appendContents(doc, amending, target)
                        "REGEXREPLACE" -> regexReplace(amending, target, Regex(regexPattern))
                    }
                }
                // Some parents have the target and some do not: add the content to the ones without.
                if (addIfNotFound) {
                    val parents = select(doc, xpathBase)
                    if (parents.size > targets.size) {
                        for (p in parents) {
                            if (select(doc, "$xpathBase/${amending.tagName}$filterExpr").none { it.parentNode === p } &&
                                (p as Element).children(amending.tagName).isEmpty()
                            ) p.appendChild(importStripped(doc, amending))
                        }
                    }
                }
                changed = true
            }
            if (operation == "RECURSE") changed = true
        } else if (operation == "APPEND" || (addIfNotFound && (operation == "RECURSE" || operation == "REPLACE"))) {
            val parents = select(doc, xpathBase)
            for (p in parents) p.appendChild(importStripped(doc, amending))
            changed = parents.isNotEmpty()
        }
        return changed
    }

    /** What an amend with no (or an unknown) operation does, and whether it adds when nothing is found. */
    private fun defaultOperation(children: List<Element>, targetCount: Int, hasAddIfNotFound: Boolean): Pair<String, Boolean> = when {
        children.isNotEmpty() -> "RECURSE" to false
        targetCount == 0 -> "APPEND" to false
        else -> "REPLACE" to !hasAddIfNotFound
    }

    private fun appendContents(doc: Document, amending: Element, target: Node) {
        var n: Node? = amending.firstChild
        if (n == null) return
        while (n != null) {
            when (n.nodeType) {
                Node.COMMENT_NODE -> {}
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    // Text joins the target's existing text node when there is one.
                    var existing: Node? = target.firstChild
                    while (existing != null && existing.nodeType != n.nodeType) existing = existing.nextSibling
                    if (existing != null) existing.nodeValue = existing.nodeValue + n.nodeValue
                    else target.appendChild(doc.importNode(n, true))
                }
                else -> target.appendChild(if (n is Element) importStripped(doc, n) else doc.importNode(n, true))
            }
            n = n.nextSibling
        }
    }

    private fun regexReplace(amending: Element, target: Node, pattern: Regex) {
        var replacement = ""
        var n: Node? = amending.firstChild
        while (n != null) {
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) replacement = n.nodeValue
            n = n.nextSibling
        }
        var t: Node? = target.firstChild
        while (t != null) {
            if (t.nodeType == Node.TEXT_NODE) t.nodeValue = pattern.replace(t.nodeValue, replacement)
            t = t.nextSibling
        }
    }
}
