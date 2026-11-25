package chummer.core.engine

import chummer.core.DataPack
import chummer.core.children
import chummer.core.parseXml
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/**
 * Guards against Chummer changing its data format under us. Players can import their own Chummer data, so the
 * engine has to read every data set listed in chummer-supported.txt, not only the pinned one. The golden tests need local
 * reference characters; these need only Chummer's public data, so they can also run anywhere the data is available.
 *
 * The shape guard lists every kind of bonus node in the data: its name and the child elements it contains, for example
 * `weaponcategorydice(bonus,name)`. Chummer 5.226.0 wrote that node as `weaponcategorydice(category)` and the engine silently
 * ignored the other form. A shape that no checked data set has had before fails here, so a person looks at it before it ships.
 * `bonus-shapes.txt` is the union over the checked data sets; to add shapes after checking them:
 * `./gradlew -Pchummer.updateShapes=true :core:test --tests chummer.core.engine.DataSetDriftTest` (once per data set).
 */
class DataSetDriftTest {
    private val src = File(System.getProperty("chummer.src"))
    private val dataDir = File(src, "Chummer/data")
    private val shapesFile = File("src/test/resources/bonus-shapes.txt")

    private fun shapes(): Set<String> {
        val out = java.util.TreeSet<String>()
        for (f in dataDir.listFiles { x -> x.extension == "xml" }.orEmpty()) {
            val bonuses = parseXml(f).getElementsByTagName("bonus")
            for (i in 0 until bonuses.length) {
                // Only containers: a leaf `<bonus>1</bonus>` inside a node is a value, not a kind of bonus.
                for (node in (bonuses.item(i) as Element).children()) {
                    out += node.tagName + "(" + node.children().map { it.tagName }.distinct().sorted().joinToString(",") + ")"
                }
            }
        }
        return out
    }

    @Test
    fun noBonusShapeIsNew() {
        val known = if (shapesFile.isFile) shapesFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet() else emptySet()
        val now = shapes()
        assertTrue("found only ${now.size} bonus shapes: is chummer.src a Chummer folder?", now.size > 100)
        if (System.getProperty("chummer.updateShapes") == "true") {
            shapesFile.parentFile.mkdirs()
            shapesFile.writeText(
                "# Kinds of bonus node (name and child elements) seen in the Chummer data sets this app is checked against. See DataSetDriftTest.\n" +
                    (known + now).sorted().joinToString("\n", postfix = "\n"),
            )
            println("Updated ${shapesFile.path}: ${(known + now).size} shapes, ${(now - known).size} new")
            return
        }
        val fresh = now - known
        assertTrue(
            "This Chummer data has ${fresh.size} bonus shape(s) no checked data set had: ${fresh.take(15)}. Make sure BonusHandlers reads them, then add them with " +
                "./gradlew -Pchummer.updateShapes=true :core:test --tests chummer.core.engine.DataSetDriftTest",
            fresh.isEmpty(),
        )
    }

    @Test
    fun everyCatalogTheAppUsesLoads() {
        val data = DataPack.load(dataDir)
        val counts = linkedMapOf<String, Int>(
            "quality" to QualityCatalog.list(data).size, "spell" to SpellCatalog.list(data).size, "gear" to GearCatalog.list(data).size,
            "ware" to WareCatalog.list(data).size, "weapon" to ArmorWeaponCatalog.weapons(data).size, "armor" to ArmorWeaponCatalog.armor(data).size,
            "accessory" to ArmorWeaponCatalog.accessories(data).size, "armormod" to ArmorWeaponCatalog.armorMods(data).size,
            "knowledge" to KnowledgeCatalog.list(data).size, "complexform" to ComplexForms.catalog(data).size, "tradition" to TraditionCatalog.list(data).size,
            "vehicle" to VehicleCatalog.vehicles(data).size, "power" to PowerCatalog.list(data).size, "martial" to MartialArtCatalog.arts(data).size,
            "critter" to CritterCatalog.list(data).size,
        )
        println("CATALOGS $counts")
        val empty = counts.filterValues { it == 0 }.keys
        assertTrue("catalogs that came out empty: $empty", empty.isEmpty())
    }
}
