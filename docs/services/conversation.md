# Conversations

`Mail.conversation(threadId)` turns a mail thread into what a thread reader
shows:
- a reply tree: who answered whom;
- each message reduced to its own words, with the quoted history, the
  signature and any forwarded message kept apart;
- how each message was delivered (TLS, SPF, DKIM, DMARC), when the server says.

The rule is that nothing is lost and nothing throws. Every step has a
fallback, and the last one is the whole body with `Confidence.Low`.

```kotlin
// A flow: the stored thread first (offline), then the server's, then again on every change.
fun conversation(threadId: String): Flow<pro.aduki.core.models.Conversation>

// One snapshot. refresh = true asks the server again for what it read about each message.
suspend fun conversation(threadId: String, refresh: Boolean): pro.aduki.core.models.Conversation
```

The models are in `pro.aduki.core.models` (`thread.kt`). The name
`Conversation` is also used by `pro.aduki.state.repository.Conversation`
(the mailbox summary of `Mail.conversations(mailbox)`). Code that uses both
imports one of them under another name.

## Models

```kotlin
data class Conversation(
    val threadId: String = "",
    val subject: String = "",              // the first message's subject without Re:/Fwd:
    val participants: List<Person> = emptyList(), // senders first, then recipients, once each
    val messages: List<ThreadMessage> = emptyList(), // reading order: depth first, siblings by time
    val security: SecuritySummary = SecuritySummary(),
    val offline: Boolean = false           // built from the local store only
)

data class ThreadMessage(
    val hex: String = "", val messageId: String? = null,
    val parent: String? = null, val depth: Int = 0,      // depth capped at 32
    val from: Person = Person(), val to: List<Person> = emptyList(), val cc: List<Person> = emptyList(),
    val replyTo: List<Person> = emptyList(), val subject: String = "",
    val sentAt: Instant? = null, val receivedAt: Instant? = null, val time: Instant? = null,
    val unread: Boolean = false, val flagged: Boolean = false, val draft: Boolean = false,
    val keywords: Set<String> = emptySet(), val mailboxes: Set<String> = emptySet(),
    val copies: List<String> = emptyList(), // hexes merged into this node (sent and received copy)
    val reply: Reply = Reply(),
    val attachments: List<Attachment> = emptyList(),
    val security: Security? = null,
    val placeholder: Boolean = false
)

data class Reply(
    val text: String = "", val html: String? = null,
    val quoted: String? = null, val quotedFrom: String? = null, val quotedId: String? = null,
    val author: String? = null, val quoteStart: Int? = null,
    val signature: String? = null, val forwarded: Forwarded? = null,
    val confidence: Confidence = Confidence.Low,   // High, Medium, Low
    val source: ReplySource = ReplySource.None     // Server, Marker, Heuristic, Diff, None
)

data class Forwarded(val from: String? = null, val date: String? = null, val subject: String? = null, val text: String = "")

data class Security(
    val transport: Transport? = null, val spf: AuthResult? = null, val dkim: List<AuthResult>? = null,
    val dmarc: AuthResult? = null, val signedBy: String? = null, val mailedBy: String? = null,
    val internal: Boolean = false
)
data class Transport(val tls: Boolean = false, val version: String? = null, val cipher: String? = null, val from: String? = null)
data class AuthResult(val result: String = "", val domain: String? = null, val selector: String? = null, val policy: String? = null)
data class SecuritySummary(val available: Boolean = false, val encrypted: Boolean = false, val plain: String? = null)

typealias Person = Address
```

Every field has a default, so fields added later do not break code that
builds these classes with named arguments.

- `Reply.confidence`: `High` means an explicit marker was found (an HTML quote
  container, an attribution line, an Outlook header). `Medium` means a
  heuristic match. `Low` means nothing was cut: `text` and `html` are the
  whole body, and the app shows it with the quote fold.
