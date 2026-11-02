package pro.aduki.core.thread

/**
 * Budget is the time one message's parsing may take. Loops call [check],
 * which throws [Overrun] past the deadline; the caller then keeps the whole
 * body.
 */
internal class Budget(millis: Long) {
    private val deadline = System.nanoTime() + millis * 1_000_000

    fun check() {
        if (System.nanoTime() > deadline) throw Overrun()
    }

    class Overrun : RuntimeException("parse budget spent")
}

/**
 * Html is a small, forgiving HTML scanner: enough to find quote markers and
 * to turn a fragment into text. It never builds a tree and runs in linear
 * time; malformed input (unclosed tags, stray `<`, unbalanced quotes) reads
 * as text rather than failing.
 */
internal object Html {

    /** One start or end tag: [start] is the offset of its `<`, [end] just past its `>`. */
    class Tag(val name: String, val close: Boolean, val start: Int, val end: Int, private val raw: String) {
        private val attributes: Map<String, String> by lazy { parse(raw) }

        fun attr(name: String): String? = attributes[name]

        fun classes(): List<String> = attr("class")?.split(' ', '\t', '\n', '\r')?.filter { it.isNotEmpty() }?.map { it.lowercase() } ?: emptyList()
    }

    /** Text rendered from HTML: [text], and for each of its lines the HTML offset where that line starts. */
    class Rendered(val text: String, val offsets: IntArray)

    /** Class names that start a quoted history. */
    private val QUOTE_CLASSES = setOf("gmail_quote", "gmail_quote_container", "yahoo_quoted", "protonmail_quote", "moz-cite-prefix")

    /** Ids that start a quoted history (Outlook). */
    private val QUOTE_IDS = setOf("divrplyfwdmsg", "appendonsend", "stopspelling")

    private val SIGNATURE_CLASSES = setOf("gmail_signature", "moz-signature")
    private val SIGNATURE_IDS = setOf("signature", "x_signature")

    private val BLOCKS = setOf(
        "p", "div", "br", "blockquote", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
        "hr", "pre", "section", "article", "header", "footer", "address", "dd", "dt", "dl"
    )
    private val RAW = setOf("script", "style", "title", "head", "template", "noscript")

    /**
     * The offset of the first tag that starts a quoted history (Gmail,
     * Apple/Thunderbird `blockquote type=cite`, Outlook, Yahoo, ProtonMail),
     * or null.
     */
    fun marker(html: String, budget: Budget): Int? {
        var found: Int? = null
        scan(html, budget) { tag ->
            if (!tag.close && quote(tag)) {
                found = tag.start
                false
            } else true
        }
        return found
    }

    /** The offset of a signature container (`gmail_signature`, `moz-signature`, Outlook `#Signature`), or null. */
    fun signature(html: String, budget: Budget): Int? {
        var found: Int? = null
        scan(html, budget) { tag ->
            if (!tag.close && (tag.classes().any { it in SIGNATURE_CLASSES } || tag.attr("id")?.lowercase() in SIGNATURE_IDS)) {
                found = tag.start
                false
            } else true
        }
        return found
    }

    private fun quote(tag: Tag): Boolean {
        if (tag.name == "blockquote" && tag.attr("type")?.equals("cite", ignoreCase = true) == true) return true
        if (tag.classes().any { it in QUOTE_CLASSES }) return true
        val id = tag.attr("id")?.lowercase() ?: return false
        return id in QUOTE_IDS
    }

    /**
     * Calls [visit] for each tag in order until it returns false. Comments,
     * doctype and the content of raw elements (`script`, `style`, ...) are
     * skipped.
     */
    fun scan(html: String, budget: Budget, visit: (Tag) -> Boolean) {
        walk(html, budget, visit) { _, _, _ -> }
    }

    /** Renders [html] as text: block tags break lines, `blockquote` content gets `> ` per depth. */
    fun text(html: String, budget: Budget): Rendered {
        val lines = ArrayList<StringBuilder>()
        val offsets = ArrayList<Int>()
        var current = StringBuilder()
        var start = -1
        var quote = 0
        var pre = 0
        var space = false
        fun newline() {
            lines += current
            offsets += if (start < 0) html.length else start
            current = StringBuilder()
            start = -1
            space = false
        }
        walk(html, budget, { tag ->
            if (start < 0 && current.isEmpty()) start = tag.start
            when (tag.name) {
                "blockquote" -> {
                    if (current.isNotEmpty()) newline()
                    quote = if (tag.close) maxOf(0, quote - 1) else quote + 1
                }
                "pre" -> {
                    if (current.isNotEmpty()) newline()
                    pre = if (tag.close) maxOf(0, pre - 1) else pre + 1
                }
                "br" -> newline()
                else -> if (tag.name in BLOCKS && current.isNotEmpty()) newline()
            }
            true
        }) { from, to, raw ->
            val run = entities(raw.substring(from, to))
            for (c in run) {
                if (c == '\n' && pre > 0) {
                    newline()
                    continue
                }
                val blank = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000c'
                if (blank && pre == 0) {
                    if (current.isNotEmpty()) space = true
                    continue
                }
                if (current.isEmpty()) {
                    if (start < 0) start = from
                    repeat(quote) { current.append("> ") }
                } else if (space) {
                    current.append(' ')
                }
                space = false
                current.append(c)
            }
        }
        if (current.isNotEmpty()) newline()
        // Trim, and fold runs of blank lines into one.
        val text = StringBuilder()
        val kept = ArrayList<Int>()
        var blank = true
        for (i in lines.indices) {
            val line = lines[i].toString().trimEnd()
            if (line.isBlank()) {
                if (blank) continue
                blank = true
            } else {
                blank = false
            }
            if (kept.isNotEmpty()) text.append('\n')
            text.append(line)
            kept += offsets[i]
        }
        var out = text.toString()
        while (out.endsWith("\n")) {
            out = out.dropLast(1)
            kept.removeAt(kept.size - 1)
        }
        return Rendered(out, kept.toIntArray())
    }

