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

`Accounts.reconcile(switcher)` makes the entries of a center match what Aduki ID lists: new accounts are added, accounts the center no longer lists (a tenant `links = deny`, a forced unlink, the person's own unlink) are removed together with their tokens. `Accounts.refresh(client, identity, bearer, current)` does `Center.fetch` and reconciles. The center token is kept in its own sealed file and is never put in an account entry. `SwitcherState.of(accounts, active)` gives the rows a switcher shows (lock for `unlock = self`, "sign in again", instant switch).

### Where tokens live: `Accounts.Mode`

```kotlin
class Accounts(
    context: Context,
    val mode: Mode = Mode.Own(),                       // Mode.Own(Vault.platform())
    manager: AccountManager = AccountManager.get(context),
    center: CenterStore = CenterStore(context, mode.vault),
    visibility: Visibility = Visibility.system(manager)
)
```

There is always exactly one copy of a session's tokens:

| Mode | Tokens are kept | The account entry holds | Use it when |
| --- | --- | --- | --- |
| `Mode.Own(vault)` (default) | In this app's private storage (`pro.aduki.account.tokens`), sealed with `vault` (the Keystore on a device). | Metadata only, plus an `own.<package>` marker. | Each app has its own session and DPoP key (recommended). |
| `Mode.Shared` | In the `AccountManager` entry only (password = refresh token, auth token `access` = access token), unsealed. | Metadata and the tokens. | Several apps of one publisher share one session through a `TokenStore`. |

A Keystore key belongs to one app, so tokens shared between apps cannot be sealed: in `Mode.Shared` they are protected by the system account database, which only apps the account is made visible to can read. Every read goes to the entry, so after one app renews the next app reads the rotated pair. A private copy that 0.5.x wrote is deleted on the first read or save. All apps of one publisher should use the same mode.

| Call | Does |
| --- | --- |
| `save(hex, tokens)` | Stores the session where the mode keeps it. `false` when there is no entry for `hex`. |
| `read(hex): Stored` | `Stored.Found(tokens, saved)`, `Stored.Missing` (signed out in this app) or `Stored.Sealed` (stored, but not this app's to use: sealed by another app, a Keystore key that is gone, or unsealed tokens another app left). Nothing is read from anywhere the mode does not keep tokens. |
| `tokens(hex)` | The tokens of `Stored.Found`, else `null`. |
| `migrate(hex)` | Opt-in, `Mode.Own` only: moves tokens this app wrote into the entry with 0.5.x (sealed with its own vault) into its private store and removes them from the entry. Another app's tokens are never adopted. |
| `forget(hex)` | Ends this app's session and keeps the entry (in `Mode.Shared` that is every app's session). |
| `fresh(hex)`, `needsSignIn(hex)`, `invalidate(hex)` | As before, against the mode's copy. |

### Sharing accounts across companion apps

On Android 8+ (API 26+), accounts added by one app are invisible to other apps until `Accounts.share(packages)` grants visibility (`AccountManager.setAccountVisibility`). `share` hands over no tokens.

```kotlin
accounts.share(listOf("pro.aduki.calendar", "pro.aduki.contacts"))
```

`Visibility` is the seam for tests (Robolectric cannot run `setAccountVisibility`): pass `Accounts(context, mode, visibility = object : Visibility { … })`. `Visibility.system(manager)` is the real one; it ignores a refused platform call and remembers what it set.

### Handoffs: a companion app opens its own session

A signed-in app (A) lets a companion app (B) of the same publisher open **its own** session of the account, bound to B's own DPoP key, without a password or second factor. Only a single-use code crosses apps; no token does.

1. A asks Aduki ID for a handoff for B's package and leaves it in the account entry:
   ```kotlin
   // A, signed in, Mode.Own
   for (peer in listOf("pro.aduki.calendar", "pro.aduki.contacts")) {
       if (!accounts.holds(hex, peer)) accounts.offer(hex, peer, aduki.handoff(peer))
   }
   ```
   `Aduki.handoff(target, ttl = 3600)` is `POST /v1/handoffs {target, ttl}` with an `id` audience token (the SDK gets it by rotating the refresh token under its renewal lock and saves the rotated token). A handoff works once, lives `ttl` seconds (60 to 86400), is replaced by a newer one for the same target, and dies with A's session.
2. B, finding the account in `accounts.list()` with nothing stored (`read(hex) == Stored.Missing`), claims and redeems it:
   ```kotlin
   // B, Mode.Own
   val code = accounts.claim(hex)                 // removes it from the entry; null when none or expired
   if (code != null) {
       val aduki = Aduki.redeem(code, dpop = AndroidKey.open(), tokenStore = store, device = "Calendar on Pixel 9")
       accounts.save(hex, aduki.session.tokens.value!!)
   }
   ```
   `Aduki.redeem` is `POST /v1/handoffs/redeem {handoff, audience, device}` with B's DPoP proof: Aduki ID opens a new session of the same account at tier 1. A used, expired or orphaned code is `AdukiException.Unauthorized`; B then signs in normally.
3. `accounts.withdraw(hex, target)` removes a handoff that was not claimed. `accounts.holds(hex, target)` tells whether `target` saved its own session (A offers only to apps that did not).

The handoff sits in `AccountManager` user data, readable by the apps `share` named, until claimed; it is a bearer code for one new session, so keep `ttl` short when B is expected to open soon.

### Device unlock

Once an app registers a device credential (`accounts.registerDevice(client, identity, bearer, device, code, center = true, key = AndroidKey.open(AndroidKey.DEVICE))`, which needs an `id` audience `bearer` and a fresh authenticator `code`: Aduki ID accepts each code step once, so not the one used to sign in), it can reopen sessions with the device key and no password: `accounts.unlockWithDevice(http, identity)` returns the hexes whose sessions it stored. Unlocked sessions are not DPoP-bound. Credentials are trusted for 60 days; a lapsed or revoked one makes `unlockWithDevice` clear it locally and throw `AdukiException.Network(code = 404)`. `accounts.revokeDevice(http, identity, bearer)` revokes it (`false` when there was none or it was already gone).

### Authenticator

One app declares `AdukiAuthenticatorService` to own the account type `pro.aduki.account`. When another app asks `AccountManager` for the `access` auth token, `AdukiAuthenticator` returns the stored one while it is fresh, else calls `Authenticator.refresher(entry, refresh)` on a binder thread (not the main thread) and stores what it returns. `refresher` must make one renewal call to Aduki ID (with the app's DPoP key when the session is bound) and return the new `Tokens`, or `null` when the session ended; `null` makes the request fail with "sign in again".

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

## Passkeys & Credential Manager

`PasskeyFlow(client, identity, CredentialManagerAuthenticator(activity))` has `register`, `stepUp` and `signIn`. A dismissed sheet is `PasskeyFailure.Cancelled` (nothing is sent), a missing passkey `NoPasskey`, anything else `Platform`.

`pro.aduki:android` declares transitive `api` dependencies on `androidx.credentials:credentials` and `androidx.credentials:credentials-play-services-auth`, providing seamless backward compatibility on Android 13 and below.

### Digital Asset Links

Android Credential Manager verifies ownership between applications and web origins using Digital Asset Links (`assetlinks.json`).

The library manifest automatically declares the `asset_statements` meta-data:

```xml
<meta-data
    android:name="asset_statements"
    android:resource="@string/asset_statements" />
```

By default, `@string/asset_statements` points to the hosted federation file:

```json
[{"include": "https://mail.aduki.pro/.well-known/assetlinks.json"}]
```

For self-hosted deployments or companion origins, override `@string/asset_statements` in the app's `res/values/strings.xml`:

```xml
<resources>
    <string name="asset_statements" translatable="false">
        [
          {\"include\": \"https://mail.aduki.pro/.well-known/assetlinks.json\"},
          {\"include\": \"https://mail.example.org/.well-known/assetlinks.json\"}
        ]
    </string>
</resources>
```

#### Keystore Fingerprint Retrieval

To associate your app in `assetlinks.json`, extract the SHA-256 certificate fingerprint:

```bash
keytool -list -v -keystore <keystore-path> -alias <key-alias>
```

Copy the `SHA256:` fingerprint in colon-separated hex format into the target server's `assetlinks.json` configuration.

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
