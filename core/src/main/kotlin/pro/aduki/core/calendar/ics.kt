package pro.aduki.core.calendar

/**
 * Property is one iCalendar content line, `NAME;PARAM=VALUE:value`
 * (RFC 5545 §3.1). [value] is kept as written (TEXT values escaped).
 */
data class Property(
    val name: String,
    val value: String,
    val params: List<Pair<String, String>> = emptyList()
) {
    fun param(key: String): String? = params.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

    /** This property with [key] set to [value] (added when absent). */
    fun with(key: String, value: String): Property {
        val kept = params.filterNot { it.first.equals(key, ignoreCase = true) }
        val at = params.indexOfFirst { it.first.equals(key, ignoreCase = true) }.let { if (it < 0) kept.size else it }
        return copy(params = kept.toMutableList().apply { add(at, key to value) })
    }

    internal fun line(): String = buildString {
        append(name)
        for ((k, v) in params) {
            append(';').append(k).append('=')
            if (v.any { it == ':' || it == ';' || it == ',' }) append('"').append(v).append('"') else append(v)
        }
        append(':').append(value)
    }
}

/**
 * Component is a `BEGIN:NAME` ... `END:NAME` block with its properties in
 * order and its nested components (a VEVENT's VALARMs, a VCALENDAR's
 * VEVENTs and VTIMEZONEs). Edits keep every line they do not touch.
 */
class Component(
    val name: String,
    val properties: MutableList<Property> = mutableListOf(),
    val children: MutableList<Component> = mutableListOf()
) {
    fun first(name: String): Property? = properties.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun all(name: String): List<Property> = properties.filter { it.name.equals(name, ignoreCase = true) }

    /** The unescaped TEXT value of [name], or null. */
    fun text(name: String): String? = first(name)?.value?.let(Ics::unescape)

    /** Replaces every [property] of its name with this one, where the first stood. */
    fun set(property: Property) {
        val at = properties.indexOfFirst { it.name.equals(property.name, ignoreCase = true) }
        remove(property.name)
        if (at < 0 || at > properties.size) properties.add(property) else properties.add(at, property)
    }

    /** Sets a TEXT property, escaping [text]; a blank text removes it. */
    fun set(name: String, text: String) {
        if (text.isBlank()) remove(name) else set(Property(name, Ics.escape(text)))
    }

    fun remove(name: String) {
        properties.removeAll { it.name.equals(name, ignoreCase = true) }
    }

    fun add(property: Property) {
        properties.add(property)
    }

    /** Child components named [name]. */
    fun components(name: String): List<Component> = children.filter { it.name.equals(name, ignoreCase = true) }

    internal fun write(out: StringBuilder) {
        Ics.fold(out, "BEGIN:$name")
        properties.forEach { Ics.fold(out, it.line()) }
        children.forEach { it.write(out) }
        Ics.fold(out, "END:$name")
    }
}

/**
 * Ics reads and writes iCalendar text (RFC 5545): content lines,
 * unfolding and folding, TEXT escaping, and the component tree.
 */
object Ics {

    /** The top-level components of [text] (normally one VCALENDAR). Malformed lines are skipped. */
    fun parse(text: String): List<Component> {
        val roots = mutableListOf<Component>()
        val stack = ArrayDeque<Component>()
        for (raw in unfold(text)) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val prop = read(line) ?: continue
            when (prop.name.uppercase()) {
                "BEGIN" -> stack.addLast(Component(prop.value.uppercase()))
                "END" -> {
                    val done = stack.removeLastOrNull() ?: continue
                    stack.lastOrNull()?.children?.add(done) ?: roots.add(done)
                }
                else -> stack.lastOrNull()?.add(prop)
            }
        }
        // An unterminated object still yields what was read.
        while (stack.isNotEmpty()) {
            val done = stack.removeLast()
            stack.lastOrNull()?.children?.add(done) ?: roots.add(done)
        }
        return roots
    }

    /** [component] as iCalendar text: CRLF line ends, lines folded at 75 octets. */
    fun render(component: Component): String = StringBuilder().also { component.write(it) }.toString()

    /** TEXT escaping (RFC 5545 §3.3.11). */
    fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\n")
        .replace("\r", "")
        .replace("\n", "\\n")

    fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + line.substring(1)
            } else {
                lines.add(line)
            }
        }
        return lines
    }

    // Splits a content line at the first ':' and the ';' outside quotes.
    private fun read(line: String): Property? {
        var quoted = false
        var colon = -1
        val cuts = mutableListOf<Int>()
        for ((i, c) in line.withIndex()) {
            when {
                c == '"' -> quoted = !quoted
                quoted -> {}
                c == ';' -> cuts.add(i)
                c == ':' -> { colon = i; break }
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val name = head.substring(0, cuts.firstOrNull() ?: head.length).trim()
        if (name.isEmpty()) return null
        val params = cuts.indices.mapNotNull { k ->
            val from = cuts[k] + 1
            val to = if (k + 1 < cuts.size) cuts[k + 1] else head.length
            val part = head.substring(from, to)
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim('"')
        }
        return Property(name.uppercase(), line.substring(colon + 1), params)
    }

    // Folds at 75 octets without splitting a UTF-8 sequence (RFC 5545 §3.1).
    internal fun fold(out: StringBuilder, line: String) {
        var used = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val point = line.codePointAt(i)
            val size = when {
                point < 0x80 -> 1
                point < 0x800 -> 2
                point < 0x10000 -> 3
                else -> 4
            }
            if (used + size > limit) {
                out.append("\r\n ")
                used = 0
                limit = 74
            }
            out.appendCodePoint(point)
            used += size
            i += Character.charCount(point)
        }
        out.append("\r\n")
    }
}
