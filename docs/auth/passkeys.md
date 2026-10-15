# Passkeys (protocol side)

`pro.aduki.net.http.Passkeys` is the HTTP side of WebAuthn at Aduki ID: the begin
and verify calls of the three ceremonies, plus list and remove. It runs on a
plain JVM. The authenticator step is handled by the platform (Android Credential Manager):
on Android, use `PasskeyFlow` and `CredentialManagerAuthenticator` from the `android`
module (`pro.aduki:android`).

Each ceremony is two calls. `begin*` returns a `Challenge` (`ceremony`, `expires`,
`options`); give `challenge.optionsJson` to the platform API; pass the JSON the
platform returns to the matching `finish*` call. A challenge is spent by the
first answer, right or wrong, and lasts five minutes.

| Ceremony | Begin | Finish | Result |
|---|---|---|---|
| Register (step-up or `code`) | `beginRegistration(client, identity, bearer, label?, code?)` | `finishRegistration(..., challenge, registrationResponseJson)` | `Registered` |
| Step-up | `beginStepUp(client, identity, bearer)` | `finishStepUp(..., challenge, authenticationResponseJson)` | Unix seconds; renew the access token to get the new `stp` |
| Sign in (no account named) | `beginSignIn(client, identity)` | `finishSignIn(client, identity, challenge, authenticationResponseJson, audience, device)` | `Tokens` |

`list(client, identity, bearer)` returns your passkeys (`Item`, never key
material); `remove(client, identity, bearer, passkey, code?)` removes one.

`Passkeys.credential(json, registration)` is what the finish calls use: it checks
that the platform JSON is a `public-key` credential with `clientDataJSON` and
either `attestationObject` (registration) or `authenticatorData` and `signature`
(assertion), and keeps only the fields Aduki ID reads. A malformed credential
throws `IllegalArgumentException` before any request is made.

Errors: every failed verification is `AdukiException.Unauthorized` (`401
auth.invalid`, uniform). Registering and removing need a recent step-up or a
fresh authenticator `code`; otherwise the server answers `403 auth.stepup`, which
`Passkeys.needsStepUp(e)` recognises (step up, then retry). A malformed server
answer is `AdukiException.Protocol`. For a session bound to a device key, use an
HTTP client with the [DPoP](dpop.md) interceptor.

## Android: Credential Manager

`pro.aduki.android.passkey.PasskeyFlow` runs begin, the platform step and finish for `register`, `stepUp` and `signIn`; `CredentialManagerAuthenticator` hands `challenge.optionsJson` to `CreatePublicKeyCredentialRequest` / `GetPublicKeyCredentialOption` and returns the platform's response JSON. See [Android module](../start/android.md). Request shaping and error mapping are unit tested; a real passkey ceremony needs a device with a credential provider and is not verified.
