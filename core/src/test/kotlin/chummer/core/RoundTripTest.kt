package chummer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every reference character must survive read -> write -> read with identical content. */
class RoundTripTest {
    private val src = File(System.getProperty("chummer.src"))
    private val fixtures: List<File> = Fixtures.characters()

    @Test
    fun everyFixtureRoundTripsLosslessly() {
        assertTrue("fixtures found", fixtures.size >= 30)
        var identical = 0
        for (f in fixtures) {
            val original = CharacterIo.readFile(f)
            val bytes = CharacterIo.toBytes(original)
            val again = CharacterIo.read(bytes.inputStream())
            // Whitespace-only text nodes are part of the DOM, so isEqualNode is a strict content check.
            assertTrue("${f.name}: content changed on round trip", original.doc.documentElement.isEqualNode(again.doc.documentElement))
            // A second write must be byte-stable.
            assertEquals("${f.name}: second write differs", bytes.toList(), CharacterIo.toBytes(again).toList())
            if (f.readBytes().contentEquals(bytes)) identical++
        }
        println("Round trip OK for ${fixtures.size} files; byte-identical to the original: $identical")
    }

    @Test
    fun filesWithADtdAreRejected() {
        val evil = """<?xml version="1.0"?><!DOCTYPE character [<!ENTITY x "boom">]><character><name>&x;</name></character>"""
        val result = runCatching { CharacterIo.read(evil.byteInputStream()) }
        assertTrue("DTD must be refused", result.isFailure)
    }

    @Test
    fun editingOneValueLeavesEverythingElseUntouched() {
        val f = fixtures.first { it.name == "Viper_career.chum5" }
        val c = CharacterIo.readFile(f)
        val before = c.root.children().filter { it.tagName != "name" }.map { it.textContent.length }
        c.root.child("name")!!.textContent = "Renamed"
        val again = CharacterIo.read(CharacterIo.toBytes(c).inputStream())
        assertEquals("Renamed", again.name)
        assertEquals(before, again.root.children().filter { it.tagName != "name" }.map { it.textContent.length })
    }
}
