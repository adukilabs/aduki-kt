package pro.aduki.core.thread

import pro.aduki.core.models.Confidence
import pro.aduki.core.models.Forwarded

/**
 * Quotes finds the quoted history, forwards and signatures in plain text:
 * attribution lines ("On … wrote:" in English, French, German, Spanish,
 * Portuguese, Dutch, Italian, Swahili and Chinese), `>` blocks, Outlook
 * headers and forward dividers.
 */
internal object Quotes {

    /**
     * Cut is a quoted range [start, end) of a text. [forward] marks a
     * forwarded message (a card, never a quote).
     */
    class Cut(
        val start: Int,
        val end: Int,
        val confidence: Confidence,
        val forward: Boolean = false,
        val author: String? = null
    )

    private val I = RegexOption.IGNORE_CASE

    private val VERBS = "wrote|a écrit|a ecrit|schrieb|escribió|escribio|escreveu|schreef|ha scritto|aliandika|写道"

    /** A line ending in an attribution verb and a colon. */
    private val ATTRIBUTION = Regex("^(?:.{0,400}?(?:$VERBS)|(?:am|op|on)\\s.{0,300}?\\s(?:schrieb|schreef)\\s.{0,200}?)\\s*[:：]\\s*$", I)

    /** How an attribution wrapped over two lines starts. */
    private val OPENING = Regex("^(on|le|am|el|em|na|op|il|mnamo|siku|在)\\s.*", I)

    /** The name in an attribution: after the last comma, before the address and the verb. */
    private val AUTHOR = Regex("(?:^|,)\\s*([^,<>]{1,80}?)\\s*(?:<[^>]*>|\\[[^\\]]*\\]|\\([^)]*\\))?\\s*(?:$VERBS)\\s*[:：]\\s*$", I)

    private val ORIGINAL = Regex(
        "^\\s*-{2,}\\s*(original message|ursprüngliche nachricht|message d'origine|mensaje original|mensagem original|" +
            "oorspronkelijk bericht|messaggio originale|ujumbe wa awali|原始邮件)\\s*-{2,}\\s*$",
        I
    )

    private val FORWARD = Regex(
        "^\\s*(-{2,}\\s*(forwarded message|message transféré|message transfere|weitergeleitete nachricht|mensaje reenviado|" +
            "mensagem encaminhada|doorgestuurd bericht|messaggio inoltrato|ujumbe uliotumwa|转发的邮件|轉寄的郵件)\\s*-{2,}|" +
            "begin forwarded message\\s*:|début du message réexpédié\\s*:|anfang der weitergeleiteten nachricht\\s*:|" +
            "inicio del mensaje reenviado\\s*:|início da mensagem reencaminhada\\s*:|begin doorgestuurd bericht\\s*:|" +
            "inizio messaggio inoltrato\\s*:)\\s*$",
        I
    )

    private val HEADER_FROM = Regex("^\\s*\\*?(from|de|von|van|da|kutoka|发件人|寄件者)\\s*\\*?\\s*[:：]\\s*\\*?\\s*(.*)$", I)
    private val HEADER_DATE = Regex("^\\s*\\*?(sent|date|envoyé|envoye|gesendet|enviado|enviada|fecha|data|verzonden|datum|inviato|imetumwa|发送时间|日期|時間)\\s*\\*?\\s*[:：]\\s*\\*?\\s*(.*)$", I)
    private val HEADER_SUBJECT = Regex("^\\s*\\*?(subject|objet|betreff|asunto|assunto|onderwerp|oggetto|kichwa|主题|主旨)\\s*\\*?\\s*[:：]\\s*\\*?\\s*(.*)$", I)
    private val HEADER_TO = Regex("^\\s*\\*?(to|cc|à|a|an|para|aan|kwa|收件人|抄送)\\s*\\*?\\s*[:：].*$", I)
    private val UNDERSCORES = Regex("^\\s*_{10,}\\s*$")

    private val FOOTERS = Regex(
        "^\\s*(sent from my \\S.*|sent from (yahoo mail|mail for windows|outlook|proton mail).*|get outlook for \\S.*|" +
            "envoyé de mon \\S.*|envoye de mon \\S.*|von meinem \\S.* gesendet|gesendet von meinem \\S.*|enviado desde mi \\S.*|" +
            "enviado do meu \\S.*|enviado de meu \\S.*|verstuurd vanaf mijn \\S.*|inviato da(l mio)? \\S.*|imetumwa kutoka \\S.*|" +
            "发自我的\\S*|從我的 ?\\S* ?傳送)\\s*$",
        I
    )

