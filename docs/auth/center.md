# Account Center & Device Unlock

The calls are blocking (run them off the main thread). Each takes an HTTP client and the Aduki ID base, `https://id.aduki.pro/v1`.

`pro.aduki.net.http.Center` talks to Aduki ID's Account Center
(ADK-AUTH-002 §5) and device-bound unlock (ADK-AUTH-001 §9).

| Call | Endpoint | Returns |
|------|----------|---------|
| `Center.link` | `POST /links` | `Switcher` — center token plus linked accounts |
| `Center.fetch` | `GET /center` | `Switcher?` (`null` when no center exists) |
| `Center.unlink` | `DELETE /links` | `Boolean` |
| `Center.register` | `POST /credentials` | credential id |
| `Center.unlock` | `POST /credentials/{id}/challenges` then `/credentials/{id}/unlock` | `List<Unlocked>` |

## Device unlock

1. Generate an Ed25519 key pair on the device and keep the private key in the platform keystore where the device supports Ed25519 there; the SDK only asks for a `sign` callback and never sees the private key. (The SDK does not ship a Keystore-backed key yet.)
2. Register the 32-byte public key with `Center.register` (a step-up code is required).
   Pass `center = true` to register against the Account Center instead of one account.
3. To unlock, call `Center.unlock` with a `sign` callback. The server issues a
   single-use nonce (valid for 2 minutes); the callback signs it and the server
   returns tier-1 sessions for every account that allows center unlock.

Credentials stay trusted for 60 days after last use. Once that window lapses, the
user must sign in with their password again.
