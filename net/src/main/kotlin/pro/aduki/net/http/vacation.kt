package pro.aduki.net.http

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Vacation is the account's automatic reply (JMAP `VacationResponse`,
 * RFC 8621 section 8). While [enabled] and between [from] and [to] (either
 * open when null), the server answers incoming mail with
 * [subject] and [text] (and [html], if set).
 */
data class Vacation(
    val enabled: Boolean = false,
    val from: Instant? = null,
    val to: Instant? = null,
    val subject: String? = null,
    val text: String? = null,
    val html: String? = null
) {
    /** Throws [IllegalArgumentException] when it ends before it starts or has no body. */
    fun validate() {
        require(from == null || to == null || !to.isBefore(from)) { "the reply ends before it starts" }
        require(!enabled || !text.isNullOrBlank() || !html.isNullOrBlank()) { "an enabled reply needs a body" }
    }

    internal fun json(): JSONObject = JSONObject()
        .put("isEnabled", enabled)
        .put("fromDate", from?.let(::date) ?: JSONObject.NULL)
        .put("toDate", to?.let(::date) ?: JSONObject.NULL)
        .put("subject", subject ?: JSONObject.NULL)
        .put("textBody", text ?: JSONObject.NULL)
        .put("htmlBody", html ?: JSONObject.NULL)

    internal companion object {
        // JMAP UTCDate: whole seconds, `Z`.
        fun date(at: Instant): String = at.truncatedTo(ChronoUnit.SECONDS).toString()

        fun from(obj: JSONObject) = Vacation(
            enabled = obj.optBoolean("isEnabled"),
            from = text(obj, "fromDate")?.let(Instant::parse),
            to = text(obj, "toDate")?.let(Instant::parse),
            subject = text(obj, "subject"),
            text = text(obj, "textBody"),
            html = text(obj, "htmlBody")
        )

        private fun text(obj: JSONObject, key: String): String? =
            if (obj.isNull(key)) null else obj.optString(key)
    }
}

/**
 * Responder reads and writes the account's [Vacation] over JMAP
 * (`VacationResponse/get` and `/set` on the `singleton` object). Calls are
 * blocking; run them off the main thread.
 */
class Responder(private val jmap: Jmap) {

    private val using = setOf(Jmap.CORE, Jmap.VACATION)

    /** The current reply; a disabled, empty one when none was ever set. */
    fun fetch(): Vacation {
        val reply = jmap.call("VacationResponse/get", JSONObject().put("ids", JSONArray(listOf(SINGLETON))), using)
        val obj = reply.optJSONArray("list")?.optJSONObject(0) ?: return Vacation()
        return Vacation.from(obj)
    }

    /** Replaces the reply with [vacation]. */
    fun save(vacation: Vacation) {
        vacation.validate()
        val args = JSONObject().put("update", JSONObject().put(SINGLETON, vacation.json()))
        refused(jmap.call("VacationResponse/set", args, using), SINGLETON)
    }

    private companion object {
        const val SINGLETON = "singleton"
    }
}
