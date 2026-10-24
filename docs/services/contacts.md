# Contacts Service Reference

The `Contacts` service (`client.contacts`) syncs the address book, looks
contacts up, and writes them: create, update and delete, groups, vCard
import and export, and duplicate merging.

Writes are applied to the local store at once and queued in the outbox, like
mail actions; they reach the server when the outbox drains (each write call
drains it once, and `client.sync.flush()` does too). Writes need a local store
(`Aduki.builder().store(dir)`); without one they throw
`IllegalStateException("no local store")`.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Contacts internal constructor(...) {
    val groups: Groups

    suspend fun sync(tenant: String = ""): Boolean
    fun observe(): StateFlow<List<Contact>>?
    fun observe(group: String): StateFlow<List<Contact>>?
    fun search(query: String): StateFlow<List<Contact>>?
    fun get(hex: String): Contact?
    fun card(hex: String): Card?

    suspend fun create(card: Card): Contact
    suspend fun update(hex: String, card: Card): Contact
    suspend fun delete(hex: String)

    suspend fun import(vcf: InputStream): Imported
    fun export(hexes: List<String>? = null, version: String = Vcard.V4): String
    fun export(out: OutputStream, hexes: List<String>? = null, version: String = Vcard.V4)

    fun duplicates(): List<Duplicate>
    suspend fun merge(keep: String, others: List<String>): Contact
}

class Groups internal constructor(...) {
    fun observe(): StateFlow<List<String>>?
    fun list(): List<String>
    suspend fun create(group: String, members: List<String>)
    suspend fun rename(group: String, name: String)
    suspend fun delete(group: String)
    suspend fun add(group: String, hexes: List<String>)
    suspend fun remove(group: String, hexes: List<String>)
}
```

---

## 2. Reading

### `sync`
Reads the address book over REST and reconciles it with the local store.
It needs a storage: `Aduki.builder().store(...)`, `contactStorage(storage)`
(with ObjectBox, `pro.aduki.sync.engine.Contact.storage(boxStore)`), or your
own engine with `engines(...)`. Without one it returns `false`.

The server has no incremental contacts route over REST, so the transport
(`pro.aduki.sync.http.HttpContactTransport`) reads `GET /v1/user/contacts`
200 rows at a time and reconciles the whole list:

- the sync token is a digest of every `hex:etag:groups` line (a group change
  leaves the etag alone); if it equals the stored one nothing is touched;
- otherwise every row is merged and local contacts the server no longer lists
  are removed;
- contacts with a queued write are left as they are locally until the write
  is sent, and a queued group rename or delete holds the whole sync back.

Rows carry the vCard: company, notes and photo are read from it, and every
e-mail address and phone is kept. The server answers in page mode (`page`,
`pages`) and ignores the `after` cursor; the transport follows either.

```json
{"success": true, "data": {"items": [
  {"hex": "8d012b837f9fbbaf", "uid": "01a16e92-...", "etag": "7e29...",
   "vcard": "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ann Lee\r\n...END:VCARD\r\n",
   "name": "Ann Lee", "emails": ["ann@example.com"], "phones": ["+15550001"],
   "groups": ["friends"], "meta": {}, "version": "4.0", "size": 134,
   "created": "2026-10-24T15:40:42.574184Z", "updated": "2026-10-24T15:42:29.310916Z"}
], "total": 1, "page": 1, "pages": 1}}
```

### `observe`, `search`, `get`, `card`
- `observe()`: every contact, sorted by name, as a hot `StateFlow`.
- `observe(group)`: the members of one group.
- `search(query)`: case-insensitive substring match on name, e-mail and phone.
- `get(hex)`: one contact from the local store.
- `card(hex)`: the editable fields of a contact (see `Card` below), for an
  editor. Change them and pass them to `update`.

All return `null` without a contact repository.

---

## 3. Writing

### `Card`

```kotlin
package pro.aduki.core.models

data class Card(
    val name: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val company: String = "",
    val notes: String = "",
    val photo: String = "",          // data: URI or URL
    val groups: List<String> = emptyList(),
    val uid: String = "",            // vCard UID, assigned on create when blank
    val vcard: String = ""           // source vCard: its other properties are kept
) {
    fun validate()                   // IllegalArgumentException for what the server refuses
    fun same(other: Card): Boolean   // same fields, ignoring uid and vcard
}
```

`validate` requires a name (at most 200 characters), at most 100 e-mail
addresses, 100 phones and 50 groups, none blank. The written vCard must stay
under 100 000 characters (the server's limit); a large photo is the usual
cause.

### `create`, `update`, `delete`

```kotlin
val ann = client.contacts.create(Card(name = "Ann Lee", emails = listOf("ann@example.com")))
client.contacts.update(hex, client.contacts.card(hex)!!.copy(company = "Acme"))
client.contacts.delete(hex)
```

- `create` stores the contact with a placeholder hex (`contact_...`) and queues
  `POST /user/contacts`; the reply's hex replaces the placeholder, and writes
  queued in the meantime follow it.
- `update` rewrites the vCard as 4.0, keeping the properties `Card` does not
  model (addresses, birthdays, `X-` properties) and the parameters of the
  e-mail and phone lines that remain. It queues
  `PATCH /user/contacts/{hex}/vcard` and `PATCH /user/contacts/{hex}/groups`.
- `delete` removes the contact and queues `DELETE /user/contacts/{hex}` (a 404
  completes it). Deleting a contact whose creation is still queued just drops
  both.

Every write carries an `Idempotency-Key` that is stable for its outbox entry,
so a retry after a lost reply does not create a second contact. A write the
server refuses for good (a 4xx other than 408, 409, 429) is dropped and
reported to `Aduki.Builder.rejected`.

```http
POST /v1/user/contacts HTTP/1.1
Idempotency-Key: outbox-12-1761320000000
Content-Type: application/json

