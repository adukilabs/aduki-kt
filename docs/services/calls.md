# Calls and meetings

`Aduki.calls` (`pro.aduki.sdk.Calls`) is Aduki Calls for the signed-in account:
start a call, make a meeting and share its link or code, list, join and restart
meetings, the call log, and the devices that ring. It covers the HTTP side; the
media (microphone, speaker, the call itself) is Aduki Calls' own SDK, which signs
in through this client ([below](#signing-in-the-media-sdk)).

## Credentials

Aduki Calls (`https://calls.aduki.pro/v1`) accepts only DPoP-bound access tokens
of audience `audio`. Build the client with a device key and an Aduki ID session:

```kotlin
val client = Aduki.builder().dpop(key).tokens(store).build()
```

The `audio` token comes from the client's own refresh token, renewed under the
same lock as the mail token; the rotated refresh token is saved before it is used
again. The Calls request itself runs outside that lock. Without a DPoP key the
first call throws `IllegalStateException`; without a session,
`AdukiException.Unauthorized`. `Builder.calls(base)` points at another Calls server.

Every call is `suspend`, runs on `Dispatchers.IO` and fails with `AdukiException`
(`Api` with the server's `kind`, such as `plan.limit`, `state.conflict` or
`rate.limited`), or `IllegalArgumentException` for an argument the server would
refuse (an id that is not sixteen hex digits, a title over 120 characters).

## Meetings

```kotlin
val meeting = client.calls.meeting(title = "Weekly review", lobby = "guests")
share(meeting.link)                    // https://calls.aduki.pro/call/abc-defg-hij
meeting.code                           // abc-defg-hij

when (val entry = client.calls.join(pasted)) {   // a code in any form, or a link
    is CallEntry.Ticket -> media.join(entry.ticket.ticket)
    is CallEntry.Waiting -> showLobby()          // join again after the socket's `admitted`
}

val ended = client.calls.status("abc-defg-hij")  // exists, open, ended, organiser, title
if (ended.ended && ended.organiser) client.calls.restart("abc-defg-hij")
```

| Call | Route |
| --- | --- |
| `meeting(title, lobby)` | `POST /calls {kind: meeting}`: a letter code and a link; `lobby` is `off`, `guests` or `everyone` |
| `meetings(limit, after)`, `list(limit, after, missed, kind, with)` | `GET /calls`: newest first, filtered |
| `get(call)` | `GET /calls/{hex or code}` |
| `join(call)` | `POST /calls/{hex or code}/tokens`: a ticket, or waiting in the lobby |
| `status(code)` | `GET /meetings/{code}`: nothing about who is in it |
| `restart(call)` | `POST /calls/{hex}/reopen`: open again for a day after it was closed or expired |
| `end(call)` | `DELETE /calls/{hex}`: for everyone (its host) |
| `change(call, locked, title, lobby, unmute)` | `PATCH /calls/{hex}` (its host) |

Codes are ten letters in groups of three, four and three; calls made before codes
keep their sixteen-hex id, and both work everywhere. `Calls.code(text)` reads a
code from what was typed or pasted (any case, spaces or dashes, a whole link) or
returns null; `Calls.link(code)` builds the link; `Calls.status(code)` works
signed out (a join screen before sign-in).

## Calls and rings

| Call | Route |
| --- | --- |
| `start(to)` | `POST /calls`: rings up to 20 accounts |
| `invite(call, to)` | `POST /calls/{hex}/invites`: a direct call becomes a group call |
| `ringing()` | `GET /calls?state=ringing`: what to show after a ring's push |
| `answer(call, accept)` | `POST /calls/{hex}/answers`: a ticket when accepted, null when declined; `409 state.conflict` once answered or missed |
| `leave(call)` | `DELETE /calls/{hex}/participants/me`: on every device |
| `missed()`, `seenMissed()` | the missed-calls badge, alike on every device |
| `hide(call)`, `clearLog()` | the account's own log |
| `limits()` | `GET /limits`: the package's `audio.*` keys and this month's use; a new call past them is `403 plan.limit` |
| `relays()` | `POST /relays`: TURN servers, or none |
| `register(token, label)`, `devices()`, `edit(device, token, label)`, `remove(device)` | `/devices`: the FCM token a device rings on (null: it rings nowhere) |

A ring is a high-priority FCM data message with the keys `v`, `kind` (`ring`),
`call`, `caller`, `name` and `until` (Unix seconds). The SDK has no Firebase
dependency: the app's `FirebaseMessagingService` shows the incoming call and
answers with `answer(call, true)`. Register again with `edit(device, token = new)`
when Firebase rotates the token.

## Meetings on calendar events

`client.scheduling.addMeeting(eventHex)` books a Calls meeting for an event: it
moves with the event and is cancelled with it. `meeting(eventHex)` reads it (null
when the event never had one) and `removeMeeting(eventHex)` cancels it. The
`EventMeeting` has the members' `link` and a `guest` link for attendees outside
the workspace; its `state` is `wanted` while Calls books it, then `booked`.

## Signing in the media SDK

Aduki Calls' media SDK never holds a refresh token or a key. It asks for an
access token and a DPoP proof per request through its `Identity` interface:

```kotlin
val identity = object : pro.aduki.calls.Identity {
    override fun token(): String = client.calls.token()
    override fun proof(method: String, url: String, token: String): String = client.calls.proof(method, url, token)
}
```

Both are blocking; the media SDK calls them off the main thread. `proof` also
signs the signalling socket (`GET wss://.../v1/signal`).

## The HTTP client

`client.callsApi` is the blocking `pro.aduki.net.http.Calls` behind the facade. It
can also be built by hand with an `Id` (`Calls(http, id, dpop)`) or any `Bearer`
token source; `http` must carry the `Dpop` interceptor and no credentials. Creating
calls and devices sends an `Idempotency-Key` (a new UUID unless one is passed).
