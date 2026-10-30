package pro.aduki.core.vcard

import pro.aduki.core.models.Card
import pro.aduki.core.models.Link
import pro.aduki.core.models.Postal

/**
 * Vcard reads vCard 2.1, 3.0 and 4.0, and writes vCard 3.0 (RFC 2426) and 4.0 (RFC 6350).
 *
 * Reading unfolds lines (honouring Quoted-Printable soft breaks and indented continuation),
 * decodes Quoted-Printable values honouring CHARSET, accepts bare parameters as types (2.1),
 * honours property groups (`item1.EMAIL`), quoted parameters and text escapes, and turns inline photos
 * (`PHOTO;ENCODING=b;TYPE=JPEG:...` or `PHOTO;ENCODING=BASE64;JPEG:...`) into `data:` URIs.
 * Writing produces 3.0 or 4.0 vCards only, keeping every property it does not model from the card's
 * source vCard, and keeping the source line (with its `TYPE` and other parameters) of each e-mail
 * address and phone number that is still present. Lines are folded at 75 octets.
 */
object Vcard {
    const val V2 = "2.1"
    const val V3 = "3.0"
    const val V4 = "4.0"

    // Properties [write] produces itself; anything else is copied from the source.
    private val OWN = setOf(
        "BEGIN", "END", "VERSION", "UID", "FN", "N", "ORG", "EMAIL", "TEL", "NOTE", "PHOTO", "CATEGORIES",
        "ADR", "TITLE", "BDAY", "URL", "NICKNAME", "X-ADUKI-COLLECTED", "X-COLLECTED"
    )

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
        require(version == V2 || version == V3 || version == V4) { "unsupported vCard version '$version' (2.1, 3.0 and 4.0 are read)" }

