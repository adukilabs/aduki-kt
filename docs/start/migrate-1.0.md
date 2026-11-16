# Moving to 1.0.0

1.0.0 removes sign-up from the SDK and changes the draft Calls client. Everything
else from 0.8.1 still compiles.

## Sign-up

| 0.8.x | 1.0.0 |
| --- | --- |
| `Aduki.requirements()` | unchanged (the personal domain) |
| `Aduki.signUp(handle, name, password, phone)` | `SignUpTab(back).open(activity)` on Android, or open `Aduki.signUpUrl(handle, back)` |
| `Aduki.verify(hex, password, code)` | the sign-up page does it |
| `Aduki.register(hex, password, code, …)` | the person signs in afterwards with `Aduki.signIn` |
| `Id.create`, `Id.verify`, `Signup.create`, `Signup.verify` | `Id.signUpUrl(handle, back)`, `Signup.url` |
| `Enrolment`, `Verified` | none |

An app's own sign-up screens (address, password, authenticator, backup codes)
go away: the "Create an account" button opens the page. See [Sign-up](../auth/signup.md).

## Calls

The draft `pro.aduki.net.http.Calls` returns models (`Call`, `CallTicket`,
`CallDevice`, `CallLimits`, ...) instead of `JSONObject`s; `capabilities`,
`relays` and `quality` still return JSON. Its default base is
`https://calls.aduki.pro/v1`. Most apps use `Aduki.calls` instead, which signs in
with the client's own session: see [Calls and meetings](../services/calls.md).

## HTTP clients

Every client the SDK builds now shares one OkHttp connection pool and
dispatcher (`pro.aduki.net.http.Client.shared`). Nothing changes for a client
passed in with `Builder.http(client)`.
