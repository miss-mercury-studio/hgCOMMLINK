package chummer.core

import org.junit.Test
import java.io.File

/** Survey (no assertions yet): how well does the spike generalize across Chummer's own test characters? */
class FixtureSurveyTest {
    @Test
    fun survey() {
        val src = File(System.getProperty("chummer.src"))
        val pack = DataPack.load(File(src, "Chummer/data"))
        val files = File(src, "Chummer.Tests/TestFiles").listFiles { f -> f.extension == "chum5" }.orEmpty().sortedBy { it.name }
        var attrOk = 0; var attrN = 0; var idsN = 0; var idsOk = 0; var expOk = 0; var parsed = 0
        for (f in files) {
            val c = try { CharacterFile.load(f) } catch (e: Exception) { println("PARSE FAIL ${f.name}: ${e.message}"); continue }
            parsed++
            val ids = c.sourceIds(); val bad = ids.filter { pack[it.second] == null }
            idsN += ids.size; idsOk += ids.size - bad.size
            val imps = c.improvements()
            val attrBad = c.attributes().filter { it.name != "ESS" }.filter {
                it.min + it.base + it.karma + imps.filter { i -> i.type == "Attribute" && i.enabled && i.name == it.name }.sumOf { i -> i.aug } != it.storedTotal
            }
            attrN += c.attributes().size - 1; attrOk += c.attributes().size - 1 - attrBad.size
            val expense = Math.abs(c.expenseTotal("Karma") - (c.root.text("karma")?.trim()?.toDoubleOrNull() ?: 0.0)) < 0.001
            if (expense) expOk++
            println("%-28s ver=%-8s %-8s ids %d/%d attrDiff=%s karmaLog=%s".format(f.name.take(28), c.appVersion, c.buildMethod, ids.size - bad.size, ids.size, attrBad.map { it.name }, expense))
        }
        println("SUMMARY parsed $parsed/${files.size}; sourceids $idsOk/$idsN; attributes $attrOk/$attrN; karma log matches $expOk/$parsed")
    }
}