- `Reply.html` is the own words as HTML, or null when the message has no HTML
  or the cut could only be made in the text. It is sanitised only when the
  server sanitises bodies (`Body.sanitised`).
- `Reply.quotedFrom` is the hex of the quoted message when it is in the
  thread. `Reply.author` is the quoted author's name, for the "quoted Ana" pill.
- A placeholder (`placeholder` true, `hex` = `ThreadMessage.PLACEHOLDER` +
  Message-ID) stands for a parent the account does not have: "Earlier message
  not in your mailbox".
- `SecuritySummary.encrypted` is true when every message whose transport is
  known arrived over TLS (or within the server), and at least one is known.
  `plain` is the hex of the first message known to have arrived without TLS.
  When `available` is false, the server gave no security facts: show "Security
  details not available", never a guess.

## What is fetched

1. One JMAP request: `Thread/get`, then `Email/get` by back-reference with
   `messageId`, `inReplyTo`, `references`, `subject`, `sentAt`, `receivedAt`,
   `from`, `to`, `cc`, `replyTo`, `keywords`, `mailboxIds` and `attachments`,
   and `Mailbox/get` for the Sent role. An unknown thread is
   `AdukiException.Api` (404).
2. When the server supports them, `aduki:reply` and `aduki:security` for the
   messages not read before. They are kept in memory by message hex (up to
   2000 messages), since they do not change. `refresh = true` asks again.
3. Bodies, only for messages without a usable `aduki:reply`. They are read in
   one batch through the body cache (`Content`), so a body read once is read
   offline afterwards. A low-confidence `aduki:reply` already carries the
   whole text, so no body is needed for it.

Flags come from the local store when it has the message, so a message
marked read on this device shows as read before the change is synced.
Messages deleted on the device are left out.

### Capability detection

The reading capability `urn:aduki:params:jmap:reading` lists the `Email`
properties it serves:

```json
"urn:aduki:params:jmap:reading": { "properties": ["aduki:text", "aduki:html", "aduki:reply", "aduki:security"] }
```

`Jmap.Session.properties` holds that list per capability. `aduki:reply` and
`aduki:security` are asked for only when they are in it (`Jmap.REPLY`,
`Jmap.SECURITY`). A server without the capability, or one whose capability
has no such list, gets no request for them, and the client parses every
body. If the server refuses the properties anyway, the client parses the
bodies and asks again on the next load. The session is read once per client.

### Offline

With a local store, the flow emits the stored thread first
(`offline = true`), then the server's, then again whenever the stored thread
changes (a new message, a flag, a move). If the server cannot be reached,
the stored thread stands and the flow goes on; the snapshot call returns it
too. The store keeps no In-Reply-To or References. Offline, the tree uses the
headers the server gave earlier in this process. Without them it falls back
to matching subjects and recipients. Bodies come from the body cache. If a
body is not cached, the message shows its preview with `Confidence.Low`.
Without a stored thread, an offline call fails as other `Mail` calls do
(`AdukiException` or `IOException`).

## The reply tree

1. **Merging copies.** Messages with the same Message-ID (the sent copy and
   the received copy) become one node. Its `copies` lists every hex. Security
   facts come from the copy outside the Sent mailbox. The node is unread when
   any copy is.
2. **Parent.** The parent is the first In-Reply-To id present in the thread.
   Failing that, it is the last References id present. When the message has
   neither header, a subject fallback applies: the latest earlier message
   whose subject matches (with `Re:`, `Fwd:`, `AW:`, `SV:`, `RE[2]:`, list
   tags and similar prefixes stripped, case and spaces folded) and whose
   recipients include the sender.
3. **Placeholders.** When the direct parent (In-Reply-To, or the last
   reference) is not in the account, a placeholder takes its place. The
   placeholder hangs under the nearest earlier reference that is present.
4. **Cycles** and self-references are broken by ignoring the edge that would
   close the cycle. `depth` is capped at 32.