    /** Lines of [text] with the offset each starts at. */
    class Lines(val text: String) {
        val starts: IntArray
        val lines: List<String>

        init {
            val s = ArrayList<Int>()
            val l = ArrayList<String>()
            var from = 0
            while (true) {
                val nl = text.indexOf('\n', from)
                s += from
                if (nl < 0) {
                    l += text.substring(from).trimEnd('\r')
                    break
                }
                l += text.substring(from, nl).trimEnd('\r')
                from = nl + 1
            }
            starts = s.toIntArray()
            lines = l
        }

        val size get() = lines.size
    }

    private fun quoted(line: String): Boolean = line.trimStart(' ').startsWith(">")

    /** The earliest quoted history or forward in [text], or null. */
    fun cut(text: String, budget: Budget): Cut? {
        if (text.isBlank()) return null
        val l = Lines(text)
        val found = listOfNotNull(
            attribution(l, budget),
            original(l, budget),
            header(l, budget),
            forward(l, budget),
            trailing(l, budget)
        )
        return found.minByOrNull { it.start } ?: leading(l, budget)
    }

    private fun attribution(l: Lines, budget: Budget): Cut? {
        for (i in 0 until l.size) {
            if (i and 63 == 0) budget.check()
            val line = l.lines[i].trim()
            if (line.isEmpty() || quoted(line) || line.length > 400) continue
            val joined = when {
                ATTRIBUTION.matches(line) -> line
                i + 1 < l.size && OPENING.matches(line) && l.lines[i + 1].trim().length <= 120 &&
                    ATTRIBUTION.matches("$line ${l.lines[i + 1].trim()}") -> "$line ${l.lines[i + 1].trim()}"
                else -> continue
            }
            val next = (i + 1 until l.size).map { l.lines[it] }.drop(if (joined === line) 0 else 1).firstOrNull { it.isNotBlank() }
            val confidence = if (next != null && quoted(next)) Confidence.High else Confidence.Medium
            val author = AUTHOR.find(joined)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.ifBlank { null }
            return Cut(l.starts[i], l.text.length, confidence, author = author)
        }
        return null
    }

    private fun original(l: Lines, budget: Budget): Cut? {
        for (i in 0 until l.size) {
            if (i and 63 == 0) budget.check()
            if (ORIGINAL.matches(l.lines[i])) return Cut(l.starts[i], l.text.length, Confidence.High)
        }
        return null
    }

    // Outlook's header block: From:, then Sent:/Date: and To:/Subject: within a few lines.
    private fun header(l: Lines, budget: Budget): Cut? {
        for (i in 0 until l.size) {
            if (i and 63 == 0) budget.check()
            if (!HEADER_FROM.matches(l.lines[i])) continue
            val block = (i + 1..minOf(i + 5, l.size - 1)).map { l.lines[it] }
            if (block.none { HEADER_DATE.matches(it) }) continue
            if (block.none { HEADER_TO.matches(it) || HEADER_SUBJECT.matches(it) }) continue
            // Part of a forward: the forward divider above decides.
            val above = (i - 1 downTo maxOf(0, i - 2)).map { l.lines[it] }.firstOrNull { it.isNotBlank() }
            if (above != null && FORWARD.matches(above)) continue
            val start = if (above != null && UNDERSCORES.matches(above)) l.starts[l.lines.lastIndexOf(above).coerceAtMost(i)] else l.starts[i]
            val name = HEADER_FROM.find(l.lines[i])?.groupValues?.get(2)?.substringBefore('<')?.substringBefore('[')?.trim()?.trim('"', '*')?.ifBlank { null }
            return Cut(minOf(start, l.starts[i]), l.text.length, Confidence.High, author = name)
        }
        return null
    }

    private fun forward(l: Lines, budget: Budget): Cut? {
        for (i in 0 until l.size) {
            if (i and 63 == 0) budget.check()
            if (FORWARD.matches(l.lines[i])) return Cut(l.starts[i], l.text.length, Confidence.High, forward = true)
        }
        return null
    }

