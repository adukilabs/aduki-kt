package pro.aduki.core.thread

import pro.aduki.core.errors.Faults
import pro.aduki.core.models.Confidence
import pro.aduki.core.models.Conversation
import pro.aduki.core.models.Person
import pro.aduki.core.models.Reply
import pro.aduki.core.models.ReplySource
import pro.aduki.core.models.SecuritySummary
import pro.aduki.core.models.ThreadMessage
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * Threads builds a [Conversation] from the emails of a thread: the reply
 * tree ([Tree]) and each message's own words.
 *
 * A server `aduki:reply` with high or medium confidence is used as it is.
 * Otherwise the client cuts the body itself, taking the first step that
 * finds something:
 * 1. the server's quote start (`aduki:quoteStart`),
 * 2. an HTML quote marker (Gmail, Apple, Thunderbird, Outlook, Yahoo, ProtonMail),
 * 3. text patterns: attribution lines, Outlook headers, forward dividers, `>` blocks,
 * 4. the text of an ancestor found at the end (at least 90 % of its lines, in order),
 * then signatures (`-- `, mobile footers, and a trailing block that repeats
 * in two messages of the same sender).
 *
 * Each message is parsed within a time budget; a step that fails or runs out
 * of time leaves that message whole ([Confidence.Low]). [build] never throws
 * (cancellation aside).
 */
object Threads {

    /** The time one message's parsing may take, in milliseconds. */
    const val BUDGET = 100L

    /** Bodies (text or HTML) larger than this are not parsed: the message stays whole. */
    const val LIMIT = 1_000_000

    // Lines compared by the ancestor diff, at most.
    private const val LINES = 2_000

