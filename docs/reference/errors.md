# Error Handling & Exception Reference

Errors raised by the SDK's clients are subclasses of the sealed `AdukiException`.

---

## 1. Sealed Exception Class Hierarchy

```kotlin
package pro.aduki.core.errors

sealed class AdukiException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class Network(message: String, cause: Throwable? = null, val code: Int? = null) :
        AdukiException(message, cause)

    /** An auth response that could not be used; [refresh] carries a rotated refresh token it did include. */
    class Auth(message: String, cause: Throwable? = null, val refresh: String = "") :
        AdukiException(message, cause)

    class Unauthorized(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    /**
     * A failure envelope from an Aduki service (HTTP status, stable dotted kind, e.g. entity.conflict, rate.limited, sod.refused, raw details JSON, retryAfter seconds).
     */
    class Api(
        val status: Int,
        val kind: String,
        message: String,
        val details: String? = null,
        val retryAfter: Long? = null
    ) : AdukiException("$kind: $message")

    /** The user refused an authorization (device grant access_denied). */
    class Denied(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    /** A device code or authorization expired (expired_token or local deadline). */
    class Expired(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Storage(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Sync(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Protocol(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class CircuitOpen(message: String = "Circuit breaker is open") :
        AdukiException(message)
}
```

---

## 2. Exception types

| Exception | Raised when | Typical recovery |
| :--- | :--- | :--- |
| `Unauthorized` | Aduki ID refused a sign-in, refresh or unlock (wrong password, missing or wrong second factor, spent, expired or revoked refresh token); a signed-out `Id` has no refresh token; an OIDC error such as `invalid_grant` | sign in again |
| `Auth` | the mail or scheduling API answered `401`/`403`; an API key was refused; an Aduki ID `2xx` lacked a required field (`refresh` then holds the rotated token, if any) | renew the token (`client.refresh()`), or fix the account's rights |
| `Api` | An Aduki service returned a failure envelope with an HTTP status, stable dotted `kind` (e.g. `entity.conflict`, `rate.limited`, `sod.refused`), raw `details` JSON, and optional `retryAfter` | branch on `e.kind`; wait `e.retryAfter` seconds if rate limited; refresh on `409 entity.conflict` |
| `Denied` | The person refused an authorization (OAuth/device authorization grant `access_denied`) | user opted out; abort flow |
| `Expired` | A device code or authorization expired (`expired_token` or local deadline) | start a new authorization flow |
| `Network` | transport failure (timeout, DNS, TLS), or an HTTP error not covered above; `code` holds the status when there was a response (e.g. `429`) | retry later; the outbox does this for queued changes |
| `Protocol` | a response that cannot be parsed, a missing mandatory field, or an OIDC check failing (state, issuer, id token) | report it; do not retry blindly |
| `CircuitOpen` | a `Circuit` you use is open | wait for the cooldown |
| `Storage`, `Sync` | declared for local-store and sync failures | the SDK does not raise them yet |

Sign-in messages start with Aduki ID's error kind where it sent one, for
example `auth.factor: ...`.

---

## 3. Recommended Handling Patterns

### Error handling in a ViewModel

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    try {
        client.sync.all()
    } catch (e: AdukiException.Auth) {
        // The SDK already renews once on a 401; a second refusal lands here.
        // Step 1: Try an explicit renewal
        val refreshed = client.refresh()
        if (!refreshed) {
            // Step 2: Refresh token is dead — clear session and redirect to login
            client.logout()
            _navEvents.emit(NavDestination.Login)
        }
    } catch (e: AdukiException.CircuitOpen) {
        // Only if you wrap calls in a Circuit: fail fast while the service is down
    } catch (e: AdukiException.Api) {
        // Structured service error: inspect kind and retryAfter
        if (e.kind == "rate.limited" && e.retryAfter != null) {
            Log.w("Aduki", "Rate limited; retry after ${e.retryAfter}s")
        } else {
            Log.e("Aduki", "API failure: ${e.kind} (${e.status}) - ${e.message}")
        }
    } catch (e: AdukiException.Network) {
        // Network blip — Outbox already handles queueing
        Log.w("Aduki", "Network synchronization deferred: ${e.message} (HTTP ${e.code})")
    }
}
```

