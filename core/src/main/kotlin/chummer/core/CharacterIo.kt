package chummer.core

import org.w3c.dom.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Reading and writing `.chum5` files.
 *
 * Writing re-serializes the same DOM that was read, so anything we do not understand
 * (including content added by newer Chummer versions) is written back unchanged.
 * Chummer's own files start with a UTF-8 byte order mark, so we write one too.
 */
object CharacterIo {
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun parse(input: InputStream): Document {
        val buffered = if (input.markSupported()) input else input.buffered()
        // Characters are untrusted input (shared files). Chummer files never declare a DTD, and
        // entity tricks need one, so refuse any file whose prolog has a DOCTYPE or ENTITY.
        // Checked on the raw bytes because not every XML parser (Android's, for one) supports
        // the "disallow-doctype-decl" feature.
        buffered.mark(PROLOG_BYTES)
        val head = ByteArray(PROLOG_BYTES)
        val n = buffered.read(head).coerceAtLeast(0)
        buffered.reset()
        val prolog = String(head, 0, n, Charsets.ISO_8859_1)
        val rootAt = prolog.indexOf("<character")
        require(!prolog.substring(0, if (rootAt >= 0) rootAt else prolog.length).let { "<!DOCTYPE" in it || "<!ENTITY" in it }) {
            "DTD declarations are not allowed"
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(buffered)
        require(doc.doctype == null) { "DTD declarations are not allowed" }
        return doc
    }

    private const val PROLOG_BYTES = 4096

    fun read(input: InputStream): CharacterFile = CharacterFile(parse(input))

    fun write(character: CharacterFile, output: OutputStream) {
        output.write(BOM)
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        t.transform(DOMSource(character.doc), StreamResult(output))
        output.flush()
    }

    fun toBytes(character: CharacterFile): ByteArray = ByteArrayOutputStream().also { write(character, it) }.toByteArray()

    fun readFile(file: File): CharacterFile = file.inputStream().buffered().use(::read)
}
