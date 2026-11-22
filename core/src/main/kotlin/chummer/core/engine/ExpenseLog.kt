package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One line of the career log. [type] is "Karma" or "Nuyen"; [undoKind] is the kind of purchase Chummer recorded for undoing it. */
data class ExpenseEntry(
    val guid: String, val date: String, val amount: Double, val reason: String, val type: String,
    val refund: Boolean, val undoKind: String, val objectId: String, val qty: Double, val extra: String,
) {
    val isKarma get() = type == "Karma"
}

/**
 * The career karma and nuyen log (`<expenses>`). Verified on Viper's file: the stored `<karma>` and `<nuyen>` equal the
 * sum of every entry's amount of that type, refund-flagged entries included (refund only marks karma that does not count
 * toward career karma). Every change here keeps the stored balance in step.
 */
class ExpenseLog(
    private val file: CharacterFile,
    /** Needed only to undo a bought quality (its improvements are removed through the editor). */
    private val data: chummer.core.GameData? = null,
    private val settings: chummer.core.data.CharacterSettings? = null,
) {
    private val doc get() = file.doc
    private val root get() = file.root

    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    private fun num(e: Element, tag: String) = e.text(tag)?.trim()?.toDoubleOrNull() ?: 0.0

    /** Entries in file order (Chummer sorts by date). */
    fun entries(): List<ExpenseEntry> = root.child("expenses")?.children("expense").orEmpty().map { e ->
        val undo = e.child("undo")
        val type = e.text("type")?.trim().orEmpty()
        ExpenseEntry(
            guid = e.text("guid")?.trim().orEmpty(), date = e.text("date")?.trim().orEmpty(), amount = num(e, "amount"),
            reason = e.text("reason").orEmpty(), type = type, refund = e.text("refund")?.trim().equals("True", true),
            undoKind = (if (type == "Karma") undo?.text("karmatype") else undo?.text("nuyentype"))?.trim().orEmpty(),
            objectId = undo?.text("objectid")?.trim().orEmpty(), qty = undo?.let { num(it, "qty") } ?: 0.0, extra = undo?.text("extra")?.trim().orEmpty(),
        )
    }

    val karma: Int get() = root.text("karma")?.trim()?.toDoubleOrNull()?.toInt() ?: 0
    val nuyen: Double get() = root.text("nuyen")?.trim()?.toDoubleOrNull() ?: 0.0

    /** True when the stored balances equal the sum of the entries. */
    fun balanced(): Boolean {
        val all = entries()
        return all.filter { it.isKarma }.sumOf { it.amount }.toInt() == karma &&
            Math.abs(all.filter { !it.isKarma }.sumOf { it.amount } - nuyen) < 0.005
    }

    private fun format(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

    private fun adjust(type: String, delta: Double) {
        val tag = root.child(if (type == "Karma") "karma" else "nuyen") ?: return
        val now = tag.textContent.trim().toDoubleOrNull() ?: 0.0
        tag.textContent = if (type == "Karma") (now + delta).toInt().toString() else format(now + delta)
    }

    /**
     * Record an entry and change the balance by [amount] (positive adds, negative spends). [undoKind] is Chummer's
     * `KarmaExpenseType` for karma ("ManualAdd", "ManualSubtract", "ImproveSkill", ...) or `NuyenExpenseType` for nuyen.
     */
    fun add(
        type: String, amount: Double, reason: String, undoKind: String, objectId: String = "", qty: Double = 0.0, extra: String = "",
        refund: Boolean = false, now: LocalDateTime = LocalDateTime.now(),
    ): String {
        require(type == "Karma" || type == "Nuyen")
        val guid = UUID.randomUUID().toString()
        val e = el("expense")
        e.appendChild(el("guid", guid))
        e.appendChild(el("date", now.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
        e.appendChild(el("amount", format(amount)))
        e.appendChild(el("reason", reason))
        e.appendChild(el("type", type))
        e.appendChild(el("refund", if (refund) "True" else "False"))
        e.appendChild(el("forcecareervisible", "False"))
        val undo = el("undo")
        // The kind that does not apply to this entry holds the enum's first value, as Chummer writes it.
        undo.appendChild(el("karmatype", if (type == "Karma") undoKind else "ImproveAttribute"))
        undo.appendChild(el("nuyentype", if (type == "Nuyen") undoKind else "AddCyberware"))
        undo.appendChild(el("objectid", objectId))
        undo.appendChild(el("qty", format(qty)))
        undo.appendChild(el("extra", extra))
        e.appendChild(undo)
        (root.child("expenses") ?: el("expenses").also { root.appendChild(it) }).appendChild(e)
        adjust(type, amount)
        return guid
    }

    /** Karma earned or spent by hand (a reward, a correction). Positive adds. */
    fun manualKarma(amount: Int, reason: String, refund: Boolean = false, now: LocalDateTime = LocalDateTime.now()) =
        add("Karma", amount.toDouble(), reason, if (amount >= 0) "ManualAdd" else "ManualSubtract", refund = refund, now = now)

    /** Nuyen earned or spent by hand. Positive adds. */
    fun manualNuyen(amount: Double, reason: String, now: LocalDateTime = LocalDateTime.now()) =
        add("Nuyen", amount, reason, if (amount >= 0) "ManualAdd" else "ManualSubtract", now = now)

    /** Which entries can be undone by [undo]: manual ones, and skill and attribute improvements. */
    fun canUndo(entry: ExpenseEntry): Boolean = when {
        entry.isKarma -> entry.undoKind in setOf("ManualAdd", "ManualSubtract", "ImproveAttribute", "ImproveSkill", "AddSkill", "AddSpecialization", "AddQuality", "AddSpell", "AddMartialArt", "AddMartialArtTechnique", "AddComplexForm") && entry.reason != "Starting Karma"
        else -> entry.undoKind in setOf("ManualAdd", "ManualSubtract", "AddGear", "AddArmor", "AddWeapon", "AddWeaponAccessory", "AddArmorMod", "AddCyberware") && entry.reason != "Starting Nuyen"
    }

    /**
     * Take back an entry: its amount leaves the balance, the entry is deleted, and for an improvement the bought rating is
     * given back. Returns false when the entry is unknown or cannot be undone.
     */
    fun undo(guid: String): Boolean {
        val entry = entries().firstOrNull { it.guid.equals(guid, true) } ?: return false
        if (!canUndo(entry)) return false
        if (!entry.isKarma && entry.undoKind == "AddCyberware") {
            val d = data ?: return false
            if (!CyberwareEditor(file, d).remove(entry.objectId)) return false
        }
        if (!entry.isKarma && entry.undoKind == "AddWeaponAccessory") {
            val d = data ?: return false
            if (!ArmorWeaponEditor(file, d).removeAccessory(entry.objectId)) return false
        }
        if (!entry.isKarma && entry.undoKind == "AddArmorMod") {
            val d = data ?: return false
            if (!ArmorWeaponEditor(file, d).removeArmorMod(entry.objectId)) return false
        }
        if (!entry.isKarma && entry.undoKind == "AddArmor") {
            val d = data ?: return false
            if (!ArmorWeaponEditor(file, d).removeArmor(entry.objectId)) return false
        }
        if (!entry.isKarma && entry.undoKind == "AddWeapon") {
            val d = data ?: return false
            if (!ArmorWeaponEditor(file, d).removeWeapon(entry.objectId)) return false
        }
        if (!entry.isKarma && entry.undoKind == "AddGear") {
            val d = data ?: return false
            if (!GearEditor(file, d, settings).removeGear(entry.objectId)) return false
        }
        if (entry.isKarma && entry.undoKind == "AddMartialArt") {
            val d = data ?: return false
            if (!MartialArtEditor(file, d).removeArt(entry.objectId)) return false
        }
        if (entry.isKarma && entry.undoKind == "AddMartialArtTechnique") {
            val d = data ?: return false
            if (!MartialArtEditor(file, d).removeTechnique(entry.objectId)) return false
        }
        if (entry.isKarma && entry.undoKind == "AddComplexForm") {
            val d = data ?: return false
            if (!CharacterEditor(file, d, settings = settings).removeItem(entry.objectId)) return false
        }
        if (entry.isKarma && entry.undoKind == "AddSpell") {
            val d = data ?: return false
            if (!CharacterEditor(file, d, settings = settings).removeItem(entry.objectId)) return false
        }
        if (entry.isKarma && entry.undoKind == "AddQuality") {
            val d = data ?: return false
            if (!CharacterEditor(file, d, settings = settings).removeItem(entry.objectId)) return false
        }
        if (entry.isKarma && entry.undoKind == "AddSpecialization" && !removeSpecialization(entry)) return false
        if (entry.isKarma && entry.undoKind in setOf("ImproveAttribute", "ImproveSkill", "AddSkill") && !reverseImprovement(entry)) return false
        val node = root.child("expenses")?.children("expense")?.first { it.text("guid")?.trim().equals(guid, true) } ?: return false
        node.parentNode.removeChild(node)
        adjust(entry.type, -entry.amount)
        return true
    }

    private fun removeSpecialization(entry: ExpenseEntry): Boolean {
        val spec = root.child("newskills")?.let { ns ->
            (ns.child("skills")?.children("skill").orEmpty() + ns.child("knoskills")?.children("skill").orEmpty())
                .flatMap { it.child("specs")?.children("spec").orEmpty() }
        }?.firstOrNull { it.text("guid")?.trim().equals(entry.objectId, true) } ?: return false
        spec.parentNode.removeChild(spec)
        return true
    }

    private fun reverseImprovement(entry: ExpenseEntry): Boolean {
        val row: Element = if (entry.undoKind == "ImproveAttribute") {
            root.child("attributes")?.children("attribute")?.firstOrNull { it.text("name")?.trim() == entry.objectId }
        } else {
            root.child("newskills")?.let { ns ->
                (ns.child("skills")?.children("skill").orEmpty() + ns.child("knoskills")?.children("skill").orEmpty())
                    .firstOrNull { it.text("guid")?.trim().equals(entry.objectId, true) }
                    ?: ns.child("groups")?.children("group")?.firstOrNull { it.text("id")?.trim().equals(entry.objectId, true) }
            }
        } ?: return false
        val karma = row.text("karma")?.trim()?.toIntOrNull() ?: 0
        if (karma <= 0) return false
        row.child("karma")!!.textContent = (karma - 1).toString()
        return true
    }
}
