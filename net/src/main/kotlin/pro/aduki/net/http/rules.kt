package pro.aduki.net.http

import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import java.security.SecureRandom

/**
 * Rule is one server-side filter rule: when its [conditions] match an
 * incoming message ([all] of them, else any), its actions run. Rules run in
 * list order; a matching rule with [stop] ends the run.
 *
 * Actions: [move] files the message into the mailbox of that name (a path
 * such as `Work/Invoices`, created when missing); [read] and [flag] set
 * `\Seen` and `\Flagged`; [delete] discards it. A rule that is not [active]
 * is kept but never runs.
 */
data class Rule(
    val id: String = Sieve.id(),
    val name: String = "",
    val conditions: List<Condition> = emptyList(),
    val all: Boolean = true,
    val move: String? = null,
    val read: Boolean = false,
    val flag: Boolean = false,
    val delete: Boolean = false,
    val stop: Boolean = true,
    val active: Boolean = true
) {
    /** Throws [IllegalArgumentException] for a rule the server could not run. */
    fun validate() {
        require(id.isNotBlank() && id.none { it.isISOControl() }) { "rule id is blank" }
        require(conditions.isNotEmpty()) { "rule $id has no conditions" }
        conditions.forEach { it.validate() }
        require(move == null || move.isNotBlank()) { "rule $id moves to a blank mailbox" }
        require(move?.none { it.isISOControl() } ?: true) { "rule $id: mailbox has control characters" }
        require(move != null || read || flag || delete) { "rule $id has no action" }
        require(!delete || (move == null && !read && !flag)) { "rule $id deletes and also moves or flags" }
    }

    companion object {
        internal fun from(obj: JSONObject) = Rule(
            id = obj.getString("id"),
            name = obj.optString("name"),
            conditions = obj.optJSONArray("conditions")?.let { list ->
                (0 until list.length()).map { Condition.from(list.getJSONObject(it)) }
            } ?: emptyList(),
            all = obj.optBoolean("all", true),
            move = obj.optString("move").ifBlank { null },
            read = obj.optBoolean("read"),
            flag = obj.optBoolean("flag"),
            delete = obj.optBoolean("delete"),
            stop = obj.optBoolean("stop", true),
            active = obj.optBoolean("active", true)
        )
    }

    internal fun json(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("conditions", JSONArray(conditions.map { it.json() }))
        .put("all", all)
        .put("move", move ?: JSONObject.NULL)
        .put("read", read)
        .put("flag", flag)
        .put("delete", delete)
        .put("stop", stop)
        .put("active", active)
}

/**
 * Condition is one test of a [Rule]: the [field] contains [value], ignoring
 * case. [Field.ATTACHMENT] ignores [value].
 */
data class Condition(val field: Field, val value: String = "") {

    /** What a condition looks at. */
    enum class Field {
        /** The `From` header (name and address). */
        SENDER,
        /** The `To` and `Cc` headers. */
        RECIPIENT,
        SUBJECT,
        /** A part with `Content-Disposition: attachment`. */
        ATTACHMENT
    }

    /** Throws [IllegalArgumentException] for an empty or multi-line value. */
    fun validate() {
        if (field == Field.ATTACHMENT) return
        require(value.isNotBlank()) { "a ${field.name.lowercase()} condition needs a value" }
        require(value.none { it.isISOControl() }) { "a condition value has control characters" }
    }

    internal fun json(): JSONObject = JSONObject().put("field", field.name.lowercase()).put("value", value)

    internal companion object {
        fun from(obj: JSONObject) = Condition(
            Field.valueOf(obj.getString("field").uppercase()),
            obj.optString("value")
        )
    }
}

/**
 * Sieve turns rules into a Sieve script (RFC 5228, with `fileinto`,
 * `imap4flags` and `body`) and back. The rules travel in the script's
 * first line as a JSON comment, so they read back exactly; the code below
 * it is what the server runs.
 */
object Sieve {
    /** The name of the script the SDK owns; other scripts are left alone. */
    const val NAME = "aduki"

    private const val MARK = "# aduki-rules 1 "
    private val random = SecureRandom()

    /** A new rule id: 16 random hex digits. */
    fun id(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** The script for [rules], in order. Inactive rules are kept in the header only. */
    fun build(rules: List<Rule>): String {
        rules.forEach { it.validate() }
        require(rules.map { it.id }.toSet().size == rules.size) { "rule ids are not unique" }
        val header = JSONObject().put("rules", JSONArray(rules.map { it.json() }))
        val out = StringBuilder()
        out.append(MARK).append(header.toString()).append('\n')
        out.append("# Written by the Aduki SDK from the rules above; edits below are overwritten.\n")
        out.append("require [\"fileinto\", \"imap4flags\", \"body\"];\n")
        for (rule in rules.filter { it.active }) {
            out.append('\n')
            val tests = rule.conditions.map(::test)
            val test = if (tests.size == 1) tests[0] else "${if (rule.all) "allof" else "anyof"} (${tests.joinToString(", ")})"
            out.append("if ").append(test).append(" {\n")
            // `addflag` sets the flags of every copy filed from here on,
            // the implicit keep (the inbox) included.
            val flags = listOfNotNull("\\Seen".takeIf { rule.read }, "\\Flagged".takeIf { rule.flag })
            if (flags.isNotEmpty()) out.append("    addflag ").append(list(flags)).append(";\n")
            if (rule.delete) out.append("    discard;\n")
            rule.move?.let { out.append("    fileinto ").append(quote(it)).append(";\n") }
            if (rule.stop) out.append("    stop;\n")
            out.append("}\n")
        }
        return out.toString()
    }

    /**
     * The rules in a script written by [build], or null when [script] has no
     * rules header (it was not written by the SDK).
     */
    fun parse(script: String): List<Rule>? {
        val line = script.lineSequence().firstOrNull { it.startsWith(MARK) } ?: return null
        val list = runCatching { JSONObject(line.removePrefix(MARK)).getJSONArray("rules") }.getOrNull() ?: return null
        return (0 until list.length()).map { Rule.from(list.getJSONObject(it)) }
    }

    /** A quoted Sieve string: `\` and `"` escaped (RFC 5228 section 2.4.2). */
    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun list(values: List<String>): String = values.joinToString(", ", "[", "]") { quote(it) }

    private fun test(c: Condition): String = when (c.field) {
        Condition.Field.SENDER -> "header :contains \"from\" ${quote(c.value)}"
        Condition.Field.RECIPIENT -> "header :contains [\"to\", \"cc\"] ${quote(c.value)}"
        Condition.Field.SUBJECT -> "header :contains \"subject\" ${quote(c.value)}"
        // A single-part attachment says so in the message header, others in a part header.
        Condition.Field.ATTACHMENT -> "anyof (header :contains \"content-disposition\" \"attachment\", " +
            "body :raw :contains [\"Content-Disposition: attachment\", \"Content-Disposition:attachment\"])"
    }
}

/**
 * Filters keeps [Rule]s on the server as the Sieve script named
 * [Sieve.NAME] (JMAP `SieveScript`, RFC 9661) and makes it the active one.
 *
 * Sieve runs one active script per account: saving rules deactivates any
 * other active script (one written by another client), which stays stored.
 * Calls are blocking; run them off the main thread.
 */
class Filters(private val jmap: Jmap) {

    /** The stored rules, whether their script is the active one, and the scripts' state. */
    data class Ruleset(val rules: List<Rule>, val active: Boolean, val state: String)

    private data class Script(val id: String, val blob: String, val active: Boolean)

    private val using = setOf(Jmap.CORE, Jmap.SIEVE)

    /**
     * The stored rules. Throws [AdukiException.Protocol] when the script was
     * edited outside the SDK and lost its rules header.
     */
    fun fetch(): Ruleset {
        val (script, state) = script()
        if (script == null) return Ruleset(emptyList(), false, state)
        val source = jmap.download(script.blob, "${Sieve.NAME}.sieve", "application/sieve").use { String(it.readBytes(), Charsets.UTF_8) }
        val rules = Sieve.parse(source) ?: throw AdukiException.Protocol("The ${Sieve.NAME} Sieve script has no rules header")
        return Ruleset(rules, script.active, state)
    }

    /** The stored rules, in order. */
    fun fetchAll(): List<Rule> = fetch().rules

    /**
     * Replaces the rules with [rules] and activates their script; an empty
     * list removes the script. With [state] (from [fetch]) the write fails
     * with `stateMismatch` if the scripts changed since.
     */
    fun save(rules: List<Rule>, state: String? = null) {
        val (script, _) = script()
        if (rules.isEmpty()) {
            if (script == null) return
            val args = JSONObject().put("destroy", JSONArray().put(script.id))
            // Only switch off what we own: another client's active script stays on.
            if (script.active) args.put("onSuccessDeactivateScript", true)
            state?.let { args.put("ifInState", it) }
            refused(jmap.call("SieveScript/set", args, using), script.id, "notDestroyed")
            return
        }
        val blob = jmap.upload(Sieve.build(rules).toByteArray(Charsets.UTF_8), "application/sieve")
        val args = if (script == null) {
            JSONObject()
                .put("create", JSONObject().put("r", JSONObject().put("name", Sieve.NAME).put("blobId", blob.id)))
                .put("onSuccessActivateScript", "#r")
        } else {
            JSONObject()
                .put("update", JSONObject().put(script.id, JSONObject().put("blobId", blob.id)))
                .put("onSuccessActivateScript", script.id)
        }
        val (error, reply) = write(blob.id, args, state)
        if (error != null) throw AdukiException.Api(400, "invalidSieve", error)
        refused(reply, script?.id ?: "r")
    }

    /**
     * Applies [change] to the stored rules and saves the result, retrying
     * when another writer got in between. Returns the saved rules.
     */
    fun edit(change: (List<Rule>) -> List<Rule>): List<Rule> {
        var attempt = 0
        while (true) {
            val current = fetch()
            val next = change(current.rules)
            try {
                save(next, current.state)
                return next
            } catch (e: AdukiException.Api) {
                if (e.kind != "stateMismatch" || ++attempt >= 3) throw e
            }
        }
    }

    // Validates [blob] and, in the same request, writes [args]: the server
    // compiles on write as well, so a script that fails never lands. Returns
    // the validation error, if any, and the `SieveScript/set` reply.
    private fun write(blob: String, args: JSONObject, state: String?): Pair<String?, JSONObject> {
        val account = jmap.account()
        args.put("accountId", account)
        state?.let { args.put("ifInState", it) }
        val replies = jmap.call(
            listOf(
                Jmap.Invocation("SieveScript/validate", JSONObject().put("accountId", account).put("blobId", blob), "v"),
                Jmap.Invocation("SieveScript/set", args, "s")
            ),
            using
        )
        val error = replies.first { it.id == "v" }.args.optJSONObject("error")
        return error?.let { it.optString("description", it.optString("type")) } to replies.first { it.id == "s" }.args
    }

    private fun script(): Pair<Script?, String> {
        val reply = jmap.call("SieveScript/get", JSONObject(), using)
        val list = reply.optJSONArray("list") ?: JSONArray()
        val script = (0 until list.length()).map { list.getJSONObject(it) }
            .firstOrNull { it.optString("name") == Sieve.NAME }
            ?.let { Script(it.getString("id"), it.getString("blobId"), it.optBoolean("isActive")) }
        return script to reply.optString("state")
    }
}

/**
 * Throws the SetError a `/set` [reply] holds for [id] (in `notCreated`,
 * `notUpdated` or `notDestroyed`, or only in [only]) as
 * [AdukiException.Api] with the error type as its kind.
 */
internal fun refused(reply: JSONObject, id: String, only: String? = null) {
    val keys = only?.let { listOf(it) } ?: listOf("notCreated", "notUpdated", "notDestroyed")
    for (key in keys) {
        val error = reply.optJSONObject(key)?.optJSONObject(id) ?: continue
        val type = error.optString("type", "serverFail")
        throw AdukiException.Api(400, type, error.optString("description", type), details = error.toString())
    }
}
