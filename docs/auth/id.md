# Aduki ID client

`pro.aduki.net.http.Id` is the client for Aduki ID's sign-in routes. One
sign-in gives access tokens for several audiences (`mail`, `id`, and others as
products add them), all derived from one rotating refresh token. `Aduki` uses
it for renewal and sign-out; you can also use it directly.

```kotlin
val id = Id(client = okhttp, base = "https://id.aduki.pro/v1")
```

`client` must carry no credentials of its own. `base` defaults to
`Endpoints.ID`.

## Methods

| Method | Does |
| :--- | :--- |
| `signIn(handle, password, code, backup, audience = "mail")` | `POST /v1/sessions`; caches the token for `audience`; returns `Tokens` |
| `adopt(tokens, audience = "mail")` | takes over an existing sign-in, e.g. restored from your own storage |
| `token(audience)` | a valid access token for `audience`, renewed when missing or expired (30 s early) |
| `rotate(audience, stale)` | renews now unless another caller already replaced `stale`; returns the current `Tokens` |
| `call(audience, build)` | sends the request `build(token)` makes; on `401` renews once and sends once more, never loops |
| `refreshToken()` | the refresh token to persist after each renewal (blank once spent) |
| `session()`, `signedIn()` | the session hex; whether a refresh token is held |
| `signOut()` | revokes the session at Aduki ID and forgets every token; returns whether it was revoked. When revocation fails the rotated refresh token is kept for a retry |
| `clear()` | forgets everything locally without calling Aduki ID |

## Guarantees

- Renewals are serialized. A refresh token is single use; presenting a spent
  one revokes the session, so each token is sent at most once.
- A refresh token Aduki ID refuses (spent, expired, revoked) is dropped and
  `AdukiException.Unauthorized` is thrown; sign in again.
- A `2xx` that fails validation (a missing field) still spent the token: the
  rotated token it did carry is kept (`AdukiException.Auth.refresh`).
- Network failures and `429`/`5xx` leave the token unspent, so a retry is safe.

## Persisting a session

The SDK keeps tokens in memory. To survive a restart, store
`id.refreshToken()` and the session hex (`id.session()`) after each renewal in
the app's own secure storage, then restore with `id.adopt(Tokens(...))` or
`client.session.update(tokens)`.
