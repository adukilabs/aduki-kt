# Token lifecycle

An Aduki ID sign-in gives a 10-minute access token for mail, a single-use
refresh token and a session. The SDK renews the access token itself; apps only
sign out.

## Automatic renewal

When mail answers `401` to a request sent with a Bearer token, the SDK renews
once and retries the request:

```http
POST /v1/tokens HTTP/1.1
Host: id.aduki.pro
Content-Type: application/json

{ "refresh": "…", "audience": "mail" }
```

```json
{ "success": true, "data": { "access": "…", "refresh": "…", "expires": 600 } }
```

Renewals are serialized through the [`Id` client](id.md). Refresh tokens
rotate, and presenting a spent one makes Aduki ID revoke the whole session, so
concurrent requests that fail together share one renewal, and a refresh token
that Aduki ID refused is dropped and never presented again. API keys are never
renewed.

## `refresh`

```kotlin
suspend fun Aduki.refresh(): Boolean
```

Renews ahead of time. Returns `false` when there is no refresh token or Aduki ID
refused it (sign in again).

## `logout`

```kotlin
suspend fun Aduki.logout(): Boolean
```

Revokes the session at Aduki ID, which ends every token issued from it, and
clears `client.session`. Aduki ID only accepts its own tokens for this, so the
SDK first swaps the refresh token for an `id`-audience token:

```http
POST /v1/tokens            { "refresh": "…", "audience": "id" }
DELETE /v1/sessions/{hex}  Authorization: Bearer <id token>
```

Returns `false` if the session could not be revoked. The swap spent the old
refresh token, so the SDK then keeps the session with the rotated one: call
`logout()` again to retry, or `session.clear()` to forget it locally. Clients
built from an API key have no session and return `false`.

A client passed to `Builder.http(...)` gets the same renewal unless it has its
own authenticator. Aduki ID calls use its connection settings without its
interceptors, so they never carry the mail token.

## Session state

```kotlin
client.session.tokens      // StateFlow<Tokens?>
client.session.token()     // current access token
client.session.refresh()   // current refresh token
```

`Tokens.session` is kept across renewals.

## Rights stream

When an account's rights change (a role edit, a revoked grant, an unlink),
Aduki Mail sends `event: rights` with `{"@type": "Rights", "epoch": N}` on its
JMAP EventSource (a key rotation sends `{"@type": "Rights", "keys": true}`),
and then ends the stream. The access token should be renewed at once, before
the next request is refused with `auth.stale`.

The SDK can do this for you:

```kotlin
client.watchRights()    // subscribe to {mail host}/jmap/eventsource
// ...
client.unwatchRights()  // also done by logout()
```

`watchRights(host = <REST endpoint without /v1>)` opens
`GET {host}/jmap/eventsource?types=*&ping=30` on the authenticated connection
and renews the token on every `rights` event. After a `rights` event the
stream is reopened immediately; after a failure it reconnects with
decorrelated jitter backoff (1 s up to 60 s). Calling it again replaces the
previous subscription.

If your app receives the same payload another way (web push, FCM), hand the
body to `client.rights(payload)`; it renews for `{"@type": "Rights", ...}` and
ignores anything else, returning whether a token was renewed.

```kotlin
scope.launch { client.rights(data) }
```

The push only saves a round trip. A stale token is refused and then renewed
by the `401` path whether or not a push arrived. The stream source exists for
audience `mail` only; a client with no mail audience needs its own product's
event.
