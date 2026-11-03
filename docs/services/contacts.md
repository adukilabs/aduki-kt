# Contacts Service Reference

The `Contacts` service (`client.contacts`) syncs the address book, looks
contacts up, and writes them: create, update and delete, groups, vCard
import and export, and duplicate merging.

Writes are applied to the local store at once and queued in the outbox, like
mail actions; they reach the server when the outbox drains (each write call
drains it once, and `client.sync.flush()` does too). Writes need a local store
(`Aduki.builder().store(dir)`); without one they throw
`IllegalStateException("no local store")`, except `import`, which then goes
to the server in one call.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Contacts internal constructor(...) {
    val groups: Groups
    val progress: StateFlow<SyncProgress>
    val photoUploads: StateFlow<PhotoProgress>

    suspend fun sync(tenant: String = ""): Boolean
    fun observe(): StateFlow<List<Contact>>?
    fun observe(group: String): StateFlow<List<Contact>>?
    fun search(query: String): StateFlow<List<Contact>>?
    fun hit(contact: Contact, query: String): SearchHit?
    fun get(hex: String): Contact?
    fun card(hex: String): Card?

    suspend fun create(card: Card): Contact
    suspend fun update(hex: String, card: Card): Contact
    suspend fun delete(hex: String)

    suspend fun photo(hex: String, refresh: Boolean = false): ByteArray?
    suspend fun photo(contact: Contact, refresh: Boolean = false): ByteArray?
    suspend fun setPhoto(hex: String, bytes: ByteArray): Contact
    suspend fun removePhoto(hex: String): Contact

    suspend fun import(vcf: InputStream): Imported
    fun export(hexes: List<String>? = null, version: String = Vcard.V4): String
    fun export(out: OutputStream, hexes: List<String>? = null, version: String = Vcard.V4)

    fun duplicates(): List<Duplicate>
    suspend fun merge(keep: String, others: List<String>): Contact
}

