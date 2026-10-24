package pro.aduki.sync.outbox

import io.objectbox.BoxStore
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.models.Card
import pro.aduki.core.models.ContactRow
import pro.aduki.core.vcard.Vcard
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact
import pro.aduki.store.entities.Contact_
import java.util.UUID

/**
 * Book applies address book writes to the local store at once and journals
 * them in the outbox, in one transaction each, for `ContactDispatcher`.
 *
 * Outbox actions (the contact hex in `Outbox.hex`, the payload JSON):
 * - `contact.create`: `{vcard, groups}`; the contact has a placeholder hex
 *   (`contact_...`) until [created] gives it the server's.
 * - `contact.update`: `{vcard, groups}`.
 * - `contact.groups`: `{groups}`, the contact's whole group list.
 * - `contact.rename`: `{group, name}`, a group renamed on every member.
 * - `contact.dissolve`: `{group}`, a group removed from every member.
 * - `contact.delete`: `{}`.
 *
 * The two group-wide actions have no hex: a contact sync waits while one is
 * queued.
 *
 * The vCard is always written as 4.0, keeping the unmodelled properties of
 * the card's source vCard.
 */
class Book(private val store: BoxStore, private val manager: Manager) {
    private val box = store.boxFor(Contact::class.java)

    /** Stores [card] as a new contact and queues its creation. */
    fun create(card: Card): Contact {
        card.validate()
        val uid = card.uid.ifBlank { UUID.randomUUID().toString() }
        val hex = PLACEHOLDER + UUID.randomUUID().toString().replace("-", "")
        return store.callInTx {
            val contact = fill(Contact(hex = hex), card.copy(uid = uid))
            box.put(contact)
            manager.enqueue(CREATE, payload(contact.vcard, contact.groups), hex)
            contact
        }
    }

    /**
     * Replaces the fields of [hex] with [card] and queues the change. A
     * [card] without a source vCard keeps the stored one's other properties.
     */
    fun update(hex: String, card: Card): Contact {
        card.validate()
        return store.callInTx {
            val contact = find(hex)
            fill(contact, card.copy(uid = card.uid.ifBlank { contact.uid }, vcard = card.vcard.ifBlank { contact.vcard }))
            box.put(contact)
            manager.enqueue(UPDATE, payload(contact.vcard, contact.groups), hex)
            contact
        }
    }

    /** Sets the group list of each contact in [changes] (hex to groups) and queues it. */
    fun groups(changes: Map<String, List<String>>): List<Contact> {
        val contacts = store.callInTx {
            changes.map { (hex, groups) ->
                val contact = find(hex)
                val list = groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                card(contact).copy(groups = list).validate()
                contact.groups = list
                // The vCard carries them too, as CATEGORIES.
                contact.vcard = Vcard.write(card(contact))
                box.put(contact)
                manager.enqueue(GROUPS, JSONObject().put("groups", JSONArray(list)).toString().toByteArray(), hex)
                contact
            }
        }
        return contacts
    }

    /** Renames [group] to [name] on every local member and queues it as one action. */
    fun rename(group: String, name: String): List<Contact> =
        wide(RENAME, JSONObject().put("group", group).put("name", name.trim()), group) { groups ->
            groups.map { if (it == group) name.trim() else it }.distinct()
        }

    /** Removes [group] from every local member and queues it as one action. */
    fun dissolve(group: String): List<Contact> =
        wide(DISSOLVE, JSONObject().put("group", group), group) { groups -> groups - group }

    private fun wide(action: String, payload: JSONObject, group: String, edit: (List<String>) -> List<String>): List<Contact> =
        store.callInTx {
            val members = box.all.filter { group in it.groups }
            for (contact in members) {
                contact.groups = edit(contact.groups)
                contact.vcard = Vcard.write(card(contact))
            }
            box.put(members)
            manager.enqueue(action, payload.toString().toByteArray())
            members
        }

    /**
     * Removes [hex] and queues its deletion. A contact whose creation is
     * still queued is just dropped with its queued actions.
     */
    fun delete(hex: String) {
        store.callInTx { remove(hex) }
    }

