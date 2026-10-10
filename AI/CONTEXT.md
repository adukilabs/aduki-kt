# Context: glossary, specs, endpoints, env vars

## Glossary

| Term | Meaning |
|---|---|
| Aduki ID | the identity service (`id` repo): sign-in, tokens, Account Center, OIDC provider |
| audience | the service a token is for (`mail`, `id`, later `space`); one sign-in, one access token per audience |
| session / hex | the Aduki ID session id (`Tokens.session`), used for sign-out |
| access / refresh token | 10-minute EdDSA JWT; single-use rotating token (reuse revokes the session) |
| rights push | `event: rights` on the mail JMAP EventSource when an account's rights change; client renews its token (ADK-AUTH-003 section 7.4) |
| `auth.stale` | server refusal for a token minted before a rights change; renewing fixes it |
| DPoP | RFC 9449 proof-of-possession; tokens bound to a device key (`cnf.jkt`) |
| Account Center | cross-account switcher at Aduki ID (`Center` client) |
| outbox | local journal of changes waiting to be sent (`Outbox` entity, `Manager`, `Worker`) |
| MODSEQ / CONDSTORE | change counter for incremental mail sync via `GET /v1/user/mail/changes` |
| hex | server identifier string used for messages, mailboxes, contacts |
| K0 to K7 | work items in `guide/plan.md` (K0 Maven namespace, K1 rename, K2 Id client, K3 rights stream, K4 authenticator, K5 DPoP, K6 passkeys, K7 OIDC) |

## Specs (registry in `aduki/guide/spec.md`)

| ADK id | Topic | Owner repo |
|---|---|---|
| ADK-KT-001 / 002 / 003 | this repo's guide index, progress, plan | aduki-kt (`guide/README.md`, `guide/progress.md`, `guide/plan.md`) |
| ADK-META-001 | registry, platform plan and phases | aduki |
| ADK-AUTH-001 | sign-in, tokens, device key, DPoP, OIDC | id |
| ADK-AUTH-002 | Account Center, link, switcher, unlock | id |
| ADK-AUTH-003 | permission bits, `rights` push | id |
| ADK-NET-001 | the REST envelope | aduki |
| ADK-SPACE-* | Space (no client yet; Space accepts DPoP-bound tokens only) | space |

A change of SDK behaviour changes the owning spec in the same pull request (the spec lives in another repo: coordinate).

## Endpoints and hosts

| Use | Value |
|---|---|
| Aduki ID | `https://id.aduki.pro/v1` (`Endpoints.ID`); routes used: `POST /sessions`, `POST /tokens`, `DELETE /sessions/{hex}`, `/links`, `/center`, `/credentials`, OIDC discovery at `/.well-known/openid-configuration` on the origin |
| Mail REST | `https://mail.aduki.pro/v1` (`Endpoints.REST`) |
| Mail event stream | `https://mail.aduki.pro/jmap/eventsource` |
| gRPC | `grpc.aduki.pro:443` |
| Docs | `docs.aduki.pro/kt` |

No local service ports: the SDK is a client; tests use MockWebServer on ephemeral ports.

## Environment variable names (never values)

| Name | Used by |
|---|---|
| `ADUKI_LIVE_URL`, `ADUKI_LIVE_EMAIL`, `ADUKI_LIVE_PASSWORD`, `ADUKI_LIVE_ID`, `ADUKI_LIVE_CODE`, `ADUKI_LIVE_BACKUP` | `net` live test (`./gradlew :net:liveTest`) |
| `ADUKI_LIVE=true` (or property `aduki.live`), `ADUKI_KEY`, `ADUKI_ENDPOINT`, `ADUKI_GRPC_HOST` | `sdk` live test (API-key based; needs `ADUKI_KEY`, otherwise its built-in placeholder key fails) |
| `SIGNING_KEY`, `SIGNING_PASSWORD` | PGP signing for publishing (also Gradle properties `signing.key`, `signing.password`) |
| `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` | Maven Central upload in `publish.yml` |
| `GITHUB_ACTOR`, `GITHUB_TOKEN` (Gradle properties `gpr.user`, `gpr.key`) | GitHub Packages |

## Service dependencies

Unit tests need none. Live tests need a running Aduki ID and Aduki Mail with a test account that has an authenticator (second factor is mandatory at Aduki ID).
