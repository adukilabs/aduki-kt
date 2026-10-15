# Indexes and queries

Fields marked `@Index` get an ObjectBox index, and the generator produces
property descriptors (`Message_`, `Contact_`) for queries.

| Entity | Indexed fields |
| :--- | :--- |
| `Message` | `hex`, `mailbox`, `uid`, `threadId`, `flags`, `receivedAt` |
| `Contact` | `hex`, `emailIndex`, `phoneIndex` |
| `Mailbox` | `hex` |
| `Outbox` | `hex`, `action`, `created` |
| `Sync` | `target` |

> **Note on Sealed Columns:** Personal text columns (`Contact.name`, `Contact.email`, `Contact.phone`, `Contact.company`, `Contact.vcard`, `Message.subject`, `Message.preview`, etc.) are sealed with AES-256-GCM under the Vault. They cannot be queried via plaintext database indexes. Exact lookups on contacts use keyed blind indexes (`emailIndex`, `phoneIndex`), and substring/autocomplete searches run in memory over decrypted rows.

## Query examples

```kotlin
// A page of a mailbox, newest first
val query = messageBox.query(Message_.mailbox.equal(mailboxHex))
    .orderDesc(Message_.receivedAt)
    .build()
val page: List<Message> = query.find(0, 50)
query.close()

// Exact contact lookup via keyed blind index
val emailBlindIndex = Sealing.index("email", "ada@example.com")
val contact = contactBox.query(Contact_.emailIndex.equal(emailBlindIndex))
    .build()
    .findFirst()

// Free-text and substring search across contacts (in-memory over decrypted rows)
// Exposes a reactive StateFlow updated on address book changes:
val searchResults: StateFlow<List<Contact>>? = client.contacts.search("ali")
```

Always `close()` a query you built, or use `query.use { }`.

