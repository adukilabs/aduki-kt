# Mail Settings

`client.settings` reads and writes the account settings the mail server keeps, so they follow the account to every device: filter rules, the vacation reply, senders and their signatures, and new-mail push. Everything goes over JMAP (`client.jmap`, RFC 8620) and needs no local store. Calls are `suspend` and run on `Dispatchers.IO`.

Server refusals are thrown as `AdukiException.Api` with the JMAP error type as `kind` (`invalidSieve`, `forbiddenFrom`, `overQuota`, `stateMismatch`, `invalidProperties`, ...). Invalid input is refused before any request with `IllegalArgumentException` (each request type has `validate()`).

The typed transports live in `pro.aduki.net.http` and can be used directly on `client.jmap`: `Filters`, `Responder`, `Senders` and `Subscriptions`.

---

## 1. Filter rules

```kotlin
import pro.aduki.net.http.Condition
import pro.aduki.net.http.Rule

val invoices = Rule(
    name = "Invoices",
    conditions = listOf(
        Condition(Condition.Field.SENDER, "billing@"),
        Condition(Condition.Field.ATTACHMENT)
    ),
    move = "Finance/Invoices",
    read = true
)
client.settings.create(invoices)          // appended; returns the saved rules
client.settings.update(invoices.copy(flag = true))
client.settings.reorder(listOf(invoices.id, other.id))
client.settings.remove(invoices)
val rules = client.settings.rules()       // in the order they run
client.settings.save(emptyList())         // removes every rule
```

| `Rule` field | Meaning |
| :--- | :--- |
| `id` | Random 16-hex id, made by the SDK. |
| `name` | Label for the UI. |
| `conditions` | `SENDER` (`From`), `RECIPIENT` (`To` and `Cc`), `SUBJECT`: the header contains `value`, ignoring case. `ATTACHMENT`: a part has `Content-Disposition: attachment` (a heuristic; `value` is ignored). At least one. |
| `all` | All conditions must match (`allof`), else any (`anyof`). |
| `move` | File into the mailbox of this name (a path such as `Work/Invoices`); a missing mailbox is created. |
| `read`, `flag` | Set `\Seen` / `\Flagged`, on the copy this rule files and on every copy filed after it (Sieve `addflag`). |
| `delete` | Discard the message. Cannot be combined with `move`, `read` or `flag`. |
| `stop` | End the run when this rule matches (default `true`). |
| `active` | A rule that is off is kept but not run. |

**How they are stored.** The rules are one Sieve script (RFC 5228, JMAP `SieveScript`, RFC 9661) named `aduki`. Its first line is a comment holding the rules as JSON, so they read back exactly; the code below it is generated from them, with every string quoted and escaped. Each write uploads the script, checks it with `SieveScript/validate` and writes it in the same request (`SieveScript/set` compiles it again and refuses it with `invalidSieve`), then makes it the active script (`onSuccessActivateScript`).

**Other scripts.** Sieve runs one active script per account. Saving rules makes `aduki` the active one, so a script another client activated is switched off (it stays stored and can be activated again there). Scripts with other names are never changed or deleted. Removing every rule deletes the `aduki` script and, only if it was the active one, leaves no script active.

**Concurrent edits.** `create`, `update`, `remove` and `reorder` read the rules, change them and write them back with `ifInState`; if another device wrote in between, they read again and retry (up to three times). `save` replaces the rules unconditionally. A script named `aduki` edited by hand (its header gone) fails to read with `AdukiException.Protocol`; `save` overwrites it.

---

## 2. Vacation reply

```kotlin
import pro.aduki.net.http.Vacation

client.settings.save(Vacation(
    enabled = true,
    from = Instant.parse("2026-12-20T00:00:00Z"),
    to = Instant.parse("2027-01-04T00:00:00Z"),
    subject = "Out of office",
    text = "Back on 4 January.",
    html = null
))
val reply = client.settings.vacation()
client.settings.save(Vacation())          // off
```

JMAP `VacationResponse` (RFC 8621 section 8), the `singleton` object: `isEnabled`, `fromDate`, `toDate` (UTC, whole seconds; null is open), `subject`, `textBody`, `htmlBody`. An enabled reply needs a body, and `to` may not be before `from`.

---

## 3. Senders and signatures

```kotlin
import pro.aduki.net.http.Sender

val senders = client.settings.senders()   // default first
val me = senders.first { it.main }
client.settings.update(me.copy(signature = "--\nAda", html = "<p>Ada</p>"))
val alias = client.settings.create(Sender(email = "ada@team.example", name = "Ada (team)"))
client.settings.remove(alias)
```

