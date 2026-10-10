# Contacts Service Reference

The `Contacts` service (`client.contacts`) provides address book synchronization, zero-copy contact lookups, and indexed substring search across names, emails, and phone numbers.

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
Performs an incremental delta synchronization using CardDAV-style `ctag` tokens.

```kotlin
suspend fun sync(tenant: String = ""): Boolean
```

#### Parameters

| Parameter | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `tenant` | `String` | No | `""` | Target tenant hex identifier. If empty or blank, defaults to `client.me()?.tenant`. |

#### Return Value
- **Type**: `Boolean`
- **Description**: Returns `true` if delta synchronizer successfully fetched and merged upstream changes; `false` on network or protocol error.

#### Network Wire Protocol

```http
GET /v1/contacts?ctag=ct_8f3a02c91b4e5d6f HTTP/1.1
Host: hermers.aduki.pro
Authorization: Bearer eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9...
Accept: application/json
```

##### Response (200 OK)

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "ctag": "ct_901c3d4e5f6a7b8c",
  "contacts": [
    {
      "hex": "c_01HZ9ABCD...",
      "name": "Alice Bob",
      "email": "alice@aduki.pro",
      "phone": "+1-555-0199",
      "company": "Aduki",
      "vcard": "BEGIN:VCARD...",
      "updated": 1725830000000
    }
  ]
}
```

---

### `observe`
Returns a hot, reactive `StateFlow` emitting the full list of address book contacts sorted alphabetically by name.

```kotlin
fun observe(): StateFlow<List<Contact>>?
```

- **Return Type**: `StateFlow<List<Contact>>?` — Hot stream populated directly from ObjectBox live query. Returns `null` if contact repository is uninitialized.

---

### `search`
Executes an indexed, case-insensitive substring search across `name`, `email`, and `phone` properties.

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
Performs a fast, zero-copy synchronous lookup of a contact by its unique hexadecimal identifier.

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
- `hex: String`: Globally unique contact hexadecimal ID (`@Index` indexed for O(1) key lookups).
- `name: String`: Contact full display name (`@Index` indexed for prefix/substring queries).
- `email: String`: Contact primary email address (`@Index` indexed).
- `phone: String`: Contact phone number.
- `company: String`: Organization affiliation.
- `vcard: String`: Raw RFC 6350 vCard v4.0 representation.
- `ctag: String`: Upstream synchronization generation cursor.
- `updated: Long`: Milliseconds timestamp of last modification.

