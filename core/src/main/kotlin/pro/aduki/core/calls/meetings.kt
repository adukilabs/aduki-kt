package pro.aduki.core.calls

/**
 * Meeting codes and links of Aduki Calls.
 *
 * A meeting or room made since letter codes has a code of ten letters in
 * groups of three, four and three (`abc-defg-hij`); older calls are named by
 * their sixteen-hex id. Both forms keep working everywhere Calls takes a
 * meeting. The link people share is `https://calls.aduki.pro/call/<code>`.
 */
object Meetings {
    /** The Calls web origin the links point at. */
    const val ORIGIN = "https://calls.aduki.pro"

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("[\\s._-]+")
    private val LETTERS = Regex("^[a-z]{10}$")
    private val HEX = Regex("^[0-9a-f]{16}$")

    /**
     * The meeting code in [text] in the form Calls takes, or null when it
     * holds none. Accepts a letter code in any case, with or without spaces,
     * dashes, dots or underscores; a sixteen-hex id; or a whole meeting link
     * (with or without the scheme, with a query or a fragment).
     */
    fun code(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val at = raw.indexOf("/call/", ignoreCase = true)
        var part = when {
            at >= 0 -> raw.substring(at + "/call/".length)
            SCHEME.containsMatchIn(raw) || raw.startsWith("/") ->
                raw.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            else -> raw
        }
        part = part.substringBefore('?').substringBefore('#').trimEnd('/').substringBefore('/')
        part = part.replace(SEPARATORS, "").lowercase()
        return when {
            LETTERS.matches(part) -> "${part.substring(0, 3)}-${part.substring(3, 7)}-${part.substring(7)}"
            HEX.matches(part) -> part
            else -> null
        }
    }

    /** [code] or an `IllegalArgumentException` naming [what]. */
    fun require(code: String, what: String = "meeting"): String =
        code(code) ?: throw IllegalArgumentException("$what is not a meeting code (abc-defg-hij, or 16 hex digits)")

    /** The link to share for meeting [code] (any form [code] reads), on [origin]. */
    fun link(code: String, origin: String = ORIGIN): String = "${origin.trimEnd('/')}/call/${require(code)}"
}
