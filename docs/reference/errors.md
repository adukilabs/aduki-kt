# Error Handling & Exception Reference

All errors originating from the Aduki Android SDK are modeled as strongly-typed subclasses of the sealed `AdukiException` hierarchy.

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

    class Auth(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Unauthorized(message: String, cause: Throwable? = null) :
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

## 2. Exception Types & HTTP Status Code Mapping

| Exception Subclass | Triggering Condition | HTTP Status Codes | Recommended Recovery Action |
| :--- | :--- | :--- | :--- |
| `AdukiException.Unauthorized` | Invalid credentials, missing/incorrect TOTP code, expired access token, or revoked refresh token. | `401 Unauthorized` | Attempt silent `client.refresh()`; if that fails, navigate user to interactive login screen. |
| `AdukiException.Auth` | Permission scope insufficient or forbidden tenant access. | `403 Forbidden` | Inform user of permission deficiency; request tenant owner elevation. |
| `AdukiException.Network` | TCP timeouts, DNS resolution failure, SSL handshake errors, or HTTP 5xx errors. | `500`, `502`, `503`, `504` | Enqueue to offline outbox journal; retry via Decorrelated Jitter backoff. |
| `AdukiException.Storage` | ObjectBox database disk full, filesystem permission error, or encryption key corruption. | N/A (Local) | Verify device storage quota; re-initialize database if key is corrupted. |
| `AdukiException.Sync` | CONDSTORE sequence mismatch, unrecoverable UIDVALIDITY divergence, or schema conflict. | `409 Conflict`, `422 Unprocessable` | Invalidate local folder cache and execute fresh CONDSTORE re-seed. |
| `AdukiException.Protocol` | Malformed JSON response, missing mandatory fields, or FlatBuffers decoding error. | N/A | Log protocol diagnostics report for SDK support. |
| `AdukiException.CircuitOpen` | Failure threshold exceeded (5 consecutive errors); circuit tripped to open state. | N/A (Internal) | Display offline banner; await circuit cooldown (30 seconds) before retrying network calls. |

---

## 3. Recommended Handling Patterns

### Comprehensive Error Handling in ViewModel

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    try {
        client.sync.all()
    } catch (e: AdukiException.Unauthorized) {
        // Step 1: Attempt silent token rotation
        val refreshed = client.refresh()
        if (!refreshed) {
            // Step 2: Refresh token is dead — clear session and redirect to login
            client.logout()
            _navEvents.emit(NavDestination.Login)
        }
    } catch (e: AdukiException.CircuitOpen) {
        // Device is offline or server experiencing outage — graceful UI degradation
        _uiState.update { it.copy(isOffline = true) }
    } catch (e: AdukiException.Network) {
        // Network blip — Outbox already handles queueing
        Log.w("Aduki", "Network synchronization deferred: ${e.message} (HTTP ${e.code})")
    } catch (e: AdukiException.Storage) {
        // Critical storage failure
        Log.e("Aduki", "Fatal storage error: ${e.message}", e)
    }
}
```

