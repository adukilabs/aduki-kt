package pro.aduki.core.vcard

import pro.aduki.core.models.Card

/**
 * Vcard reads and writes vCard 3.0 (RFC 2426) and 4.0 (RFC 6350).
 *
 * Reading unfolds lines, honours property groups (`item1.EMAIL`), quoted
 * parameters and text escapes, and turns a 3.0 inline photo
 * (`PHOTO;ENCODING=b;TYPE=JPEG:...`) into a `data:` URI. Writing keeps every
 * property it does not model from the card's source vCard, and keeps the
 * source line (with its `TYPE` and other parameters) of each e-mail address
 * and phone number that is still present. Lines are folded at 75 octets.
 */
object Vcard {
    const val V3 = "3.0"
    const val V4 = "4.0"

    // Properties [write] produces itself; anything else is copied from the source.
    private val OWN = setOf("BEGIN", "END", "VERSION", "UID", "FN", "N", "ORG", "EMAIL", "TEL", "NOTE", "PHOTO", "CATEGORIES")

    /** The cards in [text], each from `BEGIN:VCARD` to `END:VCARD` (an unterminated last one included). */
    fun split(text: String): List<String> {
        val cards = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in lines(text)) {
            val upper = line.trim().uppercase()
            if (current == null) {
                if (upper == "BEGIN:VCARD") current = StringBuilder(line).append("\r\n")
                continue
            }
            current.append(line).append("\r\n")
            if (upper == "END:VCARD") {
                cards += current.toString()
                current = null
            }
        }
        current?.let { cards += it.toString() }
        return cards
    }

    /**
     * The fields of one vCard; [Card.vcard] is [card] itself. Throws
     * [IllegalArgumentException] when it is not a 3.0 or 4.0 vCard or has
     * no name (`FN`, `N`, organisation, e-mail or phone).
     */
    fun parse(card: String): Card {
        val props = unfold(card).mapNotNull(::property)
        require(props.any { it.name == "BEGIN" && it.value.equals("VCARD", true) }) { "BEGIN:VCARD missing" }
        require(props.any { it.name == "END" && it.value.equals("VCARD", true) }) { "END:VCARD missing" }
        val version = props.firstOrNull { it.name == "VERSION" }?.value?.trim().orEmpty()
        require(version == V3 || version == V4) { "unsupported vCard version '$version' (3.0 and 4.0 are read)" }

        var name = ""
        var structured = ""
        var company = ""
        var photo = ""
        var uid = ""
        val emails = mutableListOf<String>()
        val phones = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val groups = LinkedHashSet<String>()
        for (p in props) {
            when (p.name) {
                "FN" -> if (name.isEmpty()) name = unescape(p.value).trim()
                "N" -> if (structured.isEmpty()) structured = person(p.value)
                "ORG" -> if (company.isEmpty()) company = unescape(components(p.value, ';').first()).trim()
                "EMAIL" -> email(p).takeIf { it.isNotEmpty() }?.let { emails += it }
                "TEL" -> phone(p).takeIf { it.isNotEmpty() }?.let { phones += it }
                "NOTE" -> unescape(p.value).takeIf { it.isNotBlank() }?.let { notes += it }
                "PHOTO" -> if (photo.isEmpty()) photo = image(p)
                "CATEGORIES" -> components(p.value, ',').map { unescape(it).trim() }.filter { it.isNotEmpty() }.forEach { groups += it }
                "UID" -> uid = p.value.trim()
            }
        }
        val display = name.ifEmpty { structured }.ifEmpty { company }.ifEmpty { emails.firstOrNull() ?: phones.firstOrNull() ?: "" }
        require(display.isNotEmpty()) { "vCard has no name" }
        return Card(
            name = display,
            emails = emails.distinct(),
            phones = phones.distinct(),
            company = company,
            notes = notes.joinToString("\n"),
            photo = photo,
            groups = groups.toList(),
            uid = uid,
            vcard = card
        )
    }

    /**
     * [card] as a vCard of [version] (`3.0` or `4.0`), CRLF line ends.
     * Unmodelled properties of [Card.vcard] are kept; `UID` is written when
     * [Card.uid] is set and the groups become `CATEGORIES`.
     */
    fun write(card: Card, version: String = V4): String {
        require(version == V3 || version == V4) { "vCard version must be 3.0 or 4.0" }
        val source = if (card.vcard.isBlank()) emptyList() else unfold(card.vcard).mapNotNull(::property)
        val old = if (card.vcard.isBlank()) null else runCatching { parse(card.vcard) }.getOrNull()
        val from = source.firstOrNull { it.name == "VERSION" }?.value?.trim()

        val out = mutableListOf("BEGIN:VCARD", "VERSION:$version")
        if (card.uid.isNotBlank()) out += "UID:${card.uid.trim()}"
        out += "FN:${escape(card.name)}"
        val n = source.firstOrNull { it.name == "N" }
        out += if (n != null && old?.name == card.name) n.raw else "N:${structured(card.name)}"
        if (card.company.isNotBlank()) {
            val org = source.firstOrNull { it.name == "ORG" }
            out += if (org != null && old?.company == card.company) org.raw else "ORG:${escape(card.company)}"
        }
        out += kept(card.emails, source.filter { it.name == "EMAIL" }, ::email) { "EMAIL:${escape(it)}" }
        out += kept(card.phones, source.filter { it.name == "TEL" }, ::phone) { "TEL:$it" }
        if (card.notes.isNotBlank()) {
            out += if (old?.notes == card.notes) source.filter { it.name == "NOTE" }.map { it.raw } else listOf("NOTE:${escape(card.notes)}")
        }
        if (card.photo.isNotBlank()) {
            val line = source.firstOrNull { it.name == "PHOTO" }
            out += if (line != null && old?.photo == card.photo && from == version) line.raw else photo(card.photo, version)
        }
        if (card.groups.isNotEmpty()) out += "CATEGORIES:${card.groups.joinToString(",") { escape(it) }}"
        out += source.filter { it.name !in OWN }.map { it.raw }
        out += "END:VCARD"
        return out.joinToString("") { fold(it) + "\r\n" }
    }

    /** One line of [text] per element, with any line ending. */
    private fun lines(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** Logical lines: continuation lines (leading space or tab) joined to the one before. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (line in lines(text)) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out.last().append(line, 1, line.length)
            } else if (line.isNotBlank()) {
                out += StringBuilder(line)
            }
        }
        return out.map { it.toString() }
    }

    /** Splits a logical line into group, name, parameters and value; null when it has no colon. */
    private fun property(line: String): Property? {
        var quoted = false
        var colon = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) {
                colon = i
                break
            }
        }
        if (colon <= 0) return null
        val head = split(line.substring(0, colon), ';')
        val full = head.first().trim()
        val name = full.substringAfterLast('.').uppercase()
        val params = HashMap<String, MutableList<String>>()
        for (segment in head.drop(1)) {
            val key = if ('=' in segment) segment.substringBefore('=').trim().uppercase() else "TYPE"
            val values = if ('=' in segment) segment.substringAfter('=') else segment
            split(values, ',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                .forEach { params.getOrPut(key) { mutableListOf() } += it }
        }
        return Property(name, params, line.substring(colon + 1), line)
    }

    /** [text] split on [sep] outside double quotes. */
    private fun split(text: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (c == sep && !quoted) {
                out += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        out += current.toString()
        return out
    }

    /** A text value split on unescaped [sep]; the parts stay escaped. */
    private fun components(value: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in value) {
            when {
                escaped -> {
                    current.append('\\').append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == sep -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (escaped) current.append('\\')
        out += current.toString()
        return out
    }

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var escaped = false
        for (c in value) {
            if (escaped) {
                out.append(if (c == 'n' || c == 'N') '\n' else c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(",", "\\,")
        .replace(";", "\\;")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    /** `given additional family` from an `N` value. */
    private fun person(value: String): String {
        val parts = components(value, ';').map { unescape(it).replace(',', ' ').trim() }
        return listOf(parts.getOrElse(3) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(0) { "" }, parts.getOrElse(4) { "" })
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /** An `N` value from a display name: the last word is the family name. */
    private fun structured(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return "${escape(name.trim())};;;;"
        return "${escape(words.last())};${escape(words.dropLast(1).joinToString(" "))};;;"
    }

    private fun email(p: Property): String {
        val value = unescape(p.value).trim()
        return if (value.startsWith("mailto:", true)) value.substring(7) else value
    }

    private fun phone(p: Property): String {
        val value = p.value.trim()
        return if (value.startsWith("tel:", true)) value.substring(4) else value
    }

    /** A `PHOTO` value as a `data:` URI or URL. */
    private fun image(p: Property): String {
        val encoding = p.params["ENCODING"]?.firstOrNull()?.lowercase()
        if (encoding != "b" && encoding != "base64") return p.value.trim()
        val type = p.params["TYPE"]?.firstOrNull()?.lowercase() ?: "jpeg"
        val mime = if ('/' in type) type else "image/$type"
        return "data:$mime;base64,${p.value.filterNot { it.isWhitespace() }}"
    }

    /** A `PHOTO` line for [version]: 3.0 carries inline data as `ENCODING=b`. */
    private fun photo(uri: String, version: String): String {
        if (version == V4) return "PHOTO:$uri"
        val head = uri.substringBefore(',')
        if (uri.startsWith("data:", true) && head.endsWith(";base64", true)) {
            val type = head.removePrefix("data:").substringBefore(';').substringAfter('/').uppercase()
            return "PHOTO;ENCODING=b;TYPE=$type:${uri.substringAfter(',')}"
        }
        return "PHOTO;VALUE=uri:$uri"
    }

    /** One line per value: the source line that holds it when there is one, else [fresh]. */
    private fun kept(values: List<String>, lines: List<Property>, read: (Property) -> String, fresh: (String) -> String): List<String> {
        val pool = lines.toMutableList()
        return values.map { value ->
            val line = pool.firstOrNull { read(it).equals(value, true) }
            if (line != null) {
                pool.remove(line)
                line.raw
            } else {
                fresh(value)
            }
        }
    }

    /** [line] folded at 75 octets, never inside a UTF-8 sequence. */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var used = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > limit) {
                out.append("\r\n ")
                used = 1
                limit = 75
            }
            out.appendCodePoint(cp)
            used += bytes
            i += chars
        }
        return out.toString()
    }

    private class Property(
        val name: String,
        val params: Map<String, List<String>>,
        val value: String,
        /** The whole logical line, group and parameters included. */
        val raw: String
    )
}
