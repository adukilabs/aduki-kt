# Contacts Service Reference

The `Contacts` service (`client.contacts`) provides address book synchronization, contact lookups, and substring search across names, emails, and phone numbers.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Contacts internal constructor(...) {
    suspend fun sync(tenant: String = ""): Boolean

    fun observe(): StateFlow<List<Contact>>?

    fun search(query: String): StateFlow<List<Contact>>?

    fun get(hex: String): Contact?
}
```

---

## 2. Detailed Method Specifications

### `sync`
Reads the address book over REST and reconciles it with the local store.

```kotlin
suspend fun sync(tenant: String = ""): Boolean
```

`sync` needs a storage: pass one with `Aduki.builder().contactStorage(storage)`
(with ObjectBox, `pro.aduki.sync.engine.Contact.storage(boxStore)`), or your own
engine with `engines(...)`. Without either it returns `false`.

#### Parameters

| Parameter | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `tenant` | `String` | No | `""` | Passed to the engine; the server takes the tenant from the credential. If blank, defaults to `client.me()?.tenant`. |

#### Return Value
- **Type**: `Boolean`
- **Description**: `true` when the sync ran and was merged; `false` when no storage is configured. Network and protocol errors throw `AdukiException`.

#### How it syncs (full-list reconcile)

The server has no incremental contacts route over REST, so the transport
(`pro.aduki.sync.http.HttpContactTransport`, on `client.contactsApi`) reads
`GET /v1/user/contacts?limit=200` page by page (cursor `after` = the previous
`next`) with scope `contacts:read`, then:

- stores a digest of every `hex:etag` pair as the sync token; if it equals the
  stored one the run changes nothing;
- otherwise merges every row and removes local contacts the server no longer
  lists.

Limitations, all from the list route: a row has no vCard (the server has no
`GET /user/contacts/{hex}`), so `vcard` stays empty unless you set it, and an
existing local `vcard` is kept; only the first e-mail address and phone are
kept; there is no modification time, so `updated` is the creation time. The
server's JMAP `Contact/changes` could make this incremental; it is not used
yet. Cost grows with the size of the address book, one request per 200
contacts.

```http
GET /v1/user/contacts?limit=200&after=<next> HTTP/1.1
Authorization: Bearer <access token>
```

```json
{"success": true, "data": {"items": [
  {"hex": "...", "etag": "...", "name": "Alice Bob", "emails": ["alice@aduki.pro"],
   "phones": null, "groups": ["friends"], "created": "2026-09-26T09:02:20.658460", "total": 1}
], "total": 1, "next": "..."}}
```

---

### `observe`
Returns a hot, reactive `StateFlow` emitting the full list of address book contacts sorted alphabetically by name.

```kotlin
fun observe(): StateFlow<List<Contact>>?
```

- **Return Type**: `StateFlow<List<Contact>>?` — Stream populated by an ObjectBox live query. Returns `null` if contact repository is uninitialized.

---

### `search`
Runs a case-insensitive substring search across `name`, `email`, and `phone` properties.

```kotlin
fun search(query: String): StateFlow<List<Contact>>?
```

#### Parameters

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `query` | `String` | Yes | Search term (e.g. `"alice"` or `"555"`). |

#### Return Value
- **Type**: `StateFlow<List<Contact>>?` — Reactive stream updating as contacts are added or modified matching the query.

---

### `get`
Looks up synchronously of a contact by its unique hexadecimal identifier.

```kotlin
fun get(hex: String): Contact?
```

#### Parameters

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `hex` | `String` | Yes | Contact unique identifier hex. |

#### Return Value
- **Type**: `Contact?` — The matched contact entity, or `null` if not found in local ObjectBox storage.

---

## 3. Data Model: `Contact` Entity

```kotlin
package pro.aduki.store.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Contact(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var name: String = "",
    @Index var email: String = "",
    var phone: String = "",
    var company: String = "",
    var vcard: String = "",
    var ctag: String = "",
    var updated: Long = 0
)
```

- `id: Long`: Local ObjectBox 64-bit record ID (`0` for new inserts).
- `hex: String`: Globally unique contact hexadecimal ID (indexed).
- `name: String`: Contact full display name (indexed).
- `email: String`: Contact primary email address (indexed).
- `phone: String`: Contact phone number.
- `company: String`: Organization affiliation.
- `vcard: String`: Raw RFC 6350 vCard v4.0 representation.
- `ctag: String`: Upstream synchronization generation cursor.
- `updated: Long`: Milliseconds timestamp of last modification.

