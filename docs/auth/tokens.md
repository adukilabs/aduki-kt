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

Renewals are serialized. Refresh tokens rotate, and presenting a spent one
makes Aduki ID revoke the whole session, so concurrent requests that fail
together share one renewal. API keys are never renewed.

## `refresh`

```kotlin
suspend fun HermesClient.refresh(): Boolean
```

Renews ahead of time. Returns `false` when there is no refresh token or Aduki ID
refused it (sign in again).

## `logout`

```kotlin
suspend fun HermesClient.logout(): Boolean
```

Revokes the session at Aduki ID, which ends every token issued from it, and
clears `client.session`. Aduki ID only accepts its own tokens for this, so the
SDK first swaps the refresh token for an `id`-audience token:

```http
POST /v1/tokens            { "refresh": "…", "audience": "id" }
DELETE /v1/sessions/{hex}  Authorization: Bearer <id token>
```

Returns `false` if the session could not be revoked (local state is cleared
either way). Clients built from an API key have no session and return `false`.

## Session state

```kotlin
client.session.tokens      // StateFlow<Tokens?>
client.session.token()     // current access token
client.session.refresh()   // current refresh token
```

`Tokens.session` is kept across renewals.
