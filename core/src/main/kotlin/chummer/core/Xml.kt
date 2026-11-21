package chummer.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Parse an XML file with the JDK DOM parser (available on both JVM and Android). */
fun parseXml(file: File): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    return factory.newDocumentBuilder().parse(file)
}

/** Direct child elements, optionally filtered by tag name. */
fun Element.children(tag: String? = null): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && (tag == null || n.tagName == tag)) out.add(n)
        n = n.nextSibling
    }
    return out
}

fun Element.child(tag: String): Element? {
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && n.tagName == tag) return n
        n = n.nextSibling
    }
    return null
}

/** Text of the named direct child, or null if the child is missing. */
fun Element.text(tag: String): String? = child(tag)?.textContent

fun Element.int(tag: String, default: Int = 0): Int = text(tag)?.trim()?.toIntOrNull() ?: default