{"vcard": "BEGIN:VCARD\r\nUID:...\r\nFN:Ann Lee\r\nN:Lee;Ann;;;\r\nEMAIL:ann@example.com\r\nEND:VCARD\r\n",
 "name": "Ann Lee", "groups": ["friends"], "meta": null}
```

The `VERSION` line is left out of uploads: the server rebuilds the card and
adds `VERSION:4.0` only when the source has none, so a card that carries one
is refused with 422 "VERSION must be 3.0 or 4.0". The `name` sent is the
card's `FN`.

---

## 4. Groups

The server keeps groups as tags on each contact; there is no group object. A
group exists while it has members:

```kotlin
client.contacts.groups.create("friends", listOf(annHex, bobHex)) // needs at least one member
client.contacts.groups.add("friends", listOf(carolHex))
client.contacts.groups.remove("friends", listOf(bobHex))
client.contacts.groups.rename("friends", "close friends")
client.contacts.groups.delete("close friends")                    // members stay
val names = client.contacts.groups.observe()                      // StateFlow<List<String>>
val members = client.contacts.observe("friends")
```

- `add`, `remove` and `create` set each member's group list
  (`PATCH /user/contacts/{hex}/groups`).
- `rename` and `delete` are one queued action each. They use the group-wide
  routes `PATCH /user/contacts/group/{group}` (`{"name": "..."}`) and
  `DELETE /user/contacts/group/{group}`, which are proposed and not on the
  server yet; while it answers them with 405 or 404, the members are read from
  `GET /user/contacts/group/{group}` and each one's group list is set.
- The groups also go into the vCard as `CATEGORIES`.

---

## 5. vCard import and export

```kotlin
val result: Imported = client.contacts.import(file.inputStream())
// Imported(created, updated, skipped, errors)

val vcf: String = client.contacts.export(listOf(annHex), version = Vcard.V3)
client.contacts.export(outputStream)  // every contact, 4.0
```

`import` reads vCard 3.0 and 4.0 (2.1 cards are reported as errors), one or
more cards per file. Lines are unfolded, property groups (`item1.EMAIL`),
quoted parameters and escapes are honoured, and a 3.0 inline photo
(`PHOTO;ENCODING=b;TYPE=JPEG:...`) becomes a `data:` URI. For each card:

- a card whose `UID` matches a stored contact updates it (the file's values
  win; groups are added);
- else a card sharing an e-mail address with a stored contact adds its
  addresses, phones and groups and fills blank fields;
- a card that would change nothing is `skipped`; any other is `created`;
- a card that cannot be read or stored adds `"card N: reason"` to `errors`.

`export` writes 3.0 or 4.0 with every e-mail address and phone (and their
original parameters), the photo (`ENCODING=b` in 3.0, a `data:` URI in 4.0),
the groups as `CATEGORIES` and every other property of the stored vCard.
The codec is `pro.aduki.core.vcard.Vcard` (`split`, `parse`, `write`).

---

## 6. Duplicates

```kotlin
val sets: List<Duplicate> = client.contacts.duplicates()
// Duplicate(match = "email" | "phone" | "name", contacts)
client.contacts.merge(keep = sets[0].contacts[0].hex, others = sets[0].contacts.map { it.hex })
```

`duplicates` groups contacts that share an e-mail address (case ignored), a
phone number (the last nine digits, so `+44 7700 900123` and `07700 900123`
match) or a name (case and spacing ignored); `match` is the strongest link in
the set.

`merge` folds `others` into `keep` in one local transaction: e-mail addresses,
phones and groups are combined (so `keep` takes the others' group
memberships), blank fields of `keep` are filled from the others, the notes are
joined, and the others are deleted. It queues an update of `keep` and a
delete of each other, sent in that order. Properties of the others' vCards
that `Card` does not model are not carried over.

---

## 7. Data Model: `Contact` Entity

```kotlin
package pro.aduki.store.entities

@Entity
data class Contact(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    var name: String = "",           // sealed
    var email: String = "",          // sealed, first of emails
    var phone: String = "",          // sealed, first of phones
    var company: String = "",        // sealed
    var vcard: String = "",          // sealed
    var ctag: String = "",           // the server's etag
    var updated: Long = 0,           // the server's last change, epoch ms
    @Index var emailIndex: String,   // blind index of email
    @Index var phoneIndex: String,   // blind index of phone
    @Index var uid: String = "",     // vCard UID
    var emails: List<String>,        // sealed
    var phones: List<String>,        // sealed
    var groups: List<String>,        // sealed
    var notes: String = "",          // sealed
    var photo: String = ""           // sealed, data: URI or URL
)
```

Sealed columns are encrypted with the store's vault (see `Sealing`); lists
are sealed as one text column.
