package pro.aduki.net.http

import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Address

/**
 * Sender is an address the account may send as, with its display [name]
 * and signature (JMAP `Identity`, RFC 8621 section 6). [signature] is the
 * plain-text signature, [html] the HTML one; [reply] and [bcc] are added to
 * mail sent as it.
 *
 * [main] marks the account's own login address, which cannot be removed.
 * [default] marks the sender new mail starts from: the `isDefault`
 * extension property when the server sends it, else the [main] one (the
 * Aduki mail host does not keep a default yet; see [Senders.prefer]).
 */
data class Sender(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val signature: String = "",
    val html: String = "",
    val reply: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val main: Boolean = false,
    val default: Boolean = main
) {
    /** Throws [IllegalArgumentException] for a missing or malformed address. */
    fun validate() {
        require(email.contains('@') && email.none { it.isWhitespace() || it in "<>,;\"" }) { "not an address: $email" }
        (reply + bcc).forEach { require(it.email.contains('@')) { "not an address: ${it.email}" } }
    }

    // The properties a client may write; `email` only on create.
    internal fun json(create: Boolean): JSONObject = JSONObject()
        .apply { if (create) put("email", email) }
        .put("name", name)
        .put("textSignature", signature)
        .put("htmlSignature", html)
        .put("replyTo", addresses(reply))
        .put("bcc", addresses(bcc))

    internal companion object {
        fun from(obj: JSONObject) = Sender(
            id = obj.getString("id"),
            email = obj.optString("email"),
            name = obj.optString("name"),
            signature = if (obj.isNull("textSignature")) "" else obj.optString("textSignature"),
            html = if (obj.isNull("htmlSignature")) "" else obj.optString("htmlSignature"),
            reply = addresses(obj.optJSONArray("replyTo")),
            bcc = addresses(obj.optJSONArray("bcc")),
            main = !obj.optBoolean("mayDelete", true),
            default = obj.optBoolean("isDefault", !obj.optBoolean("mayDelete", true))
        )

        private fun addresses(list: List<Address>): Any =
            if (list.isEmpty()) JSONObject.NULL
            else JSONArray(list.map { JSONObject().put("name", it.name ?: JSONObject.NULL).put("email", it.email) })

        private fun addresses(list: JSONArray?): List<Address> = list?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                Address(if (it.isNull("name")) null else it.optString("name"), it.optString("email"))
            }
        } ?: emptyList()
    }
}

/**
 * Senders lists and edits the account's [Sender]s over JMAP
 * (`Identity/get` and `Identity/set`). The server accepts only addresses
 * the account may send as (its own, its aliases, and addresses delegated
 * to it), refuses others with `forbiddenFrom`, and caps the number of
 * senders by plan (`overQuota`). An address never changes: remove the
 * sender and create another. Calls are blocking; run them off the main
 * thread.
 */
class Senders(private val jmap: Jmap) {

    private val using = setOf(Jmap.CORE, Jmap.MAIL, Jmap.SUBMISSION)

    /** Every sender, the [Sender.default] one first. */
    fun fetchAll(): List<Sender> {
        val list = jmap.call("Identity/get", JSONObject(), using).optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).map { Sender.from(list.getJSONObject(it)) }.sortedWith(compareByDescending<Sender> { it.default }.thenByDescending { it.main })
    }

    /** Adds [sender] and returns it with its server id. */
    fun create(sender: Sender): Sender {
        sender.validate()
        val args = JSONObject().put("create", JSONObject().put("s", sender.json(create = true)))
        val reply = jmap.call("Identity/set", args, using)
        refused(reply, "s")
        val made = reply.optJSONObject("created")?.optJSONObject("s")
            ?: throw AdukiException.Protocol("Identity/set did not create the sender")
        val main = !made.optBoolean("mayDelete", true)
        return sender.copy(id = made.getString("id"), main = main, default = made.optBoolean("isDefault", main))
    }

    /** Saves the name, signatures, reply-to and bcc of [sender] (by id). */
    fun update(sender: Sender) {
        require(sender.id.isNotBlank()) { "the sender has no id" }
        sender.validate()
        val args = JSONObject().put("update", JSONObject().put(sender.id, sender.json(create = false)))
        refused(jmap.call("Identity/set", args, using), sender.id)
    }

    /**
     * Makes the sender [id] the default one (`Identity/set` with the
     * `isDefault` extension: setting it on one clears it on the others).
     * Proposed server contract: the Aduki mail host does not support it yet
     * and refuses it with `invalidProperties`.
     */
    fun prefer(id: String) {
        val args = JSONObject().put("update", JSONObject().put(id, JSONObject().put("isDefault", true)))
        refused(jmap.call("Identity/set", args, using), id)
    }

    /** Removes the sender [id]; the [Sender.main] one is refused (`forbidden`). */
    fun remove(id: String) {
        val args = JSONObject().put("destroy", JSONArray().put(id))
        refused(jmap.call("Identity/set", args, using), id)
    }
}
