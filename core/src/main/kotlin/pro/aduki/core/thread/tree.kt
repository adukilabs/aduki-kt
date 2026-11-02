package pro.aduki.core.thread

import pro.aduki.core.models.Person
import pro.aduki.core.models.ThreadMessage
import java.time.Duration
import java.time.Instant

/**
 * Tree turns the emails of a thread into a reply tree: copies with the same
 * Message-ID merged, each message under its parent (In-Reply-To, then
 * References, then the subject), placeholders for missing parents, cycles
 * broken, siblings by time.
 */
internal object Tree {

    /** One node: a merged email, or a placeholder ([entry] null) for a missing parent. */
    class Node(
        val key: String,
        val hex: String,
        val entry: Entry?,
        val copies: List<Entry>,
        val time: Instant?
    ) {
        var parent: Node? = null
        var depth = 0
        val children = ArrayList<Node>()
        /** The earliest time in this subtree, for placeholders. */
        var order: Instant? = time
    }

    private val SKEW: Duration = Duration.ofHours(48)

    /** The tree of [entries] in reading order (depth first, siblings by time). */
    fun build(entries: List<Entry>, now: Instant = Instant.now()): List<Node> {
        val nodes = merge(entries, now)
        val byId = HashMap<String, Node>()
        nodes.forEach { node -> node.entry?.messageId?.let(::id)?.let { byId.putIfAbsent(it, node) } }
        val missing = LinkedHashMap<String, Node>()
        val all = ArrayList(nodes)

        // Earlier messages first, so subject matching sees only earlier ones.
        val sorted = nodes.sortedWith(compareBy(nullsLast()) { it.time })
        for (node in sorted) {
            val entry = node.entry ?: continue
            val self = entry.messageId?.let(::id)
            val replyTo = entry.inReplyTo.map(::id).filter { it.isNotEmpty() && it != self }
            val refs = entry.references.map(::id).filter { it.isNotEmpty() && it != self }
            var parent: Node? = replyTo.firstNotNullOfOrNull { byId[it] }
                ?: refs.asReversed().firstNotNullOfOrNull { byId[it] }
            val direct = replyTo.firstOrNull() ?: refs.lastOrNull()
            if (direct != null && byId[direct] == null) {
                // The direct parent is not in the account: a placeholder under
                // the nearest earlier reference that is.
                val holder = missing.getOrPut(direct) {
                    Node(ThreadMessage.PLACEHOLDER + direct, ThreadMessage.PLACEHOLDER + direct, null, emptyList(), null).also { all += it }
                }
                if (holder.parent == null) {
                    val upto = refs.indexOf(direct).let { if (it < 0) refs.size else it }
                    val above = refs.take(upto).asReversed().firstNotNullOfOrNull { byId[it] }
                    if (above != null && !reaches(above, holder)) attach(holder, above)
                }
                parent = holder
            }
            if (parent == null && replyTo.isEmpty() && refs.isEmpty()) parent = subject(node, sorted)
            if (parent != null && parent !== node && !reaches(parent, node)) attach(node, parent)
        }

        // Placeholders sort by their earliest descendant.
        val roots = all.filter { it.parent == null }
        roots.forEach(::order)
        val out = ArrayList<Node>(all.size)
        val stack = ArrayDeque<Node>()
        roots.sortedWith(compareBy(nullsLast()) { it.order }).asReversed().forEach { it.depth = 0; stack.addLast(it) }
        val seen = HashSet<Node>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!seen.add(node)) continue
            out += node
            node.children.sortWith(compareBy(nullsLast()) { it.order })
            for (child in node.children.asReversed()) {
                child.depth = minOf(node.depth + 1, ThreadMessage.DEPTH)
                stack.addLast(child)
            }
        }
        // Anything left out (cannot happen once cycles are broken) is kept as a root.
        all.filter { it !in seen }.forEach { it.parent = null; it.depth = 0; out += it }
        return out
    }

    // Copies with the same Message-ID become one node.
    private fun merge(entries: List<Entry>, now: Instant): List<Node> {
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        entries.forEach { e ->
            val key = e.messageId?.let(::id)?.ifEmpty { null } ?: "hex:${e.hex}"
            groups.getOrPut(key) { ArrayList() } += e
        }
        return groups.map { (key, copies) ->
            val main = copies.firstOrNull { !it.sent } ?: copies.first()
            val ordered = listOf(main) + copies.filter { it !== main }
            Node(key, main.hex, combine(ordered), ordered, time(main.sentAt ?: ordered.firstNotNullOfOrNull { it.sentAt }, main.receivedAt, now))
        }
    }

    // The copies' facts in one entry: [copies] first is the preferred (non-Sent) copy.
    private fun combine(copies: List<Entry>): Entry {
        val main = copies.first()
        if (copies.size == 1) return main
        val unread = copies.any { it.unread() }
        val keywords = copies.flatMap { it.keywords }.toSet().let { all ->
            if (unread) all.filterNot { it.equals("\$seen", ignoreCase = true) }.toSet() else all
        }
        return main.copy(
            inReplyTo = main.inReplyTo.ifEmpty { copies.firstNotNullOfOrNull { c -> c.inReplyTo.ifEmpty { null } } ?: emptyList() },
            references = main.references.ifEmpty { copies.firstNotNullOfOrNull { c -> c.references.ifEmpty { null } } ?: emptyList() },
            keywords = keywords,
            mailboxes = copies.flatMap { it.mailboxes }.toSet(),
            attachments = main.attachments.ifEmpty { copies.firstNotNullOfOrNull { c -> c.attachments.ifEmpty { null } } ?: emptyList() },
            reply = main.reply ?: copies.firstNotNullOfOrNull { it.reply },
            // Security facts come from the received copy when there is one.
            security = copies.firstOrNull { !it.sent && it.security != null }?.security ?: copies.firstNotNullOfOrNull { it.security },
            text = main.text.ifEmpty { copies.firstOrNull { it.text.isNotEmpty() }?.text.orEmpty() },
            html = main.html.ifEmpty { copies.firstOrNull { it.html.isNotEmpty() }?.html.orEmpty() },
            quoteStart = main.quoteStart ?: copies.firstNotNullOfOrNull { it.quoteStart },
            sentAt = main.sentAt ?: copies.firstNotNullOfOrNull { it.sentAt },
            receivedAt = main.receivedAt ?: copies.firstNotNullOfOrNull { it.receivedAt }
        )
    }

    /** The ordering time: [sent], or [received] when [sent] is missing or more than 48 h ahead. */
    fun time(sent: Instant?, received: Instant?, now: Instant): Instant? {
        if (sent == null) return received
        val reference = received ?: now
        return if (sent.isAfter(reference.plus(SKEW)) || sent.isAfter(now.plus(SKEW))) received ?: now else sent
    }

    // The JWZ fallback: the latest earlier message with the same subject whose recipients include the sender.
    private fun subject(node: Node, sorted: List<Node>): Node? {
        val entry = node.entry ?: return null
        val sender = entry.from?.email?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        val title = normal(entry.subject)
        var found: Node? = null
        for (other in sorted) {
            if (other === node) break
            val e = other.entry ?: continue
            if (normal(e.subject) != title) continue
            if ((e.to + e.cc).any { it.email.trim().equals(sender, ignoreCase = true) }) found = other
        }
        return found
    }

    private fun attach(node: Node, parent: Node) {
        node.parent?.children?.remove(node)
        node.parent = parent
        parent.children += node
    }

    // Whether walking up from [from] reaches [target]: attaching target under from would close a cycle.
    private fun reaches(from: Node, target: Node): Boolean {
        var at: Node? = from
        var steps = 0
        while (at != null && steps++ < 100_000) {
            if (at === target) return true
            at = at.parent
        }
        return false
    }

    // Each node's earliest time in its subtree (iterative: deep trees do not overflow the stack).
    private fun order(root: Node) {
        val post = ArrayList<Node>()
        val stack = ArrayDeque<Node>().apply { addLast(root) }
        val seen = HashSet<Node>()
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!seen.add(n)) continue
            post += n
            n.children.forEach { stack.addLast(it) }
        }
        for (n in post.asReversed()) {
            for (c in n.children) {
                val t = c.order ?: continue
                if (n.time == null && (n.order == null || t.isBefore(n.order))) n.order = t
            }
        }
    }

    /** A Message-ID compared: no angle brackets or spaces, lower case. */
    fun id(raw: String): String = raw.trim().removePrefix("<").removeSuffix(">").trim().lowercase()

    private val PREFIX = Regex(
        "^\\s*(?:(?:re|fwd?|fw|aw|sv|wg|vs|tr|rv|rif|r|antw|odp|res|enc|ynt|vb|ref|tr|πρ|回复|回覆|答复|转发|轉寄)\\s*(?:\\[\\d+]|\\(\\d+\\))?\\s*[:：]|\\[[^\\]]{0,40}])\\s*",
        RegexOption.IGNORE_CASE
    )

    /** [subject] without reply and forward prefixes (`Re:`, `Fwd:`, `AW:`, `SV:`, `RE[2]:`, list tags), case kept. */
    fun strip(subject: String): String {
        var s = subject.trim()
        var guard = 0
        while (guard++ < 50) {
            val m = PREFIX.find(s) ?: break
            if (m.range.isEmpty()) break
            s = s.substring(m.range.last + 1)
        }
        return s.trim()
    }

    /** [subject] compared: prefixes stripped, case and whitespace folded. */
    fun normal(subject: String): String = strip(subject).lowercase().replace(Regex("\\s+"), " ")

    /** Every distinct address, senders first. */
    fun participants(nodes: List<Node>): List<Person> {
        val seen = HashSet<String>()
        val out = ArrayList<Person>()
        fun add(p: Person?) {
            if (p == null || p.email.isBlank()) return
            if (seen.add(p.email.trim().lowercase())) out += p
        }
        nodes.forEach { add(it.entry?.from) }
        nodes.forEach { n -> n.entry?.let { e -> (e.to + e.cc).forEach(::add) } }
        return out
    }
}
