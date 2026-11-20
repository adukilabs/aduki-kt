# Chats: the HTTP routes

`Aduki.chatsApi` (`pro.aduki.net.http.Chats`) is the plain HTTP surface of Aduki
Chats for the signed-in account: the space to name, devices, contacts, a group's
sealed name and photo, a thread's calls, and encrypted media blobs.

**It is not a messaging client.** An app cannot list conversations, read a
message, send one, mark one read or show typing with it. Those travel over the
`ae09` protocol (QUIC or WebSocket, CBOR) and every message is MLS-encrypted on
the device. This SDK has neither yet: see [what messaging needs](#what-messaging-needs).

## Credentials

Aduki Chats (`https://chat.aduki.pro`) accepts only DPoP-bound access tokens of
audience `chat`, of a person (no delegation, no client token). Build the client
with a device key and an Aduki ID session:

```kotlin
val client = Aduki.builder().dpop(key).tokens(store).build()
val chats = client.chatsApi
```

Without a DPoP key the first use throws `IllegalStateException`.
`Builder.chats(base)` points at another Chats server. A `401` renews the token
once and the request is sent once more.

Every call **blocks**: run it off the main thread (`withContext(Dispatchers.IO)`).
It fails with `AdukiException` (`Api` with the server's `kind` and `details`,
`Unauthorized`, `Network`, `Protocol`), or `IllegalArgumentException` for an
argument the server would refuse, before any request is sent.

An account that never bound a Chats device is refused `403 forbidden` with
`details.reason = "binding"`. `space()` and `devices()` are the exceptions, so
an app can ask before it binds.

## What it offers

| Call | Route | Notes |
|---|---|---|
| `home()` | `GET /.well-known/ae09/server` | the server's home space, no sign-in |
| `space()` | `GET /_ae09/space` | `tenant`, `kind` (`open` or `org`), `home`, `workspace` |
| `devices()` | `GET /_ae09/devices` | the caller's devices, most recently seen first |
| `renameDevice(device, name)` | `POST /_ae09/devices/name` | 1 to 64 characters |
| `revokeDevice(device)` | `POST /_ae09/devices/revoke` | signs that device out of Chats |
| `contacts(after, limit)`, `allContacts(max)` | `GET /_ae09/contacts` | pages of up to 200; `dm` is the open direct thread |
| `threadInfo(thread)` | `GET /_ae09/threads/meta` | ciphertext; groups only |
| `putThreadInfo(thread, version, sealed)` | `PUT /_ae09/threads/meta` | `version` is the stored one plus one, else `409` |
| `calls(thread)` | `GET /_ae09/calls` | whether calls are offered, and calls in progress |
| `startCall(thread, idempotency)` | `POST /_ae09/calls` | `started` is false when one was in progress |
| `callGroup(call)` | `POST /_ae09/calls/groups` | the key group of a meeting, for someone in it |
| `upload(ciphertext)` | `PUT /_ae09/media` | stored as given: encrypt first |
| `download(token)`, `delete(token)` | `GET`, `DELETE /_ae09/media?token=` | the media token is the whole credential |
| `usage()`, `ownMedia(after)`, `deleteOwnMedia(id)` | `/_ae09/usage`, `/_ae09/media/mine` | the caller's own uploads |

To join a call `startCall` returned, ask Aduki Calls for a ticket
(`Aduki.calls`) and open `signal`.

Not covered: the operator and workspace-admin routes (`/_chats/...`: reports,
legal holds, webhooks, bots), the Aduki Mail bridge, key transparency, backups
and contact discovery.

## What messaging needs

Nothing below is built. It is here so the gap is stated where an app developer
will look for it.

- **MLS.** Every message is encrypted by OpenMLS with the Aduki group profile.
  The Chats repository has that core in Rust (`crates/client`, about 1,300
  lines), built today for the browser as WebAssembly. The Android calls library
  already links the same core natively (`aduki_calls`, through
  `crates/chatkeys` in the voice repository) but only to derive a meeting's
  keys, not to hold conversations. There is no MLS in Kotlin and writing one is
  not an option worth considering.
- **A native library for Android.** The Rust core with a JNI or UniFFI binding,
  cross-compiled with the Android NDK per ABI (`arm64-v8a`, and `armeabi-v7a`
  if old devices matter) and shipped inside the AAR. That makes the Chats part
  of this SDK Android-only, or a second artifact, where today's modules are
  plain JVM.
- **The wire protocol.** `ae09` over WebSocket (HTTP/2) at least: the
  handshake, CBOR envelopes, `thread.list`, `msg.sync`, `mls.group_op`,
  `mls.welcome`, `keypkg.publish`, `msg.ack`, `msg.read`, `thread.typing`, and
  reconnect with replay. The web client's version is about 1,500 lines of
  TypeScript over its MLS engine.
- **Storage on the device.** The MLS group state and the message history,
  encrypted at rest with a Keystore key, plus device identity custody. Losing
  the state loses the history.
- **Everything on top.** Attachments (sealed blobs, which is where `upload`
  and `download` above fit), reactions, edits, read marks, system cards, push
  wake-ups.

Where it builds: Rust is built on the build host only, with the NDK installed
there; the Kotlin side builds as today. Rough size: the Rust binding and the
wire client are several weeks for one person, and a first usable conversation
screen in an app comes after that. It is its own project with its own review,
because it is end-to-end encryption code.