        var name = ""
        var structured = ""
        var company = ""
        var photo = ""
        var uid = ""
        var title = ""
        var birthday = ""
        var nickname = ""
        var collected = false
        val emails = mutableListOf<String>()
        val phones = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val groups = LinkedHashSet<String>()
        val addresses = mutableListOf<Postal>()
        val urls = mutableListOf<Link>()
        for (p in props) {
            when (p.name) {
                "FN" -> if (name.isEmpty()) name = unescape(p.value).trim()
                "N" -> if (structured.isEmpty()) structured = person(p.value)
                "ORG" -> if (company.isEmpty()) company = unescape(components(p.value, ';').first()).trim()
                "TITLE" -> if (title.isEmpty()) title = unescape(p.value).trim()
                "BDAY" -> if (birthday.isEmpty()) birthday = p.value.trim()
                "NICKNAME" -> if (nickname.isEmpty()) nickname = unescape(p.value).trim()
                "EMAIL" -> email(p).takeIf { it.isNotEmpty() }?.let { emails += it }
                "TEL" -> phone(p).takeIf { it.isNotEmpty() }?.let { phones += it }
                "NOTE" -> unescape(p.value).takeIf { it.isNotBlank() }?.let { notes += it }
                "PHOTO" -> if (photo.isEmpty()) photo = image(p)
                "CATEGORIES" -> components(p.value, ',').map { unescape(it).trim() }.filter { it.isNotEmpty() }.forEach { groups += it }
                "ADR" -> address(p)?.let { addresses += it }
                "URL" -> link(p)?.let { urls += it }
                "X-ADUKI-COLLECTED", "X-COLLECTED" -> {
                    val v = p.value.trim()
                    if (v.equals("TRUE", true) || v == "1") collected = true
                }
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
            addresses = addresses.distinct(),
            title = title,
            birthday = birthday,
            urls = urls.distinct(),
            nickname = nickname,
            collected = collected,
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
        out += if (from != V2 && n != null && old?.name == card.name) {
            n.raw
        } else if (from == V2 && n != null && old?.name == card.name) {
            "N:${components(n.value, ';').joinToString(";") { escape(it) }}"
        } else {
            "N:${structured(card.name)}"
        }
        if (card.company.isNotBlank()) {
            val org = source.firstOrNull { it.name == "ORG" }
            out += if (from != V2 && org != null && old?.company == card.company) org.raw else "ORG:${escape(card.company)}"
        }
        if (card.title.isNotBlank()) {
            val t = source.firstOrNull { it.name == "TITLE" }
            out += if (from != V2 && t != null && old?.title == card.title) t.raw else "TITLE:${escape(card.title)}"
        }
        if (card.birthday.isNotBlank()) {
            val b = source.firstOrNull { it.name == "BDAY" }
            out += if (from != V2 && b != null && old?.birthday == card.birthday) b.raw else "BDAY:${escape(card.birthday)}"
        }
        if (card.nickname.isNotBlank()) {
            val nick = source.firstOrNull { it.name == "NICKNAME" }
            out += if (from != V2 && nick != null && old?.nickname == card.nickname) nick.raw else "NICKNAME:${escape(card.nickname)}"
        }
        out += kept(card.emails, source.filter { it.name == "EMAIL" }, ::email, from) { "EMAIL:${escape(it)}" }
        out += kept(card.phones, source.filter { it.name == "TEL" }, ::phone, from) { "TEL:$it" }
        out += keptPostal(card.addresses, source.filter { it.name == "ADR" }, from)
        out += keptLink(card.urls, source.filter { it.name == "URL" }, from)
        if (card.notes.isNotBlank()) {
            out += if (from != V2 && old?.notes == card.notes) source.filter { it.name == "NOTE" }.map { it.raw } else listOf("NOTE:${escape(card.notes)}")
        }
        if (card.photo.isNotBlank()) {
            val line = source.firstOrNull { it.name == "PHOTO" }
            out += if (line != null && old?.photo == card.photo && from == version) line.raw else photo(card.photo, version)
        }
        if (card.groups.isNotEmpty()) out += "CATEGORIES:${card.groups.joinToString(",") { escape(it) }}"
        if (card.collected) out += "X-ADUKI-COLLECTED:TRUE"
        out += source.filter { it.name !in OWN }.map { p ->
            if (from == V2 && p.params["ENCODING"]?.any { it.equals("QUOTED-PRINTABLE", true) } == true) {
                "${p.name}:${escape(p.value)}"
            } else {
                p.raw
            }
        }
        out += "END:VCARD"
        return out.joinToString("") { fold(it) + "\r\n" }
    }

    /** One line of [text] per element, with any line ending. */
    private fun lines(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** Logical lines: continuation lines (leading space or tab, or Quoted-Printable '=' soft breaks) joined to the one before. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (line in lines(text)) {
            val prev = out.lastOrNull()
            val isQpContinuation = prev != null &&
                prev.contains("QUOTED-PRINTABLE", ignoreCase = true) &&
                prev.trimEnd().endsWith("=")
            val isIndented = line.startsWith(" ") || line.startsWith("\t")

            if (isQpContinuation) {
                val trimmed = prev.trimEnd()
                val eqIndex = trimmed.lastIndexOf('=')
                prev.setLength(eqIndex)
                val continuation = if (isIndented) line.trimStart() else line
                prev.append(continuation)
            } else if (isIndented && out.isNotEmpty()) {
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
            val upper = segment.trim().uppercase()
            val (key, values) = if ('=' in segment) {
                segment.substringBefore('=').trim().uppercase() to segment.substringAfter('=')
            } else when (upper) {
                "BASE64", "QUOTED-PRINTABLE", "7BIT", "8BIT" -> "ENCODING" to segment
                "INLINE" -> "VALUE" to segment
                else -> "TYPE" to segment
            }
            split(values, ',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                .forEach { params.getOrPut(key) { mutableListOf() } += it }
        }
        val rawValue = line.substring(colon + 1)
        val value = if (params["ENCODING"]?.any { it.equals("QUOTED-PRINTABLE", true) } == true) {
            decodeQuotedPrintable(rawValue, params["CHARSET"]?.firstOrNull())
        } else {
            rawValue
        }
        return Property(name, params, value, line)
    }

    private fun decodeQuotedPrintable(text: String, charsetName: String? = null): String {
        val charset = try {
            if (!charsetName.isNullOrBlank()) java.nio.charset.Charset.forName(charsetName.trim()) else Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (c == '=') {
                if (i + 2 < len && isHex(text[i + 1]) && isHex(text[i + 2])) {
                    val b = text.substring(i + 1, i + 3).toInt(16)
                    bytes.write(b)
                    i += 3
                } else if (i + 1 < len && (text[i + 1] == '\r' || text[i + 1] == '\n')) {
                    i++
                    if (i < len && text[i] == '\r') i++
                    if (i < len && text[i] == '\n') i++
                } else if (i + 1 == len) {
                    i++
                } else {
                    bytes.write('='.code)
                    i++
                }
            } else if (c.code <= 127) {
                bytes.write(c.code)
                i++
            } else {
                bytes.write(c.toString().toByteArray(charset))
                i++
            }
        }
        return bytes.toByteArray().toString(charset)
    }

    private fun isHex(c: Char): Boolean =
        (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

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
        return out.toString().replace("\r\n", "\n").replace('\r', '\n')
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
    private fun kept(
        values: List<String>,
        lines: List<Property>,
        read: (Property) -> String,
        from: String? = null,
        fresh: (String) -> String
    ): List<String> {
        val pool = lines.toMutableList()
        return values.map { value ->
            val line = pool.firstOrNull { read(it).equals(value, true) }
            if (line != null) {
                pool.remove(line)
                if (from == V2) {
                    val types = line.params["TYPE"]?.filter { it.isNotBlank() }
                    if (!types.isNullOrEmpty()) {
                        "${line.name};TYPE=${types.joinToString(",")}:$value"
                    } else {
                        "${line.name}:$value"
                    }
                } else {
                    line.raw
                }
            } else {
                fresh(value)
            }
        }
    }

    private fun address(p: Property): Postal? {
        val parts = components(p.value, ';').map { unescape(it).trim() }
        val pobox = parts.getOrElse(0) { "" }
        val ext = parts.getOrElse(1) { "" }
        val street = parts.getOrElse(2) { "" }
        val locality = parts.getOrElse(3) { "" }
        val region = parts.getOrElse(4) { "" }
        val code = parts.getOrElse(5) { "" }
        val country = parts.getOrElse(6) { "" }
        if (pobox.isEmpty() && ext.isEmpty() && street.isEmpty() && locality.isEmpty() && region.isEmpty() && code.isEmpty() && country.isEmpty()) {
            return null
        }
        val type = p.params["TYPE"]?.firstOrNull()?.lowercase() ?: "home"
        return Postal(
            street = street,
            locality = locality,
            region = region,
            code = code,
            country = country,
            pobox = pobox,
            ext = ext,
            type = type
        )
    }

    private fun link(p: Property): Link? {
        val value = unescape(p.value).trim()
        if (value.isEmpty()) return null
        val type = p.params["TYPE"]?.firstOrNull()?.lowercase() ?: "home"
        return Link(url = value, type = type)
    }

    private fun keptPostal(
        addresses: List<Postal>,
        lines: List<Property>,
        from: String? = null
    ): List<String> {
        val pool = lines.toMutableList()
        return addresses.map { addr ->
            val line = pool.firstOrNull { address(it) == addr }
            if (line != null && from != V2) {
                pool.remove(line)
                line.raw
            } else {
                if (line != null) pool.remove(line)
                val type = if (addr.type.isNotBlank()) ";TYPE=${addr.type.uppercase()}" else ""
                "ADR$type:${escape(addr.pobox)};${escape(addr.ext)};${escape(addr.street)};${escape(addr.locality)};${escape(addr.region)};${escape(addr.code)};${escape(addr.country)}"
            }
        }
    }

    private fun keptLink(
        links: List<Link>,
        lines: List<Property>,
        from: String? = null
    ): List<String> {
        val pool = lines.toMutableList()
        return links.map { l ->
            val line = pool.firstOrNull { link(it) == l }
            if (line != null && from != V2) {
                pool.remove(line)
                line.raw
            } else {
                if (line != null) pool.remove(line)
                val type = if (l.type.isNotBlank() && !l.type.equals("home", true)) ";TYPE=${l.type.uppercase()}" else ""
                "URL$type:${escape(l.url)}"
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
