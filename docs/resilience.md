# Failure handling

What the SDK throws when the network or the server misbehaves, and how its
background work fails without ending the app. The audit below was made on
`main` at `9f4242a` (0.6.2 plus #128). Line numbers in **Before** refer to that
commit. The fixes are on `fix/network-resilience`.

## The contract

A public `suspend` function or `Flow` of the `sdk` module (`Aduki`, `Mail`,
`Contacts`, `Groups`, `Sync`, `Scheduling`, `Settings`) fails only with:

| Exception | Meaning |
| :--- | :--- |
| `CancellationException` | the calling coroutine was cancelled |
| `AdukiException` | a refusal, an HTTP failure (`Network.code`, `Network.retryAfter`), a malformed answer (`Protocol`), a local database failure (`Storage`), ... |
| `IOException` | a transport failure that was not wrapped: offline, DNS, TLS, a timeout, a stream cut short while it was read |
| `IllegalArgumentException`, `IllegalStateException` | the app called wrongly: an invalid argument (`Card.validate`, `Draft.validate`, `require`), no local store |

Every such call runs on `Dispatchers.IO`, so none touches the network on the
caller's thread. Anything else (`JSONException`, `NoSuchElementException`,
`ClassCastException`, `NumberFormatException`, `DateTimeException`, an
ObjectBox exception, ...) is wrapped by `Faults.normal`
(`core/.../errors/faults.kt`) in `AdukiException.Protocol` (or `Storage` for
ObjectBox), with the original as the `cause`. `Error`s are not wrapped.

**IOException is kept, not wrapped.** The typed REST and JMAP clients already
wrap a failed `execute()` in `AdukiException.Network` (with the
`IOException` as the cause), but reading a body can still fail with a raw
`IOException`, and Aduki ID calls (`Login`, `Signup`, `Id`) throw it as is.
The suite apps catch `IOException` and `AdukiException` around SDK calls
(over 200 `catch (e: IOException)` sites), so both stay valid; wrapping every
`IOException` would move offline errors into the `AdukiException` branch of
code that handles them separately. A later minor release can wrap them as
`Network` once the apps treat both alike.

**IllegalArgumentException and IllegalStateException are kept.** The apps
use them to tell an invalid input or a missing store apart (for example the
contacts editor shows "not valid" for `IllegalArgumentException`). Only
`NumberFormatException`, which comes from numbers in server text, is wrapped.

Background work never throws to the thread's uncaught exception handler. The
outbox flush on `resume()`, the rights stream (`watchRights`) and the store
observers behind the `observe` flows run in the client's scope, whose
`CoroutineExceptionHandler` records the failure in `Aduki.fault`
(`StateFlow<Throwable?>`, normalised as above) and logs it.

Logging: the SDK had no logger. `Faults.report` writes one line to
`java.util.logging` (`pro.aduki`, level WARNING; logcat on Android) with the
place and the exception class only, never the message, which can hold server
text or addresses.

## Findings, by crash likelihood

Each finding names what could be thrown, where it went, and what changed.

### 1. Background coroutines without an exception handler

