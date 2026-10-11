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
| `Center.revoke` | `DELETE /credentials/{id}` | `Boolean` (false when already gone) |
| `Center.unlock` | `POST /credentials/{id}/challenges` then `/credentials/{id}/unlock` | `List<Unlocked>` |

## Device unlock

A device credential is Ed25519 (`EdDSA`) or ECDSA P-256 (`ES256`, what the Android Keystore, StrongBox and the Secure Enclave hold). The algorithm and the public key encoding come from the `Key` you pass; they are never guessed by the server.

1. Keep the private key in the platform keystore. The SDK only asks the `Key` (or a `sign` callback) for a signature and never sees the private part.
2. Register it with `Center.register(client, identity, bearer, device, key, code, center)` (a fresh TOTP step-up code is required; `center = true` registers it for the Account Center instead of one account). The call sends `alg` and the public key: `ES256` sends the 65-byte uncompressed point `0x04 || x || y` (x and y left-padded to 32 bytes) in base64url without padding; `EdDSA` the 32-byte key. It checks that the server answered with the same `alg`. A P-256 key sent without `alg: "ES256"` is `422` at Aduki ID, so the old overload `register(..., public: ByteArray, ...)` is for Ed25519 only.
3. To unlock, call `Center.unlock(client, identity, credential, key)` (or the `sign` callback form). The server issues a single-use nonce (valid for 2 minutes, `{nonce, expires}`); the key signs the **raw nonce bytes** (not the base64url text). ES256 uses SHA-256 and the 64-byte `r || s` form: `Software` already returns it and `AndroidKey` converts the Keystore's DER with `crypto.dpop.Der.toRaw(der, 32)`. S is not normalised: low-S and high-S are both accepted. The server returns tier-1 sessions for every account that allows center unlock.

Errors: every unlock failure (bad signature, wrong key, spent, expired or foreign nonce) is the same `401 auth.invalid`, thrown as `AdukiException.Unauthorized`; the call is one attempt and does not retry, and the next call fetches a fresh nonce. A refused attempt does not spend the nonce at the server, a successful one does. `404` (`AdukiException.Network`, code 404) means the credential is revoked or lapsed: sign in fully. `422` at registration (`Network`, code 422) is a refused key or label, or an old Aduki ID that does not know `ES256`. A signature that is not 64 bytes (for example a DER signature) is refused locally with `IllegalArgumentException` and is never sent.

A credential works for 60 days after the full authentication that registered it. Once that window lapses, the user must sign in with their password again.

Status: built against the contract of Aduki ID PR 35 and tested on the JVM against a fake server with an independent verifier. Not yet run against a real Aduki ID.

## Android authenticator

The `android` module keeps the linked accounts as separate `AccountManager` entries under the type `pro.aduki.account` (`Accounts.reconcile`, `AdukiAuthenticator`; see [Android module](../start/android.md)). When Aduki ID removes a link (tenant `links = deny`, forced unlink), `Accounts.refresh` drops the entry and its tokens. The center token is stored in a separate sealed file and sent to Aduki ID only.

`Accounts.registerDevice` and `Accounts.unlockWithDevice` use `AndroidKey.open(AndroidKey.DEVICE)` (P-256, `ES256`) as the signer of this flow and store each unlocked session. This glue is UNVERIFIED: it could not be compiled or run without the Android SDK (see `guide/ANDROID-TESTS.md`).
