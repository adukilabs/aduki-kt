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

    class Network(message: String, cause: Throwable? = null, val code: Int? = null, val retryAfter: Long? = null) :
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
    ) : AdukiException("$kind: $message") {
        /** A 403 `locked`: the tenant locks profiles and the caller is not an owner. */
        val locked: Boolean
    }

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
| `Api` | An Aduki service returned a failure envelope with an HTTP status, stable dotted `kind` (e.g. `entity.conflict`, `rate.limited`, `sod.refused`), raw `details` JSON, and optional `retryAfter` | branch on `e.kind`; wait `e.retryAfter` seconds if rate limited; refresh on `409 entity.conflict`; `e.locked` for a `403 locked` profile write |
| `Denied` | The person refused an authorization (OAuth/device authorization grant `access_denied`) | user opted out; abort flow |
| `Expired` | A device code or authorization expired (`expired_token` or local deadline) | start a new authorization flow |
| `Network` | transport failure (timeout, DNS, TLS), or an HTTP error not covered above; `code` holds the status when there was a response (e.g. `429`), `retryAfter` the server's `Retry-After` seconds | retry later, not before `retryAfter`; the outbox does this for queued changes |
| `Protocol` | a response that cannot be parsed (an HTML page, an empty or cut body), a missing mandatory field, an OIDC check failing (state, issuer, id token), or any other unexpected exception inside an SDK call (the original is the `cause`) | report it; do not retry blindly |
| `CircuitOpen` | a `Circuit` you use is open | wait for the cooldown |
| `Storage` | the local database failed inside an SDK call (the ObjectBox exception is the `cause`) | report it |
| `Sync` | declared for sync failures | the SDK does not raise it yet |

Besides `AdukiException`, a public `suspend` call can throw `IOException`
(transport), `IllegalArgumentException` or `IllegalStateException` (a wrong
call), and `CancellationException`; nothing else. Background work reports to
`Aduki.fault` instead of throwing. See [Failure handling](../resilience.md).

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

