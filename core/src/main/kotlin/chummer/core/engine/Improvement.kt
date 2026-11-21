package chummer.core.engine

import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element

/**
 * One applied improvement, as Chummer stores it in a character's `<improvements>`.
 * Items such as qualities and cyberware add these when they are taken; the engine only
 * has to sum them. Field meanings follow Chummer's `Improvement` class.
 */
data class Improvement(
    val type: String,
    val source: String,
    val improvedName: String,
    val sourceName: String,
    val uniqueName: String,
    val value: Double,
    val augmented: Double,
    val minimum: Double,
    val maximum: Double,
    val augmentedMaximum: Double,
    val rating: Double,
    val addToRating: Boolean,
    val enabled: Boolean,
    val custom: Boolean,
    val condition: String,
) {
    companion object {
        private fun Element.num(tag: String) = text(tag)?.trim()?.toDoubleOrNull() ?: 0.0
        private fun Element.flag(tag: String) = text(tag)?.trim().let { it == "1" || it.equals("True", true) }

        fun parse(e: Element) = Improvement(
            type = e.text("improvementttype").orEmpty().trim(),
            source = e.text("improvementsource").orEmpty().trim(),
            improvedName = e.text("improvedname").orEmpty().trim(),
            sourceName = e.text("sourcename").orEmpty().trim(),
            uniqueName = e.text("unique").orEmpty().trim(),
            value = e.num("val"),
            augmented = e.num("aug"),
            minimum = e.num("min"),
            maximum = e.num("max"),
            augmentedMaximum = e.num("augmax"),
            rating = e.num("rating"),
            addToRating = e.flag("addtorating"),
            enabled = e.flag("enabled"),
            custom = e.flag("custom"),
            condition = e.text("condition").orEmpty().trim(),
        )

        fun parseAll(root: Element): List<Improvement> =
            root.child("improvements")?.children("improvement").orEmpty().map(::parse)
    }
}