JMAP `Identity` (RFC 8621 section 6): `email`, `name`, `textSignature` (`signature`), `htmlSignature` (`html`), `replyTo` (`reply`) and `bcc`. The server only accepts addresses the account may send as (its own, its aliases, addresses delegated to it); others are refused with `forbiddenFrom`. The number of senders is capped by plan (`overQuota`). An address never changes: remove the sender and create another.

`main` is the account's login address; it cannot be removed (`forbidden`). `default` is the sender new mail starts from (JMAP `isDefault`; exactly one sender has it, the `main` one until another is chosen). `client.settings.prefer(sender)` makes `sender` the default and clears the flag on the others. Mail sent without a `from` (`client.mail.send`, REST `POST /user/mail/send`) goes out as the default sender.

---

## 4. New-mail push

```kotlin
import pro.aduki.net.http.Subscription

// After the push provider gave the app an endpoint (e.g. UnifiedPush):
client.settings.registerPush(Subscription(
    url = endpoint,                       // public https Web Push endpoint
    device = installId,                   // stable per install, at most 255 bytes
    keys = Subscription.Keys(p256dh, auth) // optional: the server encrypts each push
))

// In the push receiver, for every message (decrypted JSON body):
client.settings.receive(body)

client.settings.unregisterPush(installId)
```

JMAP `PushSubscription` (RFC 8620 section 7.2), delivered as Web Push (RFC 8030) or as native FCM (below). Subscriptions belong to the user, not an account. `registerPush` first removes the earlier subscriptions of the same `device`, so calling it again replaces the registration; the server caps `expires` at seven days, so register again at least weekly (for example on each app start). With `keys` (the device's RFC 8291 P-256 public key and auth secret, base64url) each push is encrypted (`Content-Encoding: aes128gcm`) and the app decrypts it (the UnifiedPush connector does this); without them the body is plain JSON.

`receive(body)` parses the body and acts on it, returning the `Push` (or null for anything else):

| Body | Action |
| :--- | :--- |
| `{"@type": "PushVerification", "pushSubscriptionId": "p1", "verificationCode": "…"}` | Sends the code back (`PushSubscription/set`). Until then the server pushes nothing else. |
| `{"@type": "StateChange", "changed": {"<accountId>": {"Email": "…", "Thread": "…", "Mailbox": "…"}}, "mailboxes": {"<accountId>": ["<mailbox hex>"]}}` | `client.sync.all(...)` on the mailboxes the push names (`Push.Change.targets()`); a push without `mailboxes` syncs every mailbox. |
| `{"@type": "Rights", "epoch": 7}` | Renews the access token, like `client.rights(body)`. |

The mail host sends `Email`, `Thread` and `Mailbox` together on any change to the account's mail, with `mailboxes` naming the mailboxes (REST hexes) whose content or counts changed, so only those sync. A push carries no message content.

### Native FCM

`Subscription(device = installId, fcm = token)` registers a Firebase Cloud Messaging registration token instead of a Web Push URL. It is sent as the `fcmToken` extension of `PushSubscription/set`, alone (`{"fcmToken", "deviceClientId", "types"}`, no `url` and no `keys`), as the server requires. Each push, the verification included, then arrives as an FCM data message whose `push` entry is the JSON body as a string. Hand that string, or the whole data map, to `receive`:

```kotlin
client.settings.receive(message.data["push"]!!)   // the body
client.settings.receive(message.data)             // or the data map: its "push" entry
```

`receive(data)` returns null for a data message without `push` (one not from the mail server). The server drops a subscription whose token FCM reports as unregistered, and refuses an `fcmToken` (`invalidProperties`) when it has no FCM service account.

The SDK has no Firebase dependency: the app wires its own `FirebaseMessagingService`. `client` is the app's `Aduki` and `installId` a stable id per install.

```kotlin
class MailMessaging : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A new or rotated token: register it (this replaces the device's earlier registration).
    override fun onNewToken(token: String) {
        scope.launch { client.settings.registerPush(Subscription(device = installId, fcm = token)) }
    }

    // Called on a background thread; finish before returning.
    override fun onMessageReceived(message: RemoteMessage) {
        val body = message.data["push"] ?: return
        runBlocking { client.settings.receive(body) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

Registrations expire after at most seven days, so also register on app start with the current token (`FirebaseMessaging.getInstance().token`). On sign-out, call `client.settings.unregisterPush(installId)`.
