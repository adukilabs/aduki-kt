# Network transports

## HTTP (OkHttp)

All typed clients (`Id`, `Login`, `Center`, `Mail`, `Scheduling`, `Oidc`) use
OkHttp 4.12.0. `pro.aduki.net.http.Client.create(key, timeout, pinner)` builds the base
client (an idle connection pool of 8 for 5 minutes, the given timeout on connect, read and write, retry on connection failure); `Aduki` adds the `Authorization` header, the `401`
renewal authenticator, TLS pinning (see [TLS](../security/tls.md)) and, when
configured, the [DPoP](../auth/dpop.md) interceptor.

The scheme of the `Authorization` header follows the credential:

| Credential | Header |
| :--- | :--- |
| starts with `hm_` or `key_` | `Key <key>` |
| bound access token (with DPoP configured) | `DPoP <token>` plus a `DPoP` proof header |
| anything else | `Bearer <token>` |

Mail's `/v1` responses use one envelope, `{"success": true, "data": ...}` or
`{"success": false, "error": {"status", "kind", "message"}}`; the clients read
it for you and map failures to [`AdukiException`](../reference/errors.md).
Sends carry an `Idempotency-Key` header (see [Outbox](../services/outbox.md)).

Timeouts come from `Builder.timeout(seconds)` (default 15) and apply to
connect, read and write.

## Mail event stream

`Events` reads `GET {mail host}/jmap/eventsource` (Server-Sent Events) for the
`rights` event. See [Token lifecycle](../auth/tokens.md).

## gRPC

`pro.aduki.net.grpc.Channel.create(host, port = 443)` builds a TLS
`ManagedChannel` on `grpc-okhttp` (keep-alive 30 s, timeout 10 s, no
keep-alive without calls). `pro.aduki.net.grpc.Credentials(key)` is a `CallCredentials` that adds
`authorization: Key <key>`, for API-key clients.

The `Aduki` facade does not use gRPC itself; the channel factory is there for
apps that call gRPC services directly. Default host: `grpc.aduki.pro:443`.
