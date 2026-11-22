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
        MentorEditor(file, data).rebuild(guid)
        return MentorRef(guid, d("id"))
    }

    override fun addPower(name: String): PowerRef? {
        val pack = data as? DataPack ?: return null
        val id = pack.byId.entries.firstOrNull { it.value.file == "powers.xml" && it.value.element.tagName == "power" && it.value.name == name }?.key ?: return null
        val editor = PowerEditor(file, data)
        if (editor.problem(id, "") == null) editor.add(id, rating = 0)
        else if (root.text("adept")?.trim() != "True") return null
        return PowerRef(name, "")
    }

    override fun addNaturalWeapon(node: Element, sourceName: String): String? {
        fun t(tag: String, default: String) = node.child(tag)?.textContent?.trim()?.ifEmpty { null } ?: default
        val guid = UUID.randomUUID().toString()
        val w = el("weapon")
        for ((tag, v) in listOf(
            "sourceid" to "00000000-0000-0000-0000-000000000000", "guid" to guid, "name" to t("name", sourceName), "category" to "Critter Powers", "type" to "Melee",
            "spec" to "", "spec2" to "", "reach" to t("reach", "0"), "damage" to t("damage", "({STR})S").replace("{", "").replace("}", ""), "ap" to t("ap", "0"), "mode" to "0", "rc" to "0",
            "ammo" to "", "cyberware" to "False", "ammocategory" to "", "ammoname" to "", "sizecategory" to "", "firingmode" to "Skill", "clips" to "", "conceal" to "0",
            "avail" to "0", "cost" to "0", "useskill" to t("useskill", ""), "range" to "", "alternaterange" to "", "rangemultiply" to "1", "fullburst" to "10", "suppressive" to "20",
            "source" to t("source", "SR5"), "page" to t("page", "0"), "parentid" to sourceName, "allowaccessory" to "True", "weaponname" to "", "included" to "False",
            "equipped" to "True", "requireammo" to "True", "accuracy" to t("accuracy", "Physical"), "mount" to "", "extramount" to "", "location" to "", "notes" to "",
            "discountedcost" to "False", "active" to "False", "homenode" to "False", "devicerating" to "", "programlimit" to "", "overclocked" to "None", "attack" to "",
            "sleaze" to "", "dataprocessing" to "", "firewall" to "", "attributearray" to "", "modattack" to "", "modsleaze" to "", "moddataprocessing" to "", "modfirewall" to "",
            "modattributearray" to "", "canswapattributes" to "False", "matrixcmfilled" to "0",
        )) w.appendChild(el(tag, v))
        (root.child("weapons") ?: el("weapons").also { root.appendChild(it) }).appendChild(w)
        return guid
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
