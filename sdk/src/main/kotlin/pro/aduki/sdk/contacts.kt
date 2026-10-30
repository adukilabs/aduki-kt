package pro.aduki.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import pro.aduki.core.models.Card
import pro.aduki.core.vcard.Vcard
import pro.aduki.store.entities.Contact
import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.sync.outbox.Book
import pro.aduki.sync.outbox.Worker
import pro.aduki.state.repository.Contact as ContactRepo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Progress of a contact import operation.
 */
data class Progress(
    val done: Int = 0,
    val total: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
) {
    fun report(): Imported = Imported(created, updated, skipped, errors)
}

/**
 * Imported is what [Contacts.import] did with each card of a file.
 */
data class Imported(
    val created: Int = 0,
    val updated: Int = 0,
    /** Cards already stored as they are. */
    val skipped: Int = 0,
    /** One message per card that could not be read or stored (`card 3: ...`). */
    val errors: List<String> = emptyList()
)

/**
 * Duplicate is a set of contacts that look like the same person.
 */
data class Duplicate(
    /** The strongest match among them: `email`, `phone` or `name`. */
    val match: String,
    val contacts: List<Contact>
)

/**
 * SearchHit represents a contact matching a query, with the field that matched and its range.
 */
data class SearchHit(
    val contact: Contact,
    val field: String,
    val range: IntRange
)

/**
 * Contacts service providing high-level operations for address book.
 *
 * Writes are applied to the local store at once and queued in the outbox
 * (see `Book`); they reach the server on the next flush. Without a local
 * store (`Aduki.Builder.store`) they throw [IllegalStateException].
 */
