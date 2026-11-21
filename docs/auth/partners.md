# Partner apps: Aduki accounts on the device

First-class partner apps are apps by another publisher (another signing key) that offer "Continue with Aduki". They can list the Aduki accounts signed in on the device for a "Continue as …" row, and sign in with the chosen account through their own OAuth flow. They never receive a token.

## In the Aduki apps (the host)

The partner list is `Partners.packages` (default `Partners.DEFAULT`, `com.lavernai.triviaconnect`). Set it in `Application.onCreate` before anyone signs in; an empty list turns partner sharing off.

```kotlin
Partners.packages = Partners.DEFAULT + "com.example.partner"
```

`Accounts.share(packages)` and every new `AccountDirectory` entry grant `VISIBILITY_VISIBLE` to the given sibling packages **and** to the partners. Call `share` on sign-in and on every account refresh, so entries written by an older SDK are granted too.

## In the partner app

Depend on `pro.aduki:android` and call the read API off the main thread. The partner does not declare the authenticator.

```kotlin
val accounts: List<PartnerAccount> = withContext(Dispatchers.IO) { PartnerAccounts.list(context) }
accounts.forEach { println("${it.label} ${it.handle} ${it.hex}") }
```

| Field | What it is |
| --- | --- |
| `hex` | The account's hex: send it as `login_hint` to your authorization request. Null when no Aduki app answered (then use `handle`). |
| `handle` | The handle, e.g. `ada@aduki.me` (the entry's name, always there). |
| `name` | The display name, or empty; `label` is the name, else the handle. |
| `personal` | False for a member of an organisation; `tenant` is the organisation's name. |
| `photo` | An opaque photo tag. The image needs an Aduki session, so show initials. |

On an Android 8+ device without an Aduki app that lists the partner, the list is empty.

## What crosses, and what never does

- Android lets an app signed with another key see the entry's name only: `getUserData`, `getPassword` and `peekAuthToken` throw `SecurityException` for it.
- The partner asks the authenticator for the token type `PartnerAccounts.PROFILE` (`pro.aduki.profile`). The authenticator answers with a JSON of the fields above (built from an allow-list), and only to the packages in `Partners.packages` or apps signed like itself.
- The authenticator type is declared with `customTokens`: Android never hands a partner a cached access token, and the authenticator refuses `access` to anyone not signed like itself, whatever `Accounts.Mode`. In `Mode.Shared` the session tokens stay in the entry's password and auth tokens, readable only by the same-signature apps; in `Mode.Own` they are sealed in each app's private storage.
- The device group, session id, center, link state and the per-app handoff slots are never in the profile.
