# OIDC client ("Sign in with Aduki")

`pro.aduki.net.http.Oidc` is an OpenID Connect relying-party client for apps
that use Aduki ID as their identity provider. It runs the authorization code
flow with PKCE `S256` (mandatory at Aduki ID; there is no `plain`).

```kotlin
val oidc = Oidc(
    client = okhttp,                       // no credentials of its own
    issuer = "https://id.aduki.pro",       // the Aduki ID origin
    id = "my-client-id",
    redirect = "myapp://callback",
    secret = null                          // null: public client (PKCE only)
)
```

`secret` selects `client_secret_basic` (default) or, with `post = true`,
`client_secret_post`. Pass `dpop = Dpop(key)` to bind tokens to a device key.

## Flow

```kotlin
val begin = oidc.begin(listOf("openid", "profile", "offline_access"))
// 1. Open begin.url in a browser (Custom Tabs on Android). The browser step is yours.
// 2. When the browser lands on your redirect URI, pass that URI:
val grant = oidc.finish(begin, callbackUri)
grant.claims      // validated id token claims
grant.access      // access token
grant.refresh     // rotating refresh token (with offline_access)
```

Keep `begin` (its `state`, `nonce` and PKCE `verifier`) until the redirect.

| Method | Does |
| :--- | :--- |
| `discover()` | fetches `/.well-known/openid-configuration`; the issuer must match exactly and `S256` must be offered |
| `begin(scope)` | fresh `state`, `nonce` and verifier; returns the authorization URL. `scope` must contain `openid` |
| `finish(begin, callback)` | checks `error`, `state` and `iss` (RFC 9207), exchanges the code, validates the id token |
| `refresh(refreshToken, scope)` | renews; the refresh token rotates, store the new one. A `scope` may only narrow |
| `userinfo(grant)` | userinfo claims; sent with the `DPoP` scheme when the token is bound |
| `revoke(token)` | revokes a token (a refresh token takes its family) |

| `startDevice(scope)` | device authorization grant (RFC 8628), step 1: returns a `DeviceCode` with `userCode`, `verificationUri` and the polling `interval` |
| `pollDevice(code)` | one poll: `Pending`, `SlowDown(next)` (interval plus 5 s) or `Done(grant)` |
| `awaitDevice(code)` | polls until the person decides; see below |

An authorization code is single use: a failed exchange cannot be retried;
start a new `begin`.

## Id token validation

`EdDSA` signatures only, verified against the provider's JWKS by `kid` (one
refetch on an unknown `kid`); `iss`, `aud`, `azp`, `exp` and `iat` (30 s skew),
`nonce`, `sub` and `at_hash` are checked. Failures throw
`AdukiException.Protocol`; `access_denied`, `invalid_grant`, `invalid_client`
and `invalid_token` map to `AdukiException.Unauthorized`; other HTTP failures
to `AdukiException.Network`.

## Device authorization grant (RFC 8628)

For televisions, consoles and command-line tools with no browser. The client
must be allowed to use the grant at Aduki ID (`device: true` on the client).

```kotlin
val oidc = Oidc(http, "https://id.aduki.pro", "my-client", redirect = "unused")
val code = oidc.startDevice(listOf("openid", "offline_access"))
show(code.userCode, code.verificationUri)          // or a QR of code.verificationUriComplete
val grant = oidc.awaitDevice(code)                  // blocks; run it off the main thread
```

`awaitDevice` waits `interval` seconds before each poll, adds 5 seconds on
every `slow_down`, never waits past the local deadline (`expires_in`), retries
up to three consecutive network failures, 429 and 5xx answers, and validates
the id token like `finish`. Outcomes: `AdukiException.Denied` (the person
refused), `AdukiException.Expired` (deadline, `expired_token` or the `cancelled`
callback), `AdukiException.Unauthorized` (`invalid_grant`, `invalid_client`),
`AdukiException.Network` (other failures). `pollDevice` is the single-step form
for callers that run their own timer. The redirect argument of `Oidc` is not
used by this grant.
