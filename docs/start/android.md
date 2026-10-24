# Android module

`pro.aduki:android` is the one Android library of the SDK (an AAR, `minSdk` 26, `compileSdk` 34). The other modules stay plain JVM libraries; this one holds what needs Android classes: the Keystore device key, the Account Center authenticator, Custom Tabs sign-in and Credential Manager.

```kotlin
dependencies {
    implementation("pro.aduki:android:0.5.0")
}
```

In your app manifest placeholders set the scheme of your registered redirect (`scheme:/callback`):

```kotlin
android { defaultConfig { manifestPlaceholders["adukiRedirectScheme"] = "com.example.app" } }
```

| Part | Class | What it does |
| :--- | :--- | :--- |
| Device key | `keystore.AndroidKey` | non-extractable P-256 key in the Android Keystore; implements the `Key` of [DPoP](../auth/dpop.md) (`ES256`) |
| Accounts | `account.Accounts`, `AdukiAuthenticator`, `SwitcherState` | the linked accounts of an [Account Center](../auth/center.md) as `pro.aduki.account` entries in `AccountManager`; reconcile with the center; sealed tokens |
| Sign-in | `signin.SignIn`, `PendingStore`, `RedirectActivity` | [Sign in with Aduki](../auth/oidc.md) through a Custom Tab, redirect handling |
| Passkeys | `passkey.PasskeyFlow`, `CredentialManagerAuthenticator` | the [passkey](../auth/passkeys.md) ceremonies through Credential Manager |

## Device key

```kotlin
val key = AndroidKey.open()                 // creates "aduki_dpop" on first use
val aduki = Aduki.builder().dpop(key) /* ... */
key.protection().level                      // "software", "trusted_environment" or "strongbox"
key.extractable()                           // false
```

`open(alias, strongBox = true)` asks for StrongBox and falls back to the normal keystore when the device has none; read `protection()` for what you got. The signature is converted from the Keystore's DER to the fixed 64-byte `r || s` of JWS (`crypto.dpop.Der`). Deleting the alias (`AndroidKey.delete`) makes the thumbprint, and every session bound to it, unusable.

## Accounts

`Accounts.reconcile(switcher)` makes the entries of a center match what Aduki ID lists: new accounts are added, accounts the center no longer lists (a tenant `links = deny`, a forced unlink, the person's own unlink) are removed together with their tokens. `Accounts.refresh(client, identity, bearer, current)` does `Center.fetch` and reconciles. Access and refresh tokens are sealed with the [Vault](../security/vault.md) before they reach `AccountManager`; the center token is kept in its own sealed file and is never put in an account entry. `SwitcherState.of(accounts, active)` gives the rows a switcher shows (lock for `unlock = self`, "sign in again", instant switch).

Register how the authenticator refreshes a stale access token, and which activity adds an account:

```kotlin
Authenticator.refresher = { entry, refresh -> /* one DPoP-bound call to Aduki ID, or null when the session ended */ }
```

## Sign-in

```kotlin
val flow = SignIn(Oidc(http, issuer, clientId, "com.example.app:/callback", dpop = Dpop(key)),
                  "com.example.app:/callback", PendingStore(context, Vault.platform()))
SignInConfig.flow = flow
SignInConfig.done = { result -> /* a Grant or the failure, on a background thread */ }
thread { flow.start(context) }              // discovery is a network call: not on the main thread
```

`RedirectActivity` (declared in the library manifest) receives the redirect. It accepts only the exact scheme, authority and path of your registered redirect, needs the `state` of a sign-in this app started, spends it (a redirect works once), refuses it after ten minutes, and never logs the URL. The pending sign-in (PKCE verifier) is stored sealed, so it survives Android killing the app while the browser is open.

## Passkeys

`PasskeyFlow(client, identity, CredentialManagerAuthenticator(activity))` has `register`, `stepUp` and `signIn`. A dismissed sheet is `PasskeyFailure.Cancelled` (nothing is sent), a missing passkey `NoPasskey`, anything else `Platform`. The site must publish a Digital Asset Links file naming your package and signing certificate. Never run against a real Credential Manager provider yet.

## Build

```
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./gradlew test -Paduki.android=true          # JVM modules and the Robolectric tests
./gradlew :android:connectedDebugAndroidTest -Paduki.android=true   # emulator or phone
```

Without `-Paduki.android=true` (or `ADUKI_ANDROID=1`) only the JVM modules build, on JDK 17 to 22, and Google's Maven repository is never contacted. The Android modules need JDK 17 to 22 as well, AGP 8.13.2 and Gradle 9.5.1.

## Testing

Robolectric tests run in `./gradlew test -Paduki.android=true`, and the Keystore tests run on a device or emulator (`connectedDebugAndroidTest`). Emulators have software-backed keys: check hardware-backed keys and StrongBox on your target devices.

Device credentials (center unlock) use `ES256`: `Accounts.registerDevice` and `Accounts.unlockWithDevice` use a separate Keystore key (`AndroidKey.DEVICE`), see [Account Center](../auth/center.md).
