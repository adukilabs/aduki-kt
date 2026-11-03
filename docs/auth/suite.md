# Suite sign-in and the Accounts Center

Since 0.8.0 the apps of one publisher (the Aduki suite) share **who is signed in**, never a token:

- the **device directory** (`AccountDirectory`): one `AccountManager` entry per account on the device, with its profile, and no secret;
- **per-app sessions**: each app has its own DPoP key and its own refresh token, and only that app rotates it;
- the **broker** (`SuiteBroker`, `SuiteBrokerService`): an app with no session of an account asks a signed-in sibling **on demand** for a single-use code bound to its own key, then redeems it with that key;
- the **Accounts Center** (`AccountsCenter`): add, switch, sign out, remove from the device, unlink.

It needs Aduki ID with handoff `jkt`/`expires_in`, device groups, `GET /v1/me`, link tickets, the rich `GET /v1/center` and DPoP nonces (live since 2026-11-04).

## Renewal rules

The SDK drops tokens only when Aduki ID says the session is over:

| Answer to `POST /v1/tokens` | Exception | Tokens |
| --- | --- | --- |
| `401 auth.reused`, `auth.revoked`, `auth.expired`, `auth.invalid` | `AdukiException.Unauthorized`, `ended == true` | dropped |
| `401 auth.dpop` | `AdukiException.Proof` | kept |
| `401 auth.nonce` (after the one retry) | `AdukiException.Proof` | kept |
| `401 auth.factor`, `auth.stale` | `AdukiException.Unauthorized`, `ended == false` | kept |
| `5xx`, `503 auth.unavailable`, network | `AdukiException.Network` / `IOException` | kept |

More guarantees:

- **DPoP nonce.** Every answer to a DPoP request carries `DPoP-Nonce`; the `Dpop` interceptor keeps it per origin (shared by every `Dpop` of the process) and puts it in the next proofs. A phone with a wrong clock is therefore not refused. On `401 auth.nonce` (or OAuth `400 use_dpop_nonce`) the request is sent once more with the nonce. No other failure is retried, so a refresh refused as reused is never sent twice.
- **Synchronous saves.** Rotated tokens go to `TokenStore.saveNow` (new; it defaults to `save`) before they are used. `Accounts.save` (`Mode.Own`) writes with `commit()`. A store backed by `SharedPreferences` must not `apply()` a rotated refresh token.
- **One rotation per session in a process.** Renewals are serialised by a process-wide lock per Aduki ID session, across `Aduki` instances: a sign-in client and a redeem client sharing one `TokenStore` rotate once.
- **The key is read lazily.** `Aduki.Builder.dpop { AndroidKey.open() }` reads the key when the first proof is signed and again while that fails, instead of once at build.

## Device directory

```kotlin
val directory = AccountDirectory(context, packages = SUITE)   // SUITE: the three package names
directory.upsert(aduki.account()!!)                            // after any sign-in: GET /v1/me
directory.list()                                               // personal first, then by tenant name
directory.changes()                                            // Flow, also fed by other apps' entries
```

Entries keep the handle as the account name (shown in Settings > Accounts). The user data is at version 2:

