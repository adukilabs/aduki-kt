# Lifecycle Management Reference

The `Lifecycle` object tracks whether the app is in the foreground and tells interested parties when that changes. The one thing the SDK does itself on a change is to flush the outbox when the app resumes.

---

## 1. Class & Method Signatures

```kotlin
package pro.aduki.sdk

class Lifecycle {
    fun active(): Boolean
    fun pause()
    fun resume()
    fun listen(listener: (Boolean) -> Unit)
    fun remove(listener: (Boolean) -> Unit)
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
Marks the SDK as backgrounded and calls the listeners with `false`. The SDK does not stop any task by itself; use a listener to pause your own work.

```kotlin
fun pause()
```

- **Listeners**: Fires all callbacks registered via `listen` with `false`.

---

### `resume`
Marks the SDK as foregrounded and calls the listeners with `true`; `Aduki` has a listener that launches `sync.flush()` on resume.

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
        // App enters background
        client.pause()
    }
}
```

