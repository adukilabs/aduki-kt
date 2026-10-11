# Space client (JVM, draft)

`pro.aduki.net.http.Space` is a client for the Aduki Space REST API
(`https://space.aduki.pro/v1`, audience `space`). It is a library class in the
`net` module: it is not yet reachable from `Aduki`, has no local store, and has
never run against a real Space server (fake-server tests only). The Space DTOs
are still Draft; the shapes used here are read from the Space handlers.

## Credentials

Space accepts only DPoP-bound access tokens. Give the `Id` client and the
`Space` client the same HTTP client with the [DPoP](../auth/dpop.md) interceptor
and sign in for audience `space`; every call is then sent as
`Authorization: DPoP <token>` with a proof. A 401 renews the token once and
retries once; a second 401 is `AdukiException.Unauthorized`.

```kotlin
val http = OkHttpClient.Builder().addInterceptor(Dpop(key)).build()
val id = Id(http)
id.signIn("ada@acme.example", password, code = totp, audience = "space")
val space = Space(http, id)
val page = space.tasks(status = "open", limit = 20)
```

The calls are blocking; run them off the main thread.

## What it covers

| Call | Route |
|---|---|
| `call(method, path, query, body, idempotency, ifMatch)` | any route; returns `Reply(status, data, etag)` |
| `list(path, query, limit, after)`, `all(path, query, size)` | keyset lists `{items, next}`; `all` is a lazy sequence over the pages |
| `tasks(...)`, `task(hex)`, `addTask`, `editTask(hex, changes, version)` | `/tasks`, `/tasks/{hex}` |
| `checkins(task)`, `addCheckin(task, body, minutes, proof)` | `/tasks/{hex}/checkins` |
| `tickets(...)`, `addTicket`, `messages(ticket)`, `reply(ticket, body, internal)` | `/tickets`, `/tickets/{hex}/messages` |
| `search(q, entity, limit)` | `/search` (ids by query: `{collection, id}`) |
| `settings()`, `editSettings(changes, version)` | `/settings` |

Anything else (invoices, payments, payroll, nodes, portal, ...) is reachable through
`call` and `list` with the documented path and body; typed helpers for it wait for
the DTOs to settle.

## Rules the client follows

- Envelope `{success, data}`; a failure is `AdukiException.Api(status, kind, message,
  details, retryAfter)`: `kind` is the stable dotted identifier (`entity.conflict`,
  `sod.refused`, `rate.limited`, `tenant.syncing`, ...), `details` the raw JSON object
  text, `retryAfter` the `Retry-After` seconds. Branch on `kind` and `details`, not
  the message.
- Creating calls send an `Idempotency-Key` (a new UUID unless you pass one; pass your own
  to retry safely). A key that is not a UUID is refused before any request.
- `task()` and `settings()` return the `ETag`; pass it as `version` to the edit so a
  stale write is `409 entity.conflict` instead of a lost update.
- Lists take `limit` 1 to 100 and the opaque `after` cursor; there is no offset and no
  total. A cursor is bound to its route and filters: reuse it only with the same ones.
- Requests are strict: the server refuses unknown query parameters and body fields
  (`422 input.invalid`), so send only documented fields. Ids are sixteen lowercase hex
  digits (checked before the request). Money is integer minor units with a `currency`;
  durations are minutes.
- Which routes a caller may use is Aduki ID's: the token carries the rights bits; a refused
  or invisible entity is `403` or the same `404`.

## Not built

A `Space` entry on `Aduki` and a local store or sync for Space data; typed models for
invoices, payments and payroll; the realtime channel (Sockudo) and upload tickets for
Store; retry on `tenant.syncing` (`Retry-After: 2`) is left to the caller.