    // A '>' block that runs to the end of the text.
    private fun trailing(l: Lines, budget: Budget): Cut? {
        var first = -1
        for (i in l.size - 1 downTo 0) {
            if (i and 63 == 0) budget.check()
            val line = l.lines[i]
            if (line.isBlank()) continue
            if (!quoted(line)) break
            first = i
        }
        if (first < 0) return null
        return Cut(l.starts[first], l.text.length, Confidence.Medium)
    }

    // A '>' block at the top followed by own words with no more quotes (bottom posting).
    private fun leading(l: Lines, budget: Budget): Cut? {
        var last = -1
        for (i in 0 until l.size) {
            if (i and 63 == 0) budget.check()
            val line = l.lines[i]
            if (line.isBlank()) continue
            if (!quoted(line)) break
            last = i
        }
        if (last < 0) return null
        val rest = (last + 1 until l.size).map { l.lines[it] }
        if (rest.any { quoted(it) } || rest.all { it.isBlank() }) return null
        return Cut(0, l.starts[last + 1], Confidence.Medium)
    }

    /** The forwarded message in [block] (starting at its divider). */
    fun forwarded(block: String): Forwarded {
        val lines = Lines(block).lines.drop(1).let { lines ->
            // Apple quotes a plain forward with '>'.
            if (lines.filter { it.isNotBlank() }.all { quoted(it) }) lines.map { it.trimStart(' ').removePrefix(">").removePrefix(" ") } else lines
        }
        var from: String? = null
        var date: String? = null
        var subject: String? = null
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            when {
                from == null && HEADER_FROM.matches(line) -> from = HEADER_FROM.find(line)?.groupValues?.get(2)?.trim()
                date == null && HEADER_DATE.matches(line) -> date = HEADER_DATE.find(line)?.groupValues?.get(2)?.trim()
                subject == null && HEADER_SUBJECT.matches(line) -> subject = HEADER_SUBJECT.find(line)?.groupValues?.get(2)?.trim()
                HEADER_TO.matches(line) || line.trim().startsWith("Reply-To", ignoreCase = true) -> Unit
                else -> break
            }
            i++
        }
        val headers = from != null || date != null || subject != null
        val text = (if (headers) lines.drop(i) else lines).joinToString("\n").trim()
        return Forwarded(from?.ifBlank { null }, date?.ifBlank { null }, subject?.ifBlank { null }, text)
    }

    /** The trailing signature of [text] (`-- ` delimiter or a mobile footer): (own, signature), or null. */
    fun signature(text: String): Pair<String, String>? {
        val l = Lines(text)
        for (i in l.size - 1 downTo maxOf(0, l.size - 16)) {
            val line = l.lines[i]
            if (line == "-- " || line == "--" || line == "—" || line == "-- -") {
                val own = text.substring(0, l.starts[i]).trimEnd()
                val sig = text.substring(minOf(text.length, l.starts[i] + line.length)).trim()
                if (own.isNotBlank() && sig.isNotBlank()) return own to sig
            }
        }
        val last = (l.size - 1 downTo 0).firstOrNull { l.lines[it].isNotBlank() } ?: return null
        if (FOOTERS.matches(l.lines[last])) {
            val own = text.substring(0, l.starts[last]).trimEnd()
            if (own.isNotBlank()) return own to l.lines[last].trim()
        }
        return null
    }

    /** The last non-blank line of [text] is an attribution: [text] without it. */
    fun dropAttribution(text: String): String {
        val trimmed = text.trimEnd()
        val l = Lines(trimmed)
        val last = l.size - 1
        if (last < 0) return trimmed
        if (ATTRIBUTION.matches(l.lines[last].trim())) return trimmed.substring(0, l.starts[last]).trimEnd()
        if (last >= 1 && OPENING.matches(l.lines[last - 1].trim()) && ATTRIBUTION.matches("${l.lines[last - 1].trim()} ${l.lines[last].trim()}")) {
            return trimmed.substring(0, l.starts[last - 1]).trimEnd()
        }
        return trimmed
    }

    /** The author named by an attribution line in [text], if its first line is one. */
    fun author(text: String): String? {
        val first = Lines(text.trimStart()).lines.take(2)
        val line = first.firstOrNull()?.trim() ?: return null
        val joined = if (ATTRIBUTION.matches(line)) line else if (first.size > 1) "$line ${first[1].trim()}" else return null
        if (!ATTRIBUTION.matches(joined)) return null
        return AUTHOR.find(joined)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.ifBlank { null }
    }
}
