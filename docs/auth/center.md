# Account Center & Device Unlock

`pro.aduki.hermes.net.http.Center` talks to Aduki ID's Account Center
(ADK-AUTH-002 §5) and device-bound unlock (ADK-AUTH-001 §9).

| Call | Endpoint | Returns |
|------|----------|---------|
| `Center.link` | `POST /links` | `Switcher` — center token plus linked accounts |
| `Center.fetch` | `GET /center` | `Switcher?` (`null` when no center exists) |
| `Center.unlink` | `DELETE /links` | `Boolean` |
| `Center.register` | `POST /credentials` | credential id |
| `Center.unlock` | `POST /credentials/{id}/challenges` then `/credentials/{id}/unlock` | `List<Unlocked>` |

## Device unlock

1. Generate an Ed25519 key pair in the Android KeyStore.
2. Register the 32-byte public key with `Center.register` (a step-up code is required).
   Pass `center = true` to register against the Account Center instead of one account.
3. To unlock, call `Center.unlock` with a `sign` callback. The server issues a
   single-use nonce (valid for 2 minutes); the callback signs it and the server
   returns tier-1 sessions for every account that allows center unlock.

Credentials stay trusted for 60 days after last use. Once that window lapses, the
user must sign in with their password again.
