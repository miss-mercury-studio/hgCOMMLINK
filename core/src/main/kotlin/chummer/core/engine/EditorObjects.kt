package chummer.core.engine

import chummer.core.CharacterFile
import chummer.core.DataPack
import chummer.core.GameData
import chummer.core.child
import chummer.core.children
import chummer.core.text
import org.w3c.dom.Element
import java.util.UUID

/** Creates, on the character's own document, the objects that bonuses add: gear, mentor spirits and contacts. */
class EditorObjects(private val file: CharacterFile, private val data: GameData) : BonusObjects {
    private val doc get() = file.doc
    private val root get() = file.root
    private fun el(tag: String, text: String? = null): Element = doc.createElement(tag).also { if (!text.isNullOrEmpty()) it.textContent = text }

    private fun findGear(guid: String): Element? {
        fun walk(list: Element?): Element? {
            for (g in list?.children("gear").orEmpty()) {
                if (g.text("guid")?.trim().equals(guid, true)) return g
                walk(g.child("children"))?.let { return it }
            }
            return null
        }
        return walk(root.child("gears"))
    }

    override fun addGear(sourceGuid: String, name: String, category: String, rating: Int, qty: Double, fullCost: Boolean, parentGuid: String?): String? {
        val editor = GearEditor(file, data)
        val id = editor.findGear(name, category) ?: return null
        val into = parentGuid?.let { p -> findGear(p)?.let { g -> g.child("children") ?: el("children").also { g.appendChild(it) } } }
        val guid = editor.addGear(id, rating.takeIf { it > 0 }, qty, into = into, parentId = sourceGuid)
        if (!fullCost) {
            val list = into ?: root.child("gears")
            list?.children("gear")?.lastOrNull { it.text("guid")?.trim() == guid }?.child("cost")?.textContent = "0"
        }
        return guid
    }

    override fun addMentorSpirit(name: String): MentorRef? {
        val pack = data as? DataPack ?: return null
        val entry = pack.byId.values.firstOrNull { it.file == "mentors.xml" && it.element.tagName == "mentor" && it.name == name }?.element ?: return null
        fun d(tag: String) = entry.child(tag)?.textContent?.trim().orEmpty()
        val guid = UUID.randomUUID().toString()
        val m = el("mentorspirit")
        fun add(tag: String, text: String? = null) = m.appendChild(el(tag, text))
        add("guid", guid); add("name", d("name")); add("mentortype", "MentorSpirit"); add("extra"); add("source", d("source")); add("page", d("page"))
        add("advantage", d("advantage")); add("disadvantage", d("disadvantage")); add("mentormask", "False"); add("bonus"); add("choice1"); add("choice2"); add("notes")
        add("id", d("id"))
        (root.child("mentorspirits") ?: el("mentorspirits").also { root.appendChild(it) }).appendChild(m)
        return MentorRef(guid, d("id"))
    }

    override fun addContact(loyalty: Int, connection: Int, group: Boolean, readOnly: Boolean): String? {
        val guid = UUID.randomUUID().toString()
        val c = el("contact")
        fun add(tag: String, text: String? = null) = c.appendChild(el(tag, text))
        add("name"); add("role"); add("location"); add("connection", connection.toString()); add("loyalty", loyalty.toString())
        for (t in listOf("metatype", "sex", "age", "contacttype", "preferredpayment", "hobbiesvice", "personallife")) add(t)
        add("type", "Contact"); add("file"); add("relative"); add("notes"); add("groupname"); add("colour", "0")
        add("group", if (group) "True" else "False"); add("family", "False"); add("blackmail", "False"); add("free", "False"); add("groupenabled", "True")
        add("guid", guid); add("mainmugshotindex", "-1"); add("mugshots")
        (root.child("contacts") ?: el("contacts").also { root.appendChild(it) }).appendChild(c)
        return guid
    }
}