class Contacts internal constructor(
    private val client: Aduki,
    private val repo: ContactRepo? = null,
    private val engine: ContactEngine? = null,
    private val book: Book? = null,
    private val worker: Worker? = null
) {
    // Writes need the local store: without one they would vanish silently.
    private fun book(): Book = book ?: throw IllegalStateException("no local store")

    /** Groups (the server's group tags on contacts). */
    val groups = Groups(repo, book, worker)

    /**
     * Performs incremental delta sync for address book.
     */
    suspend fun sync(tenant: String = ""): Boolean {
        val targetTenant = tenant.ifBlank { client.me()?.tenant ?: "" }
        return engine?.sync(targetTenant) ?: false
    }

    /**
     * Observes all contacts sorted by name as a hot StateFlow.
     */
    fun observe(): StateFlow<List<Contact>>? {
        return repo?.observe()
    }

    /** Observes the members of [group], sorted by name. */
    fun observe(group: String): StateFlow<List<Contact>>? {
        return repo?.group(group)
    }

    /**
     * Observes contacts filtered by search query as a hot StateFlow.
     * Matches case-insensitively across name, company, all e-mails, phones,
     * group tags, and notes.
     */
    fun search(query: String): StateFlow<List<Contact>>? {
        return repo?.search(query)
    }

    /**
     * Finds the matched field and character range for search highlighting in [contact].
     */
    fun hit(contact: Contact, query: String): SearchHit? {
        val q = query.trim()
        if (q.isEmpty()) return null
        fun find(text: String, field: String): SearchHit? {
            val idx = text.indexOf(q, ignoreCase = true)
            return if (idx >= 0) SearchHit(contact, field, idx until (idx + q.length)) else null
        }
        find(contact.name, "name")?.let { return it }
        for (e in contact.emails) { find(e, "email")?.let { return it } }
        find(contact.email, "email")?.let { return it }
        for (p in contact.phones) { find(p, "phone")?.let { return it } }
        find(contact.phone, "phone")?.let { return it }
        find(contact.company, "company")?.let { return it }
        for (g in contact.groups) { find(g, "group")?.let { return it } }
        find(contact.notes, "notes")?.let { return it }
        return null
    }

    /**
     * Synchronously looks up a contact by hex.
     */
    fun get(hex: String): Contact? {
        return repo?.get(hex)
    }

    /** The editable fields of [hex], for an editor; pass them back, changed, to [update]. */
    fun card(hex: String): Card? = repo?.get(hex)?.let(Book::card)

    /**
     * Creates a contact. It has a placeholder hex (`contact_...`) until the
     * server's reply replaces it. Throws [IllegalArgumentException] for a
     * card [Card.validate] refuses.
     */
    suspend fun create(card: Card): Contact {
        val contact = book().create(card)
        worker?.drain()
        return contact
    }

    /**
     * Replaces the fields of [hex] with [card]. Properties of the stored
     * vCard that [Card] does not model are kept.
     */
    suspend fun update(hex: String, card: Card): Contact {
        val contact = book().update(hex, card)
        worker?.drain()
        return contact
    }

    /** Deletes [hex]. */
    suspend fun delete(hex: String) {
        book().delete(hex)
        worker?.drain()
    }

    /**
     * Imports a vCard file (3.0 or 4.0, one or more cards).
     *
     * With a local store it runs on the device off the main thread ([Dispatchers.IO]),
     * so it works offline and each card goes through the outbox like any write.
     * When [cancel] returns true or the calling coroutine is cancelled,
     * the import stops after the current card and safely flushes what has been
     * stored so far, returning a partial [Imported] report.
     *
     * Without a local store the file goes to the server in one call
     * (`POST /user/contacts/import`, at most 5000 cards), which matches by UID only.
     */
    suspend fun import(
        vcf: InputStream,
        cancel: (() -> Boolean)? = null,
        onProgress: ((Progress) -> Unit)? = null
    ): Imported = withContext(Dispatchers.IO) {
        val text = vcf.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (book == null) {
            val report = client.contactsApi.import(text)
            val errors = report.errors.map { (i, error) -> "card ${i + 1}: $error" }
            val total = report.created + report.updated + report.skipped + report.errors.size
            val progress = Progress(
                done = total,
                total = total,
                created = report.created,
                updated = report.updated,
                skipped = report.skipped,
                errors = errors
            )
            onProgress?.invoke(progress)
            return@withContext progress.report()
        }
        val book = book()
        val stored = repo?.all().orEmpty()
        val uids = stored.filter { it.uid.isNotBlank() }.associateBy { it.uid }.toMutableMap()
        val emails = HashMap<String, Contact>()
        fun index(contact: Contact) {
            if (contact.uid.isNotBlank()) uids[contact.uid] = contact
            for (email in Book.card(contact).emails) emails.putIfAbsent(email.trim().lowercase(), contact)
        }
        stored.forEach(::index)

        val cards = Vcard.split(text)
        var created = 0
        var updated = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        try {
            for ((i, raw) in cards.withIndex()) {
                if (cancel?.invoke() == true || !isActive) break
                try {
                    val card = Vcard.parse(raw)
                    val byUid = card.uid.takeIf { it.isNotBlank() }?.let { uids[it] }
                    val existing = byUid ?: card.emails.firstNotNullOfOrNull { emails[it.trim().lowercase()] }
                    if (existing == null) {
                        index(book.create(card))
                        created++
                    } else {
                        val current = Book.card(existing)
                        val next = if (byUid != null) card.copy(groups = (card.groups + current.groups).distinct()) else Book.combine(current, card)
                        if (next.same(current)) {
                            skipped++
                        } else {
                            index(book.update(existing.hex, next))
                            updated++
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    errors += "card ${i + 1}: ${e.message}"
                }
                val currentProgress = Progress(
                    done = i + 1,
                    total = cards.size,
                    created = created,
                    updated = updated,
                    skipped = skipped,
                    errors = errors.toList()
                )
                onProgress?.invoke(currentProgress)
            }
        } finally {
            withContext(NonCancellable) {
                worker?.drain()
            }
        }
        Imported(created, updated, skipped, errors)
    }

    /**
     * Progressively imports a vCard file, emitting [Progress] after each card.
     * Runs off the main thread on [Dispatchers.IO].
     */
    fun ingest(vcf: InputStream): Flow<Progress> = flow {
        val text = vcf.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (book == null) {
            val report = client.contactsApi.import(text)
            val errors = report.errors.map { (i, error) -> "card ${i + 1}: $error" }
            val total = report.created + report.updated + report.skipped + report.errors.size
            emit(
                Progress(
                    done = total,
                    total = total,
                    created = report.created,
                    updated = report.updated,
                    skipped = report.skipped,
                    errors = errors
                )
            )
            return@flow
        }
        val book = book()
        val stored = repo?.all().orEmpty()
        val uids = stored.filter { it.uid.isNotBlank() }.associateBy { it.uid }.toMutableMap()
        val emails = HashMap<String, Contact>()
        fun index(contact: Contact) {
            if (contact.uid.isNotBlank()) uids[contact.uid] = contact
            for (email in Book.card(contact).emails) emails.putIfAbsent(email.trim().lowercase(), contact)
        }
        stored.forEach(::index)

        val cards = Vcard.split(text)
        var created = 0
        var updated = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        try {
            emit(Progress(done = 0, total = cards.size))
            for ((i, raw) in cards.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    val card = Vcard.parse(raw)
                    val byUid = card.uid.takeIf { it.isNotBlank() }?.let { uids[it] }
                    val existing = byUid ?: card.emails.firstNotNullOfOrNull { emails[it.trim().lowercase()] }
                    if (existing == null) {
                        index(book.create(card))
                        created++
                    } else {
                        val current = Book.card(existing)
                        val next = if (byUid != null) card.copy(groups = (card.groups + current.groups).distinct()) else Book.combine(current, card)
                        if (next.same(current)) {
                            skipped++
                        } else {
                            index(book.update(existing.hex, next))
                            updated++
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    errors += "card ${i + 1}: ${e.message}"
                }
                emit(
                    Progress(
                        done = i + 1,
                        total = cards.size,
                        created = created,
                        updated = updated,
                        skipped = skipped,
                        errors = errors.toList()
                    )
                )
            }
        } finally {
            withContext(NonCancellable) {
                worker?.drain()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The contacts [hexes] (every stored contact when null) as one vCard
     * file of [version] (`3.0` or `4.0`); unknown hexes are left out.
     */
    fun export(hexes: List<String>? = null, version: String = Vcard.V4): String {
        val out = ByteArrayOutputStream()
        export(out, hexes, version)
        return out.toString(Charsets.UTF_8.name())
    }

    /** Like `export(hexes, version)`, written to [out] (left open). */
    fun export(out: OutputStream, hexes: List<String>? = null, version: String = Vcard.V4) {
        require(version == Vcard.V3 || version == Vcard.V4) { "vCard version must be 3.0 or 4.0" }
        val contacts = hexes?.mapNotNull { repo?.get(it) } ?: repo?.all().orEmpty().sortedBy { it.name }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        for (contact in contacts) writer.write(Vcard.write(Book.card(contact), version))
        writer.flush()
    }

    /**
     * Sets of stored contacts that share an e-mail address, a phone number
     * (the last nine digits) or a name (case and spacing ignored).
     */
    fun duplicates(): List<Duplicate> {
        val all = repo?.all().orEmpty()
        val root = IntArray(all.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (root[r] != r) r = root[r]
            root[i] = r
            return r
        }
        // Edges by strength: 0 email, 1 phone, 2 name.
        val edges = mutableListOf<Triple<Int, Int, Int>>()
        for ((strength, keys) in listOf<(Contact) -> List<String>>({ emailKeys(it) }, { phoneKeys(it) }, { nameKeys(it) }).withIndex()) {
            val first = HashMap<String, Int>()
            for ((i, contact) in all.withIndex()) {
                for (key in keys(contact).distinct()) {
                    val j = first.putIfAbsent(key, i) ?: continue
                    if (j != i) edges += Triple(j, i, strength)
                }
            }
        }
        for ((a, b, _) in edges) root[find(a)] = find(b)
        val best = HashMap<Int, Int>()
        for ((a, _, strength) in edges) best.merge(find(a), strength, ::minOf)
        return all.indices.groupBy(::find)
            .filter { it.value.size > 1 }
            .map { (r, members) -> Duplicate(MATCHES[best[r] ?: 2], members.map { all[it] }.sortedBy { it.name }) }
            .sortedBy { it.contacts.first().name }
    }

    /**
     * Merges [others] into [keep] in one operation: e-mail addresses, phones
     * and groups are combined (so [keep] takes the others' group
     * memberships), blank fields of [keep] are filled from the others, the
     * notes are joined, and the others are deleted. Applied locally at once
     * and sent as one atomic server merge (`POST /user/contacts/{hex}/merge`).
     */
    suspend fun merge(keep: String, others: List<String>): Contact {
        val contact = book().merge(keep, others)
        worker?.drain()
        return contact
    }

    private companion object {
        val MATCHES = listOf("email", "phone", "name")

        fun emailKeys(c: Contact) = Book.card(c).emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        fun phoneKeys(c: Contact) = Book.card(c).phones.map { p -> p.filter { it.isDigit() }.takeLast(9) }.filter { it.length >= 6 }

        fun nameKeys(c: Contact) = listOf(c.name.trim().lowercase().split(Regex("\\s+")).joinToString(" ")).filter { it.isNotEmpty() }
    }
}

/**
 * Groups of contacts. Members carry a group as a tag; a group created by
 * name ([create]) stays listed with no members until it is deleted. Every
 * change is queued in the outbox and synced like other contact writes:
 * adding and removing members writes each member's group list; create,
 * rename and delete are one group-wide action each.
 */
class Groups internal constructor(
    private val repo: ContactRepo?,
    private val book: Book?,
    private val worker: Worker?
) {
    private fun book(): Book = book ?: throw IllegalStateException("no local store")

    /** Observes the group names (with members, or created empty), sorted. */
    fun observe(): StateFlow<List<String>>? = repo?.groups()

    /** The group names now (with members, or created empty), sorted. */
    fun list(): List<String> = repo?.names().orEmpty()

    /** Creates [group], with [members] or none; it stays listed while empty. */
    suspend fun create(group: String, members: List<String> = emptyList()) {
        book().group(group)
        if (members.isEmpty()) worker?.drain() else add(group, members)
    }

    /**
     * Renames [group] to [name] on every member, as one queued action (see
     * `ContactDispatcher` for the server routes it uses).
     */
    suspend fun rename(group: String, name: String) {
        require(name.isNotBlank()) { "group name is required" }
        if (name.trim() == group) return
        book().rename(group, name)
        worker?.drain()
    }

    /** Deletes [group], as one queued action: its members stay, without it. */
    suspend fun delete(group: String) {
        book().dissolve(group)
        worker?.drain()
    }

    /** Adds [hexes] to [group]. */
    suspend fun add(group: String, hexes: List<String>) {
        require(group.isNotBlank()) { "group name is required" }
        change(hexes) { groups -> groups + group.trim() }
    }

    /** Removes [hexes] from [group]. */
    suspend fun remove(group: String, hexes: List<String>) {
        change(hexes) { groups -> groups - group }
    }

    private suspend fun change(hexes: List<String>, edit: (List<String>) -> List<String>) {
        val book = book()
        val changes = hexes.distinct().associateWith { hex ->
            val contact = repo?.get(hex) ?: throw IllegalArgumentException("Contact not found: $hex")
            edit(contact.groups).distinct()
        }.filter { (hex, groups) -> repo?.get(hex)?.groups != groups }
        if (changes.isEmpty()) return
        book.groups(changes)
        worker?.drain()
    }
}
