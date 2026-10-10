# Lifecycle Management Reference

The `Lifecycle` coordinator bridges Android system lifecycle states to Aduki SDK network polling, socket keepalives, and outbox synchronization.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Lifecycle {
    fun active(): Boolean
    fun pause()
    fun resume()
    fun listen(listener: (Boolean) -> Unit)
}
```

### Methods on `Aduki`
Direct facade shortcuts are available on `Aduki`:

```kotlin
fun Aduki.pause()   // Delegates to lifecycle.pause()
fun Aduki.resume()  // Delegates to lifecycle.resume()
```

---

## 2. Detailed Method Specifications

### `active`
Queries whether the SDK considers the application currently foregrounded and active.

```kotlin
fun active(): Boolean
```

- **Return Type**: `Boolean` — Returns `true` if active / foreground; `false` if paused / backgrounded. Defaults to `true` upon initialization.

---

### `pause`
Transitions SDK state to background. Immediately halts continuous polling, suspends gRPC streaming channels, and conserves device battery.

```kotlin
fun pause()
```

- **Concurrency**: Thread-safe (backed by internal state synchronization).
- **Listeners**: Fires all callbacks registered via `listen` with `false`.

---

### `resume`
Transitions SDK state to foreground. Reactivates network polling and triggers an immediate asynchronous flush of all queued outbox mutations.

```kotlin
fun resume()
```

- **Automatic Flush Trigger**: `Aduki` installs an internal listener that launches a coroutine (`SupervisorJob + Dispatchers.IO`) calling `client.sync.flush()` upon resume.
- **Listeners**: Fires all callbacks registered via `listen` with `true`.

---

### `listen`
Registers a callback lambda to receive state transition events (`true` for resumed, `false` for paused).

```kotlin
fun listen(listener: (Boolean) -> Unit)
```

- **Thread-Safety**: Uses `CopyOnWriteArrayList` to ensure safe concurrent iteration and registration.

---

## 3. Production Android Architecture Integration

The recommended integration utilizes AndroidX `ProcessLifecycleOwner`:

```kotlin
package com.example.adukiapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import pro.aduki.sdk.Aduki

class App : Application(), DefaultLifecycleObserver {

    lateinit var client: Aduki
        private set

    override fun onCreate() {
        super.onCreate()

        client = Aduki.builder()
            .key(BuildConfig.ADUKI_API_KEY)
            .build()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // App enters foreground — flush pending outbox actions and resume sync
        client.resume()
    }

    override fun onStop(owner: LifecycleOwner) {
        // App enters background — suspend polling to conserve radio and battery
        client.pause()
    }
}
```

