# Calls client (JVM, draft)

`pro.aduki.net.http.Calls` is a client for the aduki Calls API (ADK-AUDIO-013,
`https://voice.aduki.pro/v1`, audience `audio`). It is a library class in the
`net` module and covers the HTTP side of calls only: starting a call and ringing
people, the calls that ring for you, answers, tickets into a call, leaving, relays,
and the devices that ring. The media (the microphone, the speaker, the call itself)
is aduki Calls' own SDK (`pro.aduki.calls`, an AAR with a native engine), which
signs in through this client.

Aduki ID does not issue `audio` tokens yet. Until it does, every call ends in
`AdukiException.Unauthorized`; the client has run against fake servers only.

## Credentials

aduki Calls accepts only DPoP-bound access tokens. Give the `Id` client and the
`Calls` client the same HTTP client with the [DPoP](../auth/dpop.md) interceptor,
and pass that `Dpop` to `Calls` too; it signs the proofs of aduki Calls' SDK. A 401
renews the token once and retries once; a second 401 is
`AdukiException.Unauthorized`.

```kotlin
val dpop = Dpop(key)
val http = OkHttpClient.Builder().addInterceptor(dpop).build()
val id = Id(http)
id.signIn("ada@acme.example", password, code = totp)
val calls = Calls(http, id, dpop)
val call = calls.start(to = listOf(barakaHex))   // rings Baraka's devices
```

The calls are blocking; run them off the main thread.

## Signing in aduki Calls' SDK

aduki Calls' SDK never holds a refresh token or a key. It asks for an access token
and a DPoP proof per request through its `Identity` interface; `token()` and
`proof()` answer it:

```kotlin
val identity = object : pro.aduki.calls.Identity {
    override fun token(): String = calls.token()
    override fun proof(method: String, url: String, token: String): String = calls.proof(method, url, token)
}
val client = pro.aduki.calls.Client.withIdentity(url, models, identity)
```

`proof` also signs the signalling socket (`GET wss://.../v1/signal`): the query is
not signed, and the token is bound with `ath`.

## What it covers

| Call | Route |
|---|---|
| `capabilities()` | `GET /capabilities`: signalling URLs, push kinds, limits |
| `start(to, idempotency)` | `POST /calls`: a call, ringing up to 20 accounts; the same key returns the same call |
| `fetch(call)`, `list(limit, after)` | `GET /calls/{hex}`, `GET /calls` (newest first) |
| `ringing()` | `GET /calls?state=ringing`: what rings for you, after a ring's push |
| `answer(call, accept)` | `POST /calls/{hex}/answers`: a ticket when accepted; `409 state.conflict` once answered or missed (60 s) |
| `ticket(call)` | `POST /calls/{hex}/tokens`: a single-use ticket into the call |
| `leave(call)` | `DELETE /calls/{hex}/participants/me`: on every device |
| `relays()` | `POST /relays`: TURN servers, or none |
| `register(platform, token, label)`, `devices()`, `edit(device, token, label)`, `remove(device)` | `/devices`: the FCM token a device rings on (`null`: it rings nowhere) |

## Rings

A ring is a high-priority FCM data message to the registered token, with the data
keys `v`, `kind` (`ring`), `call`, `caller`, `name` and `until` (Unix seconds). The
SDK has no Firebase dependency: the app's own `FirebaseMessagingService` receives
it, shows the incoming call (aduki Calls' Android library has a ready one), and on
Answer calls `answer(call, true)` or lets aduki Calls' SDK do it. Register again
with `edit(device, token = new)` when Firebase rotates the token.

## Rules the client follows

- Envelope `{success, data}`; a failure is `AdukiException.Api(status, kind, message,
  details, retryAfter)`, with `kind` such as `state.conflict`, `rate.limited` or
  `input.invalid`. Branch on `kind`, not the message.
- Creating calls and devices sends an `Idempotency-Key` (a new UUID unless you pass
  one). A key that is not a UUID is refused before any request.
- Ids are sixteen lowercase hex digits, checked before the request. The server
  refuses unknown body fields.
