# Sign-in with Aduki ID

Mail no longer signs people in. Every Aduki app signs in at Aduki ID
(`id.aduki.pro`) and presents an audience-scoped token to the service it
calls; mail accepts tokens with audience `mail`. API keys
(`Authorization: Key hm_…`) are unchanged.

```text
 App ──POST /v1/sessions {handle, password, code, audience: mail}──▶ Aduki ID
     ◀──────────── {session, access (10 min), refresh, expires} ───────
 App ──Bearer access──▶ Mail REST / gRPC
     ◀── 401 ─────────  (token expired)
 App ──POST /v1/tokens {refresh, audience: mail}──▶ Aduki ID   (automatic)
 App ──retry with the new token──▶ Mail
 App ──POST /v1/tokens {refresh, audience: id}; DELETE /v1/sessions/{hex}──▶ Aduki ID   (logout)
```

## Endpoints (Aduki ID)

| Call | Body | Answer (`data`) |
| --- | --- | --- |
| `POST /v1/sessions` | `handle`, `password`, `code` or `backup`, `audience` | `session`, `access`, `refresh`, `expires` |
| `POST /v1/tokens` | `refresh`, `audience` | `access`, `refresh`, `expires` |
| `DELETE /v1/sessions/{hex}` | — (Bearer, audience `id`) | `204` |

Answers use Aduki ID's envelope, `{"success": true, "data": {…}}`, and errors
`{"success": false, "error": {"status", "kind", "message"}}`. Refresh tokens
are single-use: presenting a spent one revokes the session, so the SDK
serializes renewals.

A second factor is required. Accounts set it up in Aduki ID's Account Center;
mail's `PATCH /v1/user/totp` (`Aduki.totp`) is deprecated.

## Usage

```kotlin
val client = Aduki.login(
    handle = "ada@aduki.me",
    password = password,
    code = "123456"
)

client.me()      // GET /v1/user on mail
client.logout()  // revokes the session at Aduki ID
```

Point at other deployments with `endpoint = …` (mail) and `identity = …`
(Aduki ID), or `Aduki.builder().identity(…)`.
