# Indexes and queries

Fields marked `@Index` get an ObjectBox index, and the generator produces
property descriptors (`Message_`, `Contact_`) for queries.

| Entity | Indexed fields |
| :--- | :--- |
| `Message` | `hex`, `mailbox`, `uid`, `threadId`, `flags`, `receivedAt` |
| `Contact` | `hex`, `name`, `email` |
| `Mailbox` | `hex` |
| `Outbox` | `hex`, `action`, `created` |
| `Sync` | `target` |

## Query examples

```kotlin
// A page of a mailbox, newest first
val query = messageBox.query(Message_.mailbox.equal(mailboxHex))
    .orderDesc(Message_.receivedAt)
    .build()
val page: List<Message> = query.find(0, 50)
query.close()

// Contacts matching a prefix, for autocomplete
val search = contactBox.query(
    Contact_.name.contains("ali", QueryBuilder.StringOrder.CASE_INSENSITIVE)
        .or(Contact_.email.contains("ali", QueryBuilder.StringOrder.CASE_INSENSITIVE))
).order(Contact_.name).build()
val results = search.find(0, 20)
search.close()
```

Always `close()` a query you built, or use `query.use { }`.
