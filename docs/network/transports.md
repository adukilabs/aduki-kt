# Network transports

## HTTP (OkHttp)

All typed clients (`Id`, `Login`, `Center`, `Mail`, `Scheduling`, `Oidc`) use
OkHttp 4.12.0. `pro.aduki.net.http.Client.create(key, timeout, pinner, apiKey)` builds the base
client (an idle connection pool of 8 for 5 minutes, the given timeout on connect, read and write, retry on connection failure); `Aduki` adds the `Authorization` header, the `401`
renewal authenticator, TLS pinning (see [TLS](../security/tls.md)) and, when
configured, the [DPoP](../auth/dpop.md) interceptor.

The scheme of the `Authorization` header follows the credential:

| Credential | Header |
| :--- | :--- |
| supplied through `Builder.key(...)` | `Key <key>` |
| bound access token (with DPoP configured) | `DPoP <token>` plus a `DPoP` proof header |
| anything else (`token(...)`, sessions) | `Bearer <token>` |

The SDK never guesses from the token prefix; the server accepts either scheme
for both kinds of credential.

Mail's `/v1` responses use one envelope, `{"success": true, "data": ...}` or
`{"success": false, "error": {"status", "kind", "message"}}`; the clients read
it for you and map failures to [`AdukiException`](../reference/errors.md).
Sends carry an `Idempotency-Key` header (see [Outbox](../services/outbox.md)).

Timeouts come from `Builder.timeout(seconds)` (default 15) and apply to
connect, read and write.

## Mail event stream

`Events` reads `GET {mail host}/jmap/eventsource` (Server-Sent Events) for the
`rights` event. See [Token lifecycle](../auth/tokens.md).

The SDK has no gRPC transport: the `grpc.` host was dropped from the platform
(D-HOST-5) and the `Channel` and `Credentials` classes were removed (a breaking change in this 0.x SDK; see the CHANGELOG).
