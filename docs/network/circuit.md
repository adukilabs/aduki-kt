# Circuit Breaker & Fault Tolerance Reference

`pro.aduki.core.retry.Circuit` is a small three-state circuit breaker for fail-fast behaviour during outages. It is a utility: the SDK's own clients do not wrap their calls in it, so use it around your own calls (for example a custom `Dispatcher` for the outbox).

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.core.retry

import pro.aduki.core.errors.AdukiException

class Circuit(
    private val threshold: Int = 5,
    private val timeout: Long = 15_000
) {
    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    fun state(): State
    fun success()
    fun fail()
    inline fun <T> execute(block: () -> T): T
}
```

### Parameters

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `threshold` | `Int` | `5` | Number of consecutive exceptions before the circuit trips from `CLOSED` to `OPEN`. |
| `timeout` | `Long` | `15_000` | Cooldown duration in milliseconds before an `OPEN` circuit transitions to `HALF_OPEN` for a canary probe. |

---

## 2. State Machine Specification

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: 5 consecutive failures (threshold reached)
    OPEN --> HALF_OPEN: 15 seconds elapsed (timeout)
    HALF_OPEN --> CLOSED: Canary request succeeds (success())
    HALF_OPEN --> OPEN: Canary request fails (fail())
```

### State Behaviors

| State | Request Execution Behavior |
| :--- | :--- |
| **`CLOSED`** | Normal execution. All requests execute directly. Failures increment an atomic counter; any success resets the failure count to 0. |
| **`OPEN`** | Fail-fast mode. Calls to `execute { ... }` immediately throw `AdukiException.CircuitOpen` without executing the block or waking the radio modem. |
| **`HALF_OPEN`** | Canary probing mode. Allows a single execution. If it succeeds, circuit resets to `CLOSED`. If it fails, circuit immediately returns to `OPEN`. |

---

## 3. Usage & Exception Handling Example

```kotlin
val circuit = Circuit(threshold = 5, timeout = 15_000L)

try {
    val response = circuit.execute {
        transport.dispatch(payload)
    }
} catch (e: AdukiException.CircuitOpen) {
    // Immediate fail-fast: device is offline or remote service is unresponsive
    // Outbox actions remain safely stored in local ObjectBox database
    showOfflineStatusBanner()
} catch (e: Exception) {
    // Network or transport error — circuit counted this as a failure
    Log.w("Aduki", "Call failed: ${e.message}")
}
```

