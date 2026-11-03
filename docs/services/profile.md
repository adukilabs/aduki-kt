# Profile

The signed-in account's profile: name, address, photo, tenant, role, plan and
storage. It is read from the mail server, the name is changed at Aduki ID and
the pooled storage comes from Aduki Store. All of it is on `Aduki`.

```kotlin
suspend fun profile(): Profile?
suspend fun rename(name: String): Boolean
suspend fun avatar(hex: String? = null, etag: String? = null): AvatarImage?
suspend fun uploadAvatar(bytes: ByteArray, type: String): Profile?
suspend fun removeAvatar(): Boolean
suspend fun avatars(emails: Collection<String>): Map<String, Avatar>
```

Reads (`profile`, `avatar`, `avatars`) never throw except on cancellation:
they return null or an empty map and log the failure by type. Writes
(`rename`, `uploadAvatar`, `removeAvatar`) throw only `AdukiException` (and
`CancellationException`). After a successful write the cached identity
(`session.identity`) is cleared, so the next `me()` reads it again.

## Reading

```kotlin
val profile = client.profile() ?: return
profile.name            // "Ada Lovelace"
profile.kind            // Profile.Kind.PERSONAL or MEMBER
profile.tenant?.name    // the tenant of a member, null for a personal account
profile.role.owner      // a tenant owner
profile.plan?.code      // e.g. "free"
profile.storage.used    // bytes
profile.storage.limit   // bytes, null when unlimited
profile.editable        // false when the tenant locks profiles
```

`profile()` reads `GET /user/profile`. When the client has an Aduki ID session
(a refresh token), it also reads `GET /v1/quota` at Aduki Store (audience
`store`) and puts that pooled total (drive, mail and chat) in `storage`, with
`storage.pooled = true`. Without a drive (404), with an API key or a bare
token, or when Store cannot be reached, `storage` is the mail counter and
`pooled` is false. Store requires DPoP-bound tokens, so the pooled total needs
a client built with `dpop(key)`. The Store base is set with
`Builder.drive(base)` (default `https://store.aduki.pro/v1`).

`Identity` (from `me()`) also carries the account's `name` now.

## Name

```kotlin
try {
    client.rename("Ada King")
} catch (e: AdukiException.Api) {
    if (e.locked) showLocked() else showError(e.kind)
}
```

`rename` sends `PATCH /v1/accounts/{hex}` `{name}` to Aduki ID with an `id`
audience token. The name is trimmed and must be 1 to 80 characters; anything
else fails with `Api` `input.invalid` (400) before a request is made. It
returns false when the client has no Aduki ID session (an API key or a bare
token). The mail server's profile shows the new name shortly after, once it
has followed Aduki ID's `id.account.updated` event.

## Photo

```kotlin
val image = client.avatar(etag = cached?.etag)   // null: unchanged, none, or failed
val updated = client.uploadAvatar(jpeg, "image/jpeg")
client.removeAvatar()
```

- `uploadAvatar` sends the raw bytes (`PUT /user/avatar`): JPEG, PNG or WebP,
  at most 1 MiB (`Profiles.LIMIT`) and 1024 by 1024 pixels. Another media type
  fails with `Api` 415 before a request is made; the server answers 413 for a
  photo that is too large.
- `avatar()` reads the own photo (`GET /user/avatar`), `avatar(hex)` that of
  the account `hex` (`GET /user/avatar/{hex}`). With `etag` the read is
  conditional (`If-None-Match`). `AvatarImage` holds `bytes`, `etag` (the
  photo hex) and `type`. Null means unchanged (304), none or not visible (404),
  or a failure: compare `Profile.avatar?.hex` with the cached `etag` to know
  whether a photo exists.
- Members of a tenant see each other's photos. A personal account's photo is
  visible to every signed-in Aduki account.

`avatars(emails)` looks up photos by address (`POST /user/lookup/avatars`, in
batches of 50). The map is keyed by address as the server wrote it; each
`Avatar` has the photo `hex`, `updated` (epoch ms) and the `account` hex to
pass to `avatar(hex)`. Addresses without a visible photo are absent.

## Locked profiles

A tenant can lock profiles (Aduki ID tenant policy `profile.locked`). Members
who are not owners then cannot rename themselves or change their photo:
`profile.editable` is false and the writes fail with `AdukiException.Api`
status 403 kind `locked`, for which `e.locked` is true. Owners and personal
accounts are never locked.

## Transport

`pro.aduki.net.http.Profiles` has the blocking calls behind the facade:
`get`, `avatar`, `upload`, `remove`, `lookup` and `quota`. `Id.rename(hex, name)`
renames at Aduki ID.