    /** The conversation [threadId] made of [entries] (in any order). */
    fun build(threadId: String, entries: List<Entry>, now: Instant = Instant.now(), budget: Long = BUDGET): Conversation =
        try {
            tree(threadId, entries, now, budget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Faults.report("thread tree", e)
            flat(threadId, entries, now)
        } catch (e: StackOverflowError) {
            Faults.report("thread tree", e)
            flat(threadId, entries, now)
        }

    private class Done(val node: Tree.Node, var reply: Reply, val client: Boolean)

    private fun tree(threadId: String, entries: List<Entry>, now: Instant, budget: Long): Conversation {
        val nodes = Tree.build(entries, now)
        val byId = HashMap<String, Tree.Node>()
        nodes.forEach { n -> n.entry?.messageId?.let { byId.putIfAbsent(Tree.id(it), n) } }
        val done = HashMap<Tree.Node, Done>()
        for (node in nodes) {
            val entry = node.entry ?: continue
            val server = entry.reply
            val result = if (server != null && server.confidence != Confidence.Low) {
                Done(node, server.copy(source = ReplySource.Server, quotedFrom = server.quotedFrom ?: server.quotedId?.let { byId[Tree.id(it)]?.hex }), false)
            } else {
                Done(node, extract(node, entry, done, Budget(budget)), true)
            }
            done[node] = result
        }
        learn(done.values.toList(), budget)
        val messages = nodes.map { node -> message(node, done[node]?.reply) }
        return Conversation(
            threadId = threadId,
            subject = Tree.strip(nodes.firstOrNull { it.entry != null }?.entry?.subject.orEmpty()),
            participants = Tree.participants(nodes),
            messages = messages,
            security = summary(messages)
        )
    }

    private fun message(node: Tree.Node, reply: Reply?): ThreadMessage {
        val e = node.entry ?: return ThreadMessage(
            hex = node.hex,
            messageId = node.key.removePrefix(ThreadMessage.PLACEHOLDER),
            parent = node.parent?.hex,
            depth = node.depth,
            time = node.order,
            placeholder = true
        )
        return ThreadMessage(
            hex = node.hex,
            messageId = bare(e.messageId),
            parent = node.parent?.hex,
            depth = node.depth,
            from = e.from ?: Person(),
            to = e.to,
            cc = e.cc,
            replyTo = e.replyTo,
            subject = e.subject,
            sentAt = e.sentAt,
            receivedAt = e.receivedAt,
            time = node.time,
            unread = e.unread(),
            flagged = e.keywords.any { it.equals("\$flagged", ignoreCase = true) },
            draft = e.keywords.any { it.equals("\$draft", ignoreCase = true) },
            keywords = e.keywords,
            mailboxes = e.mailboxes,
            copies = node.copies.map { it.hex },
            reply = reply ?: whole(e),
            attachments = e.attachments,
            security = e.security
        )
    }

    private fun summary(messages: List<ThreadMessage>): SecuritySummary {
        val facts = messages.filter { it.security != null }
        val known = facts.filter { it.security?.internal == true || it.security?.transport != null }
        val plain = known.firstOrNull { val s = it.security!!; !s.internal && s.transport?.tls != true }
        return SecuritySummary(available = facts.isNotEmpty(), encrypted = known.isNotEmpty() && plain == null, plain = plain?.hex)
    }

    // The body whole: nothing cut.
    private fun whole(e: Entry): Reply {
        val server = e.reply
        if (server != null) return server.copy(confidence = Confidence.Low, source = ReplySource.None)
        val text = e.text.ifEmpty { if (e.html.isNotEmpty() && e.html.length <= LIMIT) runCatching { Html.text(e.html, Budget(BUDGET)).text }.getOrDefault("") else "" }
        return Reply(text = text, html = e.html.ifEmpty { null }, confidence = Confidence.Low, source = ReplySource.None)
    }

    private fun extract(node: Tree.Node, entry: Entry, done: Map<Tree.Node, Done>, budget: Budget): Reply {
        val full = whole(entry)
        val html = (entry.reply?.html ?: entry.html).orEmpty()
        val text = full.text
        if (text.length > LIMIT || html.length > LIMIT) return full
        return try {
            cut(node, entry, text, html, done, budget)?.let { signed(it, budget) } ?: signed(full, budget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Budget.Overrun) {
            full
        } catch (e: Exception) {
            Faults.report("reply extraction", e)
            full
        } catch (e: StackOverflowError) {
            Faults.report("reply extraction", e)
            full
        }
    }

    // The nearest real ancestors, parent first.
    private fun ancestors(node: Tree.Node): List<Tree.Node> {
        val out = ArrayList<Tree.Node>()
        var at = node.parent
        while (at != null && out.size < ThreadMessage.DEPTH) {
            if (at.entry != null) out += at
            at = at.parent
        }
        return out
    }

    private fun cut(node: Tree.Node, entry: Entry, text: String, html: String, done: Map<Tree.Node, Done>, budget: Budget): Reply? {
        val parent = ancestors(node).firstOrNull()
        val parentId = bare(parent?.entry?.messageId)
        val parentName = parent?.entry?.from?.let { it.name?.ifBlank { null } ?: it.email.ifBlank { null } }
        fun quote(own: String, ownHtml: String?, quoted: String, start: Int?, confidence: Confidence, source: ReplySource, author: String?): Reply {
            val forward = forwardAt(quoted)
            return if (forward != null && quoted.substring(0, forward).isBlank()) {
                Reply(text = own, html = ownHtml, forwarded = Quotes.forwarded(quoted.substring(forward)), quoteStart = start, confidence = confidence, source = source)
            } else {
                Reply(
                    text = own, html = ownHtml, quoted = quoted.trim().ifEmpty { null }, quotedFrom = parent?.hex, quotedId = parentId,
                    author = author ?: Quotes.author(quoted) ?: parentName, quoteStart = start, confidence = confidence, source = source
                )
            }
        }

        // 1. The server's quote start.
        val start = if (entry.reply == null) entry.quoteStart else null
        if (start != null && start > 0 && start < text.length) {
            val own = Quotes.dropAttribution(text.substring(0, start))
            if (own.isNotBlank()) return quote(own, ownHtml(html, budget), text.substring(start), start, Confidence.High, ReplySource.Server, null)
        }

        // 2. HTML markers.
        if (html.isNotEmpty()) {
            val at = Html.marker(html, budget)
            if (at != null) {
                val ownHtml = html.substring(0, at)
                val rendered = Html.text(ownHtml, budget)
                val own = Quotes.dropAttribution(rendered.text)
                val quoted = Html.text(html.substring(at), budget).text
                val trimmed = trimHtml(ownHtml, rendered, own)
                // A quote first and the own words after it (bottom posting): the text patterns decide.
                if (own.isNotBlank() || forwardAt(quoted) != null) return quote(own, trimmed.ifBlank { null }, quoted, null, Confidence.High, ReplySource.Marker, null)
            }
        }

        // 3. Text patterns.
        val found = Quotes.cut(text, budget)
        if (found != null) {
            val quoted = text.substring(found.start, found.end)
            val own = if (found.start == 0) text.substring(found.end).trim() else Quotes.dropAttribution(text.substring(0, found.start))
            if (found.forward) {
                return Reply(text = own, html = ownHtml(html, budget), forwarded = Quotes.forwarded(quoted), quoteStart = found.start, confidence = found.confidence, source = ReplySource.Heuristic)
            }
            if (own.isNotBlank()) {
                return quote(own, ownHtml(html, budget), quoted, found.start, found.confidence, ReplySource.Heuristic, found.author)
            }
        }

        // 4. An ancestor's text at the end.
        for (ancestor in ancestors(node)) {
            budget.check()
            val theirs = done[ancestor]?.reply?.text ?: continue
            val at = diff(text, theirs, budget) ?: continue
            val own = text.substring(0, at).trimEnd()
            val e = ancestor.entry
            return Reply(
                text = own, html = null, quoted = text.substring(at).trim(), quotedFrom = ancestor.hex,
                quotedId = bare(e?.messageId),
                author = e?.from?.let { it.name?.ifBlank { null } ?: it.email.ifBlank { null } },
                quoteStart = at, confidence = Confidence.Medium, source = ReplySource.Diff
            )
        }
        return null
    }

    // The own part of [html] when a quote marker shows where it ends, else null.
    private fun ownHtml(html: String, budget: Budget): String? {
        if (html.isEmpty()) return null
        val at = Html.marker(html, budget) ?: return null
        val own = html.substring(0, at)
        val rendered = Html.text(own, budget)
        return trimHtml(own, rendered, Quotes.dropAttribution(rendered.text)).ifBlank { null }
    }

    // [html] without the lines [rendered] has past [own] (a dropped attribution line).
    private fun trimHtml(html: String, rendered: Html.Rendered, own: String): String {
        if (own.length >= rendered.text.length) return html
        val lines = own.count { it == '\n' } + 1
        val offsets = rendered.offsets
        if (own.isEmpty()) return if (offsets.isNotEmpty()) html.substring(0, offsets[0].coerceIn(0, html.length)) else html
        if (lines >= offsets.size) return html
        return html.substring(0, offsets[lines].coerceIn(0, html.length))
    }

    // The offset in [quoted] of a forward divider in its first lines, or null.
    private fun forwardAt(quoted: String): Int? {
        val head = quoted.take(4_000)
        val cut = runCatching { Quotes.cut(head, Budget(BUDGET)) }.getOrNull() ?: return null
        return if (cut.forward) cut.start else null
    }

    // A Message-ID without angle brackets, or null when blank.
    private fun bare(id: String?): String? = id?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()?.ifEmpty { null }

    /** A line compared by the diff: no quote marks, spaces folded, lower case. */
    private fun norm(line: String): String {
        var s = line.trim()
        while (s.startsWith(">")) s = s.substring(1).trimStart()
        return s.lowercase().replace(WS, " ")
    }

    private val WS = Regex("\\s+")

    /**
     * Where a trailing block of [text] starts whose lines hold at least 90 %
     * of [theirs]'s lines in order, or null. Own words must come before it.
     */
    internal fun diff(text: String, theirs: String, budget: Budget): Int? {
        val a = theirs.lines().map(::norm).filter { it.isNotEmpty() }.take(LINES)
        if (a.isEmpty() || a.sumOf { it.length } < 20) return null
        val l = Quotes.Lines(text)
        val mine = ArrayList<Int>() // indexes of non-blank lines
        val hashes = ArrayList<Int>()
        for (i in 0 until l.size) {
            val n = norm(l.lines[i])
            if (n.isEmpty()) continue
            mine += i
            hashes += n.hashCode()
        }
        if (mine.size > LINES * 2) return null
        val ah = a.map { it.hashCode() }.toIntArray()
        val heads = ah.take(3).toSet()
        for (s in 1 until hashes.size) {
            if (hashes[s] !in heads) continue
            budget.check()
            val block = hashes.subList(s, hashes.size)
            if (common(block, ah, budget) * 10 >= ah.size * 9) return l.starts[mine[s]]
        }
        return null
    }

    // The longest common subsequence of two line-hash sequences.
    private fun common(x: List<Int>, y: IntArray, budget: Budget): Int {
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            if (i and 31 == 0) budget.check()
            val xi = x[i - 1]
            for (j in 1..y.size) {
                cur[j] = if (xi == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.size]
    }

    // Explicit signatures: the `-- ` delimiter, a mobile footer, an HTML signature container.
    private fun signed(reply: Reply, budget: Budget): Reply {
        if (reply.signature != null) return reply
        val (own, signature) = Quotes.signature(reply.text) ?: return reply
        val html = reply.html?.let { h -> Html.signature(h, budget)?.let { at -> h.substring(0, at) } ?: h }
        val cut = reply.confidence == Confidence.Low
        return reply.copy(
            text = own, signature = signature, html = html,
            confidence = if (cut) Confidence.Medium else reply.confidence,
            source = if (cut) ReplySource.Heuristic else reply.source
        )
    }

    // A trailing block repeated in two messages of the same sender is that sender's signature.
    private fun learn(all: List<Done>, budget: Long) {
        val groups = all.filter { it.node.entry?.from?.email?.isNotBlank() == true }
            .groupBy { it.node.entry!!.from!!.email.trim().lowercase() }
            .filterValues { it.size >= 2 }
        // Compared before any is cut.
        val texts = all.associateWith { it.reply.text.lines() }
        for (group in groups.values) {
            for (item in group) {
                if (!item.client || item.reply.signature != null) continue
                try {
                    val clock = Budget(budget)
                    val mine = texts.getValue(item)
                    var best = 0
                    for (other in group) {
                        if (other === item) continue
                        clock.check()
                        best = maxOf(best, trailing(mine, texts.getValue(other)))
                    }
                    if (best == 0) continue
                    val keep = mine.size - best
                    val own = mine.take(keep).joinToString("\n").trimEnd()
                    val signature = mine.drop(keep).joinToString("\n").trim()
                    if (own.isBlank() || signature.length < 3) continue
                    val cut = item.reply.confidence == Confidence.Low
                    item.reply = item.reply.copy(
                        text = own, signature = signature,
                        confidence = if (cut) Confidence.Medium else item.reply.confidence,
                        source = if (cut) ReplySource.Heuristic else item.reply.source
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e !is Budget.Overrun) Faults.report("signature learning", e)
                }
            }
        }
    }

    // How many trailing lines (blank lines included) [a] and [b] share: at most 10 non-blank lines, 800 characters.
    private fun trailing(a: List<String>, b: List<String>): Int {
        var i = a.size - 1
        var j = b.size - 1
        while (i >= 0 && a[i].isBlank()) i--
        while (j >= 0 && b[j].isBlank()) j--
        val end = i
        var shared = 0
        var lines = 0
        var chars = 0
        while (i >= 0 && j >= 0) {
            if (a[i].isBlank()) { i--; continue }
            if (b[j].isBlank()) { j--; continue }
            if (norm(a[i]) != norm(b[j])) break
            lines++
            chars += a[i].length
            if (lines > 10 || chars > 800) return 0
            shared = a.size - i
            i--
            j--
        }
        if (lines == 0) return 0
        // A block with nothing but very short lines ("Thanks") is not a signature.
        if (a.subList(a.size - shared, end + 1).all { it.trim().length < 3 }) return 0
        return shared
    }

    // The fallback when the tree itself failed: every message, flat, by time, whole.
    private fun flat(threadId: String, entries: List<Entry>, now: Instant): Conversation {
        val messages = entries.sortedWith(compareBy(nullsLast()) { Tree.time(it.sentAt, it.receivedAt, now) }).map { e ->
            ThreadMessage(
                hex = e.hex, messageId = e.messageId, from = e.from ?: Person(), to = e.to, cc = e.cc, replyTo = e.replyTo,
                subject = e.subject, sentAt = e.sentAt, receivedAt = e.receivedAt, time = Tree.time(e.sentAt, e.receivedAt, now),
                unread = e.unread(), keywords = e.keywords, mailboxes = e.mailboxes, copies = listOf(e.hex),
                reply = runCatching { whole(e) }.getOrDefault(Reply(text = e.text, html = e.html.ifEmpty { null })),
                attachments = e.attachments, security = e.security
            )
        }
        return Conversation(threadId = threadId, subject = entries.firstOrNull()?.subject.orEmpty(), messages = messages)
    }
}