**Before.** The client scope was `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
(`sdk/.../client.kt:78`) with no handler.
- `lifecycle.listen` launched `sync.flush()` on every `resume()`
  (`client.kt:364`). `Worker.drain` reads the store (`manager.pending()`), and
  its `Rejected` branch called the app's `rejected` callback without a catch
  (`sync/.../outbox/worker.kt:70`). Any exception there went to the default
  uncaught handler: on Android, an app crash on returning to the foreground.
- `watchRights` launched `events.run()` (`client.kt:440`); `Events.run` catches
  `Exception` (`net/.../events.kt:45`), so only an `Error` could escape.
- `MailRepo`, `ContactRepo` and `AppointmentRepo` built by the client run
  `stateIn(scope, ...)` (`state/.../repository/mail.kt:123` and on). A failing
  store query (a sealed column that no longer opens, a closed store) was
  thrown in that scope: a crash.

**Fixed.** The scope gets a `CoroutineExceptionHandler`; the flush and the
rights stream also catch and report. Failures land in `Aduki.fault`. Test:
`sdk ResilienceTest.aFailingBackgroundFlushIsReportedNotThrown`,
`theRightsStreamSurvivesRefusalsAndStops` (both assert that the default
handler saw nothing).

### 2. Network calls on the caller's thread

**Before.** These `suspend` functions ran blocking OkHttp calls on whatever
thread called them. From `viewModelScope` (main thread) that is
`NetworkOnMainThreadException`, a `RuntimeException` the apps do not catch:
- `Aduki.login` (`client.kt:742`), `requirements` (`:808`), `signUp`,
  `verify`, `register` (`:863`), `totp` (`:392`), `refresh` (`:402`),
  `rights` (`:413`), `logout` (`:458`, inside `synchronized`).
- `Aduki.me()` (`:374`): failed silently (caught) and returned null.
- `Sync.mailboxes`, `all`, `flush`, `retry` (`sdk/.../sync.kt:64-130`) and every
  `Mail`/`Contacts`/`Groups` write that ends in `worker?.drain()`
  (`mail.kt:81,148,160,348,380`, `contacts.kt:156-489`). The HTTP dispatchers
  switch to `IO` themselves, but the store reads and the `Settings.receive`
  path did not.

**Fixed.** All run through `guard`/`client.io` (`sdk/.../io.kt`), which moves to
`Dispatchers.IO` and normalises failures.

### 3. Raw runtime exceptions from malformed answers

**Before.**
- `Jmap.session`, `call`, `upload` parsed with `JSONObject(execute(...))`
  (`net/.../jmap.kt:59,91,118`) and `getString` (`:65-67,95,119`): an HTML page
  with a 200 (captive portal, proxy), an empty or cut body threw
  `JSONException`. `call(name, args)` used `.first { }` (`:107`):
  `NoSuchElementException`. A session URL that does not parse threw
  `IllegalArgumentException` from `Request.Builder.url`; an unparsable blob
  type threw it from `toMediaType()` (`:111,114`).
- `Scheduling.execute` returned `transform(...)` unguarded
  (`net/.../scheduling.kt:687`): `JSONException` on an HTML 200. (`Mail` and
  `Contacts` already wrapped it in `Protocol`; `Contacts` also wrapped an
  `AdukiException` from the transform.)
- `Id.sessions` (`net/.../id.kt:192,196`), `Push` subscriptions `.first()`
  (`push.kt:183,202`), `Center` lists (`center.kt:234,283`), `Mail.headers`
  (`sdk/.../mail.kt`, `getJSONObject`/`getString` on the reply): raw
  `JSONException`/`NoSuchElementException`.
- `Push.parse` used `ids::getString` on server mailbox ids (`push.kt:134`): a
  non-string id threw from a push handler.
- `Oidc.discover` read endpoints with `getString` (`oidc.kt:84-88`) and every
  answer with `JSONObject(...)` (`:78,200,244,294,317,407`).

**Fixed.** Jmap, Scheduling and Oidc map a body that is not JSON, or lacks a
required field, to `AdukiException.Protocol`; Jmap maps a bad session URL to
`Protocol` and sends an unparsable blob type as `application/octet-stream`;
`Push.parse` skips a bad id. Everything else reached through the `sdk`
module is normalised by `guard`. Tests: `net ResilienceTest` (HTML 200, HTML
502, empty body, malformed method response, missing response, invalid URL),
`sdk ResilienceTest.aMalformedServerListIsAnAdukiExceptionNotAJsonException`.

### 4. DPoP signing failures inside OkHttp

**Before.** `Dpop.intercept` signed with the device key (`dpop.kt:83,89`). A
Keystore key that was invalidated or busy throws a runtime or security
exception from inside the interceptor. On a synchronous call it surfaced raw
(the typed clients wrapped it as `Network`, `Login`/`Id` did not); on an
`enqueue`d call OkHttp rethrows a non-`IOException` on its dispatcher thread,
which crashes. The renewal authenticator also re-signed with
`dpop?.apply(next)` (`client.kt:153`).

**Fixed.** The interceptor turns a signing failure into an `IOException` with
the cause; the authenticator gives up the retry (the 401 goes back). Test:
`net ResilienceTest.aKeyThatCannotSignFailsTheCallWithAnIOException`.

### 5. Token renewal

**Before.** `renew` loaded the token store outside any `try`
(`client.kt:184`); it runs inside the OkHttp interceptor and authenticator,
so an app `TokenStore` that throws (AccountManager, Keystore) failed every
request with that exception. Otherwise renewal was already careful: a refused
refresh drops the token, a 5xx or HTML answer keeps it, the 401 is returned.

**Fixed.** A failing load is reported and treated as no stored tokens. Tests:
`sdk ResilienceTest.a401WhoseRenewalIsRefusedIsAnAuthFailureAndDropsTheRefreshToken`,
`aRenewalAnsweredWithAnHtmlPageKeepsTheRefreshToken`.

### 6. Outbox retries

**Before.** `Worker.process` caught `Exception` (`worker.kt:72`), which also
swallowed `CancellationException`: a cancelled drain counted as a failed
attempt and was rescheduled. Retry-After was ignored: the REST clients did
not read it (`AdukiException.Network` had no field for it) and the worker used
jitter only.

**Fixed.** Cancellation propagates and leaves the action untouched. Mail,
Contacts, Scheduling, Jmap and Oidc read `Retry-After` (seconds or HTTP date)
into `Network.retryAfter`; the worker waits at least that long (capped at one
hour). `Oidc.awaitDevice`, the other existing retry loop, waits it out within
the device-code deadline. No retry loop was added to non-idempotent calls.
Tests: `WorkerTest.aRetryAfterHoldsTheActionAtLeastThatLong`,
`aHugeRetryAfterIsCappedAtAnHour`, `aThrowingRejectedCallbackDoesNotStopTheQueue`,
`aCancelledDispatchLeavesTheActionPendingAndPropagates`, `net ResilienceTest.a429CarriesRetryAfter`,
`restClientsCarryRetryAfterToo`, `DeviceTest.awaitWaitsOutARetryAfterOnA429`.

### 7. Swallowed cancellation and silent failures

- `Mail.openDraft` caught `Exception` around `body(hex)` (`mail.kt:395`) and
  `Aduki.me` around the lookup: both swallowed cancellation. Fixed; the
  failure is reported.
- `Scheduling.flush` and the calendar list in `sync` used `runCatching`
  (`scheduling.kt:54,153`): cancellation swallowed, failure unlogged. Fixed.

### 8. Local database writes of server data

**Before.** `Content.body` stored every fetched body (`sync/.../engine/content.kt:70`);
a body ObjectBox refused (database full, too large, closed) failed the read
although the body had arrived.

**Fixed.** The cache write is best effort and logged. Test:
`ContentTest.aBodyTheStoreCannotTakeIsStillReturned`. Other writes of server
data (sync engines, outbox) fail the call or the drain with
`AdukiException.Storage` now, not a raw ObjectBox exception.

### 9. Timeouts

`Client.create` sets connect, read and write timeouts (`net/.../client.kt:21-23`,
default 15 s). The Aduki ID clients (sign-in, sign-up, renewal, sessions) now
also have a call timeout of four times that. The mail client deliberately has
none: attachment downloads and the event stream outlive any fixed bound, and
the read timeout already ends a stalled transfer. The event stream uses a
90 s read timeout against 30 s server pings. A client passed through
`Builder.http` keeps its own timeouts.

### Checked and found sound

- `Mail`, `Contacts`, `Scheduling` and `Jmap` wrap a failed `execute()`
  (`UnknownHost`, `SocketTimeout`, `Connect`, `SSL`) in `Network` with the
  cause; `Login.logout` maps them to "not revoked".
- `Events.run` catches every `Exception`, backs off with jitter (1 to 60 s) and
  stops on `stop()`; the `rights` handler cannot throw.
- Redirects: OkHttp follows them with the same client; the authenticator
  retries a 401 once (`priorResponse`/`X-Aduki-Retry`).
- A mid-download reset surfaces as `IOException` from the stream (test:
  `net ResilienceTest.aConnectionResetMidDownloadIsAnIOException`).
- `Center` decodes server Base64 inside `try`/`runCatching`; `Dpop.bound`
  catches its decode.

## Proposals (not done)

1. **Poison actions in the outbox.** An action whose stored payload cannot be
   read (`JSONException`, `NumberFormatException` in `HttpDispatcher`) fails
   every attempt, and the drain stops at the first failure, so it blocks the
   queue forever. Marking such failures `Rejected` is right for local payloads
   but the same exception types come from malformed server answers, which
   should be retried. Proposal: parse the payload before dispatching (outside
   the network `try`) and reject on a parse failure; and cap attempts (e.g.
   move an action to `failed` after 20 attempts) so the app can show it.
2. **Wrap `IOException` as `AdukiException.Network`** in every public call,
   once the suite apps handle both alike. A behaviour change for apps that
   branch on `IOException`, so not done now.
3. **Blocking public API.** `Mail.open(attachment)` and the `net` typed clients
   (`Mail`, `Jmap`, `Center`, `Id`, `Login`, `Signup`, `Passkeys`, `Oidc`, ...) are
   blocking and public, and are not covered by `guard`: called directly they
   can still throw `JSONException` and friends from `Center`, `Id.sessions`,
   `Rules`, `Senders`, `Vacation` and `Subscriptions`, and must be called off
   the main thread. Proposal: give `Mail` a `suspend fun download(attachment)`
   and route the remaining parsers through `Envelope`.
4. **Android module.** `PasskeyFlow.io` (`android/.../passkey/passkeys.kt:127`)
   switches to `IO` but does not normalise; `Aduki.Builder.build()` and the
   `Aduki` constructor call `TokenStore.load()` unguarded. Not changed here
   (the Android build was not run); both should go through `Faults.normal`.
5. **Rights stream backoff.** A stream refused for good (revoked session)
   reconnects every 60 s at most, forever, until `unwatchRights()`. Proposal:
   stop after a refused renewal and report it in `fault`.