5. **Order.** Siblings are ordered by `sentAt`. When `sentAt` is missing, or
   more than 48 h after the arrival, `receivedAt` is used instead
   (`ThreadMessage.time`). Placeholders sort by their earliest reply.

## Own words

A server `aduki:reply` with high or medium confidence is used as it is
(`source = Server`). Otherwise, when it is missing or has low confidence, the
client cuts the body. It takes the first step that finds something:

1. **Server quote start** (`Server`). The `quoteStart` of a body the server
   read (0.6.2 reading).
2. **HTML markers** (`Marker`, `High`). Gmail `gmail_quote`, Apple and
   Thunderbird `blockquote type=cite` and `moz-cite-prefix`, Outlook
   `#divRplyFwdMsg`, `#appendonsend` and `#stopSpelling`, Yahoo
   `yahoo_quoted`, ProtonMail `protonmail_quote`. The HTML is cut before the
   marker. A small tokenizer reads the HTML: it works in linear time, needs no
   HTML library, and reads malformed HTML as text instead of failing.
3. **Text patterns** (`Heuristic`). These are:
   - "On … wrote:" attribution lines in English, French, German, Spanish,
     Portuguese, Dutch, Italian, Swahili and Chinese, including lines wrapped
     over two. Followed by a `>` block they give `High`, otherwise `Medium`.
   - Outlook's `-----Original Message-----` and its `From:`/`Sent:`/`To:`
     header block (localised): `High`.
   - Forward dividers (`---------- Forwarded message ---------`, `Begin
     forwarded message:` and their translations). These become `forwarded`,
     never a quote.
   - A `>` block that runs to the end, or a `>` block at the top followed by
     the own words (bottom posting): `Medium`. Interleaved replies are not
     cut.
4. **Ancestor diff** (`Diff`, `Medium`). A trailing block that holds at least
   90 % of an ancestor's own lines, in order, is cut. Lines are compared
   without `>` marks, case or extra spaces, by the longest common subsequence
   of their hashes. This catches clients that quote without any marker.

After the cut, signatures are split off into `signature`:
- the `-- ` delimiter;
- a mobile footer ("Sent from my iPhone" and its translations);
- an HTML `gmail_signature`, `moz-signature` or `#Signature` container (in
  `html`);
- **learning**: a trailing block, up to 10 lines, that repeats in two
  messages of the same sender in the thread.

When a message had no quote but a signature was split off, the result is
`Medium` with `source = Heuristic`.

### Limits

- Each message is parsed within a 100 ms budget (`Threads.BUDGET`). A body or
  HTML over 1 MB (`Threads.LIMIT`) is not parsed. A step that throws or runs
  out of time leaves that message whole (`Low`, `source = None`).
- Parsing runs on `Dispatchers.Default`. Network and database work runs on
  `Dispatchers.IO` through the same guard as the other `Mail` calls. The
  calls fail only as described in [Failure handling](../resilience.md); a
  parse failure is never one of them.
- A 200-message thread in which every message quotes the whole history
  (about 20 000 quoted lines) parses in about 0.3 s on a desktop JVM
  (`ThreadsTest.deepChainsAreCappedAndTwoHundredMessagesParseQuickly`).

## Example

```kotlin
client.mail.conversation(threadId).collect { c ->
    header(c.subject, c.participants, c.messages.count { !it.placeholder }, c.security)
    for (m in c.messages) {
        if (m.placeholder) { earlierMessageMissing(m.depth); continue }
        val r = m.reply
        if (r.confidence == Confidence.Low) showWhole(m, r.html ?: r.text)
        else showOwnWords(m, r.html ?: r.text, quotedPill = r.author, signature = r.signature, forward = r.forwarded)
    }
}
```

The building blocks are public for tests and tools: `pro.aduki.core.thread.Threads.build(threadId, entries)`
builds a `Conversation` from `Entry` values, and
`pro.aduki.sync.http.JmapThreadTransport` reads them over JMAP.