    /**
     * Folds [others] into [keep] and deletes them, in one transaction: e-mail
     * addresses, phones and groups are combined, blank fields of [keep] are
     * filled from the others and the notes are joined. Unmodelled vCard
     * properties of the others are not carried over.
     */
    fun merge(keep: String, others: List<String>): Contact {
        val gone = others.filter { it != keep }.distinct()
        return store.callInTx {
            val kept = find(keep)
            val card = gone.map { card(find(it)) }.fold(card(kept), ::combine)
            card.validate()
            fill(kept, card)
            box.put(kept)
            manager.enqueue(UPDATE, payload(kept.vcard, kept.groups), keep)
            gone.forEach(::remove)
            kept
        }
    }

    /**
     * Gives the placeholder [old] the server's [row] after its creation, and
     * points actions queued on [old] at the new hex.
     */
    fun created(old: String, row: ContactRow) {
        if (row.hex.isBlank()) return
        store.callInTx {
            val contact = box.query(Contact_.hex.equal(old)).build().findFirst()
            if (contact != null) {
                contact.hex = row.hex
                contact.ctag = row.etag
                contact.uid = row.uid.ifBlank { contact.uid }
                contact.updated = row.updated
                box.put(contact)
            }
            manager.repoint(old, row.hex)
        }
    }

    private fun find(hex: String): Contact =
        box.query(Contact_.hex.equal(hex)).build().findFirst() ?: throw IllegalArgumentException("Contact not found: $hex")

    private fun remove(hex: String) {
        val contact = box.query(Contact_.hex.equal(hex)).build().findFirst()
        val unsent = hex.startsWith(PLACEHOLDER) && manager.pending().any { it.hex == hex && it.action == CREATE }
        if (contact != null) box.remove(contact)
        if (unsent) {
            manager.drop(hex)
        } else {
            manager.enqueue(DELETE, "{}".toByteArray(), hex)
        }
    }

    companion object {
        const val PREFIX = "contact."
        const val CREATE = "contact.create"
        const val UPDATE = "contact.update"
        const val GROUPS = "contact.groups"
        const val RENAME = "contact.rename"
        const val DISSOLVE = "contact.dissolve"
        const val DELETE = "contact.delete"

        /** Hex prefix of a contact the server has not created yet. */
        const val PLACEHOLDER = "contact_"

        /** The server refuses a vCard longer than this (`PATCH .../vcard`). */
        const val LIMIT = 100_000

        /** The editable fields of [contact], with its vCard as the source. */
        fun card(contact: Contact): Card = Card(
            name = contact.name,
            emails = contact.emails.ifEmpty { listOfNotNull(contact.email.ifBlank { null }) },
            phones = contact.phones.ifEmpty { listOfNotNull(contact.phone.ifBlank { null }) },
            company = contact.company,
            notes = contact.notes,
            photo = contact.photo,
            groups = contact.groups,
            uid = contact.uid,
            vcard = contact.vcard
        )

        /** [into] with what [other] adds: see [merge]. */
        fun combine(into: Card, other: Card): Card = into.copy(
            name = into.name.ifBlank { other.name },
            emails = (into.emails + other.emails).distinctBy { it.trim().lowercase() },
            // The same number written nationally and internationally is one.
            phones = (into.phones + other.phones).distinctBy { p -> p.filter { it.isDigit() }.takeLast(9) },
            company = into.company.ifBlank { other.company },
            notes = listOf(into.notes, other.notes).filter { it.isNotBlank() }.distinct().joinToString("\n"),
            photo = into.photo.ifBlank { other.photo },
            groups = (into.groups + other.groups).distinct()
        )

        private fun fill(contact: Contact, card: Card): Contact {
            val vcard = Vcard.write(card, Vcard.V4)
            require(vcard.length <= LIMIT) { "vCard is longer than $LIMIT characters (a smaller photo helps)" }
            contact.name = card.name.trim()
            contact.emails = card.emails.map { it.trim() }
            contact.phones = card.phones.map { it.trim() }
            contact.email = contact.emails.firstOrNull() ?: ""
            contact.phone = contact.phones.firstOrNull() ?: ""
            contact.company = card.company.trim()
            contact.notes = card.notes
            contact.photo = card.photo.trim()
            contact.groups = card.groups.map { it.trim() }
            contact.uid = card.uid
            contact.vcard = vcard
            return Sealing.reindex(contact)
        }

        private fun payload(vcard: String, groups: List<String>): ByteArray =
            JSONObject().put("vcard", vcard).put("groups", JSONArray(groups)).toString().toByteArray()
    }
}