| Field | Content |
| --- | --- |
| identity | `v=2`, the real `hex`, `handle`, `name` |
| kind and tenant | `kind`, tenant `hex`, `tenantName`, `role` and `owner` |
| profile | `photo` (the tag from `/v1/me`), `center`, `unlock`, `links` |
| this device | `group` (the account's device group here) |
| link state | `linked`, `removedBy` (the tenant's name) and `refusal` (why it is not linked) |

Each app also keeps a private mirror. When entries vanish (the system purges them when the app owning the authenticator is uninstalled), use `orphans()` to list them, check with Aduki ID, then call `restore(hex)` or `forget(hex)`. An entry missing on its own does not mean the account signed out.

`reconcile(view, current)` merges `GET /v1/center` and never drops an account. Accounts in the center become linked. Accounts it no longer lists are kept as device-only (`linked = false`, `refusal = Unlinked`), with `removedBy` set to the tenant's name when a tenant removed a member. The deprecated `Accounts.reconcile` and `Accounts.refresh` still remove entries; do not use them with the directory.

### Migrating email-keyed entries

Suites before 0.8.0 wrote entries with `hex` set to the email. With a live session, call:

```kotlin
directory.migrate(aduki.account()!!)?.let { (email, hex) ->
    // re-key what the app stores per account: the active account, contacts.phonebook, ...
}
```

The entry is updated in place, so no app signs out. This app's `Mode.Own` tokens under the email move to the hex (`Accounts.rekey` does the same on its own). `needsMigration()` tells whether an old entry is still there. The old marks `own.*`, `suite.session.*` and `handoff.*` are left for older siblings.

## Broker

Provider side: subclass once and declare it in the manifest.

```kotlin
class Broker : SuiteBrokerService() {
    override val packages = SUITE
    override suspend fun hasSession(hex: String) = sessions.client(hex) != null
    override suspend fun handoff(hex: String, target: String, jkt: String) =
        sessions.client(hex)?.handoff(target, Handoff.BROKER_TTL, jkt)   // ttl 120, bound to the caller's key
    override suspend fun signedOut(group: String) { /* end this app's session of that group */ }
    override suspend fun activate(hex: String) { /* "use in all apps": switch on resume */ }
}
```

```xml
<permission android:name="pro.aduki.permission.ACCOUNT_ACCESS" android:protectionLevel="signature" />
<uses-permission android:name="pro.aduki.permission.ACCOUNT_ACCESS" />
<service android:name=".Broker" android:exported="true"
    android:permission="pro.aduki.permission.ACCOUNT_ACCESS">
    <intent-filter><action android:name="pro.aduki.action.ACCOUNT_BROKER" /></intent-filter>
</service>
```

Every call goes through `Callers`. The calling uid must belong to a package in `packages`, and `PackageManager.checkSignatures` must report `SIGNATURE_MATCH` with this app. There is no debug bypass: a debug-signed APK next to a Play-signed one is refused.

Caller side:

```kotlin
val broker = SuiteBroker(context, SUITE)
when (val pull = broker.open(hex, key) { code -> Aduki.exchange(code, dpop = key, tokenStore = store, device = label).client }) {
    is SuiteBroker.Pull.Opened -> use(pull.value)
    is SuiteBroker.Pull.NeedsPassword -> askPassword(hex, pull.reason)   // NoSibling, SignedOut, Offline, Refused
}
```

`open` tries each installed sibling that is signed like this app. A sibling that holds no session, or a code that turns out spent, moves on to the next sibling. After a network failure the whole round is tried once more after a short pause. Only then does it return `NeedsPassword` with the reason. `providers(hex)` pings the siblings first, so a "Continue as" sheet can decide its primary button before the person taps.

The push path (`Accounts.offer`, `claim`, `offered`, `withdraw`, `holds`) is deprecated and kept one release for older siblings. To take a code an old sibling left, call `pending(hex)`, redeem it, then `settle(hex, code)`. Unlike `claim`, this no longer deletes the code before redeeming it, and it no longer compares the code's expiry with the phone's clock.

## Accounts Center

```kotlin
val center = AccountsCenter(context, directory, sessions, broker)   // sessions: this app's client per account
center.accounts                                     // StateFlow<List<CenterAccount>>; center.watch(scope) follows other apps
center.refresh()                                    // GET /v1/me + GET /v1/center, merged (never drops)

// Add: sign the other account in here with link = true, then link it to the active one.
val opened = Aduki.signIn(handle, password, code, dpop = key, tokenStore = storeFor(handle), link = true)
when (val added = center.add(activeClient, opened)) {
    is AccountsCenter.AddResult.Linked -> …
    is AccountsCenter.AddResult.DeviceOnly -> show(added.reason)   // TenantDenies (403), SecondPersonal (409), NotAsked, Failed
}

center.switch(hex)                   // this app only; switch(hex, allApps = true) asks the siblings too
center.signOut(hex)                  // this app's session of hex; the row stays
center.removeFromDevice(hex)         // its device group ends; the entry goes for every app; stays linked
center.unlink(hex, code)             // DELETE /v1/links (fresh TOTP); stays on the device, device-only
center.signOutDevice(hex)            // every app on this device for hex; signOutDevice() for every account
center.profile(hex)                  // GET /v1/me, stored in the directory
```

On the client:

| Call | Does |
| --- | --- |
| `Aduki.account()` | `GET /v1/me`, cached |
| `center()` | `GET /v1/center`; `center` is null for a lone account |
| `link(ticket)` | `POST /v1/links {ticket}` |
| `unlink(code)` | `DELETE /v1/links` |
| `endDevice()` | `DELETE /v1/sessions?group=`, then forgets the tokens |
| `removeFromDevice(account, group)` | `DELETE /v1/sessions?group=&account=` |
| `handoff(target, ttl, jkt)` | `POST /v1/handoffs`, bound to the receiver's key |

`Aduki.signIn` and `Aduki.exchange` return `Opened(client, group, ticket)`.

`SwitcherState` and `Linked` are deprecated but still work. `SwitcherState.of(center.accounts.value)` maps the new rows into them.