class Groups internal constructor(...) {
    fun observe(): StateFlow<List<String>>?
    fun list(): List<String>
    suspend fun create(group: String, members: List<String> = emptyList())
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

The transport (`pro.aduki.sync.http.HttpContactTransport`) reads
`GET /v1/user/contacts/sync` (scope `contacts:read`), 200 cards a page:

- with no stored token it is a full listing (`full: true` on every page,
  `total` on the first). Each page is applied in its own transaction as it
  arrives; once the last page (`more: false`) is in, local contacts not on
  any page of the listing are removed, and its `token` is stored. A pass that
  fails half way keeps the pages it applied and removes nothing;
- the next sync sends `since=<token>` and gets only the cards changed
  (`items`) and deleted (`removed`) after it, paged the same way;
- a `410` (`sync.reset`, the token is too old) or `422` (not a token) drops
  the token and starts a full listing;
- a server without the route (`404`, `405`) is read the old way, every sync:
  all of `GET /v1/user/contacts`, 200 rows at a time, reconciled as a full
  listing; the token is then a digest of every `hex:etag` line, and when it
  equals the stored one no contact is touched. The transport keeps to the old
  way for its lifetime;
- a row whose etag is the stored one is not written again; only rows that
  changed are opened, merged and sealed. Removals go by id;
- the group list (`GET /v1/user/contacts/groups`) is read with the last page
  and replaces the stored groups, so groups without members show up too;
- contacts with a queued write are left as they are locally until the write
  is sent (the token is then not kept, so the next sync reads them again),
  and a queued group create, rename or delete holds the whole sync back.

```json
{"success": true, "data": {
  "items": [{"hex": "8d012b837f9fbbaf", "uid": "01a16e92-...", "etag": "7e29...",
    "vcard": "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ann Lee\r\n...END:VCARD\r\n",
    "name": "Ann Lee", "emails": ["ann@example.com"], "phones": ["+15550001"],
    "groups": ["friends"], "meta": {}, "version": "4.0", "size": 134,
    "created": "2026-10-24T15:40:42.574184Z", "updated": "2026-10-24T15:42:29.310916Z",
    "photo": {"hex": "0123456789abcdef", "updated": "2026-10-24T15:42:29Z"}}],
  "removed": ["2f1c0d9e8b7a6c5d"], "token": "d.4182", "more": false, "full": false}}
```

Rows carry the vCard: company, notes and an inline photo are read from it,
and every e-mail address and phone is kept. A photo kept as a blob (`photo`
above, `null` for none) has no `PHOTO` line in the vCard; it is
`Contact.photoHex` (see Photos).

### `progress`
`StateFlow<SyncProgress>` (`pro.aduki.core.models`), also `client.sync.contacts`:

- `Idle`: no sync has run in this process;
- `Running(done, total)`: `done` cards applied so far; `total` is the size of
  a full listing when the server says it, else null (show an indeterminate bar);
- `Done(at, changed)`: finished at `at` (epoch ms), `changed` cards stored or removed;
- `Failed(message, at)`: the last sync failed; `sync` threw the error.

### Photos
- `photo(hex, refresh)` / `photo(contact, refresh)`: the photo bytes, or null.
  A photo set on this device and not sent yet comes from its spool file; a
  server photo from the sealed cache, keyed by its blob id, else it is
  downloaded once (`GET /v1/user/contacts/{hex}/photo`) and cached. With
  `refresh` a cached photo is checked with `If-None-Match` (a `304` keeps
  it). A `data:` URI photo in the vCard is decoded. Never throws: a failure
  is null (or the cached copy), reported through `Faults.report`. Without a
  store `photo(hex)` reads the server each time.
- `setPhoto(hex, bytes)`: JPEG, PNG or WebP (by magic bytes), at most 1 MiB;
  the server also refuses more than 1024 by 1024 pixels. Scale it first: on
  Android `pro.aduki.android.photo.Thumbnails.scale(bytes)` writes a JPEG of
  at most 512 pixels. It is shown at once (`photoHex` is `local:<spool>`) and
  queued as `contact.photo`: `PUT /v1/user/contacts/{hex}/photo` with the
  bytes. The reply's blob id replaces `photoHex`, and the bytes are cached
  under it, so the device never downloads its own upload.
- `removePhoto(hex)`: queued as `contact.unphoto` (`DELETE
  /v1/user/contacts/{hex}/photo`); a `data:` URI photo is removed from the
  vCard too, with a `contact.update`.
- `photoUploads`: `PhotoProgress(queued, done, failed)`: photo writes waiting
  in the outbox, sent since the client was built, and refused for good (413,
  415, 422, or 404 from a server without photo routes); those stay in
  `client.sync.failed` to retry or discard.

Photos are files sealed with the store's vault, in `photos` inside the
`store(dir)` directory or `Aduki.Builder.photos(dir)`. With
`store(BoxStore)` and no `photos(dir)` they are kept in memory: an upload
still queued when the process ends is then refused on the next flush.

### `observe`, `search`, `get`, `card`
- `observe()`: every contact, sorted by name, as a hot `StateFlow`.
- `observe(group)`: the members of one group.
- `search(query)`: case-insensitive substring match across name, company,
  every e-mail address and phone, notes and group names. Searches and groups
  are derived from the one `observe()` flow: they add no store subscription
  and their `value` is current without a collector.
- `hit(contact, query)`: returns `SearchHit(contact, field, range)` naming the
  matched property and its character offset range for search highlighting.
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

{"vcard": "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:...\r\nFN:Ann Lee\r\nN:Lee;Ann;;;\r\nEMAIL:ann@example.com\r\nEND:VCARD\r\n",
 "name": "Ann Lee", "groups": ["friends"], "meta": null}
```

The vCard goes up as written, `VERSION:4.0` included; the server rebuilds it
with one `VERSION:4.0`. The `name` sent is the card's `FN`.

---

## 4. Groups

Members carry a group as a tag. A group created by name stays listed with no
members until it is deleted; one that only ever existed as a tag goes when
its last member leaves.

```kotlin
client.contacts.groups.create("family")                           // empty, kept
client.contacts.groups.create("friends", listOf(annHex, bobHex))
client.contacts.groups.add("friends", listOf(carolHex))
client.contacts.groups.remove("friends", listOf(bobHex))
client.contacts.groups.rename("friends", "close friends")
client.contacts.groups.delete("close friends")                    // members stay
val names = client.contacts.groups.observe()                      // StateFlow<List<String>>
val members = client.contacts.observe("friends")
```

- `create` stores the group locally (a `Group` row, so `list` and `observe`
  show it at once) and queues `POST /user/contacts/groups` `{"name": "..."}`
  (201 when new, 200 when it exists). With members, it then adds them.
- `add` and `remove` set each member's group list
  (`PATCH /user/contacts/{hex}/groups`).
- `rename` and `delete` are one queued action each:
  `PATCH /user/contacts/group/{group}` `{"name": "..."}` (answers
  `{"group", "changed"}`) and `DELETE /user/contacts/group/{group}` (answers
  `{"changed"}`). Both apply to every member and to an empty group.
- `list` and `observe` give the group names in use plus the stored groups:
  after a sync, the server's whole list (`GET /user/contacts/groups`,
  `{"items": [{"name", "count"}]}`, empty groups with count 0).
- The groups also go into the vCard as `CATEGORIES`.

---

## 5. vCard import and export

```kotlin
val result: Imported = client.contacts.import(file.inputStream())
// Imported(created, updated, skipped, errors)

val vcf: String = client.contacts.export(listOf(annHex), version = Vcard.V3)
client.contacts.export(outputStream)  // every contact, 4.0
```

With a local store, `import` runs on the device: it works offline, each card
goes through the outbox like any write, and cards are matched by UID and
then by e-mail address. Without a local store the file goes to the server in
one call, `POST /user/contacts/import` (`text/vcard`, at most 5000 cards),
which matches by UID only and answers `{created, updated, skipped, errors:
[{index, error}]}`; `errors` become `"card N: reason"` as below.

On the device, `import` reads vCard 2.1, 3.0 and 4.0, one or more cards per file.
Lines are unfolded (including Quoted-Printable soft line breaks and indented photo blocks),
Quoted-Printable values are decoded honouring `CHARSET`, bare 2.1 parameters are accepted as types,
property groups (`item1.EMAIL`), quoted parameters and escapes are honoured, and inline photos
(`PHOTO;ENCODING=b;TYPE=JPEG:...` or `PHOTO;ENCODING=BASE64;JPEG:...`) become `data:` URIs.
Imported cards are written to the store as 3.0 or 4.0. For each card:

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
joined, and the others are deleted. It queues one `contact.merge`, sent as
`POST /user/contacts/{hex}/merge` `{"others": [hex], "vcard": "..."}`: the
server merges in one transaction (the others' groups join `keep`'s, the
vCard replaces `keep`'s, the others are deleted) and returns the card.
Others whose creation was never sent are just dropped (when all are, an
update of `keep` is queued instead). If the server answers 404 (one of them
is gone, nothing changed), the merge falls back to an update of `keep` and
a delete of each other. Properties of the others' vCards that `Card` does
not model are not carried over.

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
    var photo: String = "",          // sealed, data: URI or URL from the vCard
    var photoHex: String = "",       // clear: the photo blob id, local:<spool> while queued, "" for none
    var photoUpdated: Long = 0       // when the photo was set, epoch ms
)
```

Sealed columns are encrypted with the store's vault (see `Sealing`); lists
are sealed as one text column.

Groups kept without members are `Group` rows:

```kotlin
@Entity
data class Group(
    @Id var id: Long = 0,
    var name: String = "",           // sealed
    var count: Int = 0               // members on the server at the last sync
)
```