    // The tokenizer: tags to [tag], text runs to [text] (as offsets into the input).
    private fun walk(html: String, budget: Budget, tag: (Tag) -> Boolean, text: (Int, Int, String) -> Unit) {
        val n = html.length
        var i = 0
        var run = 0
        var closed = true // false once a '<' was found without a '>' after it: the rest is text
        var steps = 0
        while (i < n) {
            if (++steps and 0xFFF == 0) budget.check()
            val c = html[i]
            if (c != '<' || !closed || i + 1 >= n) {
                i++
                continue
            }
            val next = html[i + 1]
            if (html.startsWith("<!--", i)) {
                if (run < i) text(run, i, html)
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                run = i
                continue
            }
            if (next == '!' || next == '?') {
                if (run < i) text(run, i, html)
                val end = html.indexOf('>', i + 2)
                i = if (end < 0) n else end + 1
                run = i
                continue
            }
            val close = next == '/'
            val nameStart = if (close) i + 2 else i + 1
            if (nameStart >= n || !html[nameStart].isLetter()) {
                i++
                continue
            }
            val end = tagEnd(html, nameStart)
            if (end < 0) {
                // -1: no '>' anywhere after: the rest is text. -2: another '<' first: this one was text.
                if (end == -1) closed = false
                i++
                continue
            }
            var nameEnd = nameStart
            while (nameEnd < end && (html[nameEnd].isLetterOrDigit() || html[nameEnd] == '-' || html[nameEnd] == ':')) nameEnd++
            val name = html.substring(nameStart, nameEnd).lowercase()
            if (run < i) text(run, i, html)
            val raw = html.substring(nameEnd, end)
            val found = Tag(name, close, i, end + 1, raw)
            if (!tag(found)) return
            i = end + 1
            if (!close && name in RAW && !raw.trimEnd().endsWith("/")) {
                val stop = html.indexOf("</$name", i, ignoreCase = true)
                i = if (stop < 0) n else maxOf(i, html.indexOf('>', stop).let { if (it < 0) n else it + 1 })
            }
            run = i
        }
        if (run < n) text(run, n, html)
    }

    // The offset of the '>' ending the tag whose name starts at [from]; -1
    // when no '>' follows at all, -2 when another '<' comes first.
    private fun tagEnd(html: String, from: Int): Int {
        val quoted = quotedEnd(html, from)
        if (quoted != -1) return quoted
        // An unbalanced quote: the first '>' ends the tag.
        return html.indexOf('>', from)
    }

    private fun quotedEnd(html: String, from: Int): Int {
        var quote = 0.toChar()
        var i = from
        while (i < html.length) {
            val c = html[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                // A quote only opens a value right after '='.
                var k = i - 1
                while (k > from && html[k].isWhitespace()) k--
                if (html[k] == '=') quote = c
            } else if (c == '>') {
                return i
            } else if (c == '<' && i > from) {
                // A new tag before this one closed: this '<' was text.
                return -2
            }
            i++
        }
        return -1
    }

    private fun parse(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = raw.length
        while (i < n) {
            while (i < n && (raw[i].isWhitespace() || raw[i] == '/')) i++
            val keyStart = i
            while (i < n && !raw[i].isWhitespace() && raw[i] != '=' && raw[i] != '/') i++
            if (i == keyStart) {
                i++
                continue
            }
            val key = raw.substring(keyStart, i).lowercase()
            while (i < n && raw[i].isWhitespace()) i++
            var value = ""
            if (i < n && raw[i] == '=') {
                i++
                while (i < n && raw[i].isWhitespace()) i++
                if (i < n && (raw[i] == '"' || raw[i] == '\'')) {
                    val q = raw[i]
                    val end = raw.indexOf(q, i + 1).let { if (it < 0) n else it }
                    value = raw.substring(i + 1, end)
                    i = end + 1
                } else {
                    val start = i
                    while (i < n && !raw[i].isWhitespace()) i++
                    value = raw.substring(start, i)
                }
            }
            out.putIfAbsent(key, entities(value))
        }
        return out
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "uuml" to "ü",
        "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "ntilde" to "ñ"
    )

    /** [value] with character references decoded; unknown ones are kept as written. */
    fun entities(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = value.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                out.append(c)
                i++
                continue
            }
            val ref = value.substring(i + 1, semi)
            val decoded = when {
                ref.startsWith("#x") || ref.startsWith("#X") -> ref.substring(2).toIntOrNull(16)?.let(::code)
                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::code)
                else -> NAMED[ref.lowercase()]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun code(point: Int): String? =
        if (point in 1..0x10FFFF && point !in 0xD800..0xDFFF) String(Character.toChars(point)) else null
}
