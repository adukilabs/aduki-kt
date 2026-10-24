package pro.aduki.net.http

import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.AdukiException
import java.time.Instant

/**
 * Subscription asks the server to push account changes to a device (JMAP
 * `PushSubscription`, RFC 8620 section 7.2, delivered as Web Push, RFC 8030,
 * or as native Firebase Cloud Messaging).
 *
 * [url] is the device's public HTTPS push endpoint: a UnifiedPush endpoint,
 * or a Web Push endpoint of a push service. [device] identifies the device
 * and app install (stable across launches; at most 255 bytes). With [keys]
 * the server encrypts each push (`aes128gcm`, RFC 8291) for the device;
 * without, the body is plain JSON. [types] limits the pushes to these
 * JMAP types (null: every type). The server caps [expires] at seven days.
 *
 * [fcm] is a native Firebase Cloud Messaging registration token, sent
 * instead of [url] as the `fcmToken` extension property, alone (no url, no
 * keys); each push then arrives as an FCM data message whose `push` entry
 * is the JSON body as a string. A server without an FCM service account
 * refuses it (`invalidProperties`). The server drops a subscription whose
 * token FCM reports as unregistered.
 */
data class Subscription(
    val url: String = "",
    val device: String,
    val keys: Keys? = null,
    val types: List<String>? = null,
    val expires: Instant? = null,
    val fcm: String? = null
) {
    /** The device's RFC 8291 public key ([p256dh], 65 bytes) and [auth] secret (16 bytes), base64url. */
    data class Keys(val p256dh: String, val auth: String)

    /** Throws [IllegalArgumentException] for a non-HTTPS URL, both or neither of URL and token, or a missing device id. */
    fun validate() {
        if (fcm == null) {
            require(url.startsWith("https://")) { "the push URL must be https" }
        } else {
            require(fcm.isNotBlank() && url.isEmpty() && keys == null) { "an FCM token goes alone, without a URL or keys" }
        }
        require(device.isNotBlank() && device.toByteArray(Charsets.UTF_8).size <= 255) { "the device id is blank or too long" }
        keys?.let { require(it.p256dh.isNotBlank() && it.auth.isNotBlank()) { "push keys are incomplete" } }
    }

    // An FCM token goes alone: no url and no keys property at all.
    internal fun json(): JSONObject = JSONObject()
        .apply {
            if (fcm == null) {
                put("url", url)
                put("keys", keys?.let { JSONObject().put("p256dh", it.p256dh).put("auth", it.auth) } ?: JSONObject.NULL)
            } else {
                put("fcmToken", fcm)
            }
        }
        .put("deviceClientId", device)
        .put("types", types?.let { JSONArray(it) } ?: JSONObject.NULL)
        .apply { expires?.let { put("expires", Vacation.date(it)) } }
}

/**
 * Push is one message the server pushes to a [Subscription] endpoint, read
 * from its JSON body (after Web Push decryption, when the subscription has
 * keys) by [parse].
 */
sealed class Push {
    /**
     * `{"@type": "PushVerification", "pushSubscriptionId", "verificationCode"}`:
     * the first push of a new subscription. Nothing else is pushed until the
     * code is sent back ([Subscriptions.verify]).
     */
    data class Verification(val subscription: String, val code: String) : Push()

    /**
     * `{"@type": "StateChange", "changed": {accountId: {type: state}}}`: the
     * new state of each JMAP type that changed, per account. The Aduki mail
     * host sends `Email`, `Thread` and `Mailbox` together on any mail change
     * and carries no content.
     *
     * [mailboxes] is the `mailboxes` extension, `{accountId: [mailbox hex]}`:
     * the mailboxes whose content or counts changed, so [targets] can sync
     * only those. A push without it syncs every mailbox.
     */
    data class Change(
        val changed: Map<String, Map<String, String>>,
        val mailboxes: Map<String, List<String>> = emptyMap()
    ) : Push() {
        /** Whether any account's `Email`, `Thread` or `Mailbox` state moved. */
        fun mail(): Boolean = changed.values.any { types -> types.keys.any { it in MAIL } }

        /**
         * The mailbox ids (REST hexes) to sync, or null when a changed
         * account names none (sync everything).
         */
        fun targets(): List<String>? {
            val accounts = changed.filterValues { types -> types.keys.any { it in MAIL } }.keys
            if (accounts.isEmpty() || accounts.any { mailboxes[it] == null }) return null
            return accounts.flatMap { mailboxes.getValue(it) }.distinct()
        }
    }

    /** `{"@type": "Rights", "epoch"}`: the account's rights changed; renew the access token. */
    data class Rights(val epoch: Long) : Push()

    companion object {
        private val MAIL = setOf("Email", "Thread", "Mailbox")

        /**
         * The push in [payload], or null for anything else. [payload] is the
         * push body, or an FCM data message as JSON (`{"push": "<body>"}`),
         * whose `push` string is read.
         */
        fun parse(payload: String): Push? {
            var obj = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            if (!obj.has("@type")) {
                val inner = obj.opt("push") as? String ?: return null
                obj = runCatching { JSONObject(inner) }.getOrNull() ?: return null
            }
            return when (obj.optString("@type")) {
                "PushVerification" -> Verification(obj.optString("pushSubscriptionId"), obj.optString("verificationCode"))
                    .takeIf { it.subscription.isNotBlank() && it.code.isNotBlank() }
                "StateChange" -> {
                    val changed = obj.optJSONObject("changed") ?: return null
                    val boxes = obj.optJSONObject("mailboxes") ?: JSONObject()
                    Change(
                        changed.keySet().associateWith { account ->
                            val types = changed.optJSONObject(account) ?: JSONObject()
                            types.keySet().associateWith { types.optString(it) }
                        },
                        boxes.keySet().mapNotNull { account ->
                            boxes.optJSONArray(account)?.let { ids -> account to (0 until ids.length()).map(ids::getString) }
                        }.toMap()
                    )
                }
                "Rights" -> Rights(obj.optLong("epoch"))
                else -> null
            }
        }
    }
}

/**
 * Subscriptions registers, verifies and removes push [Subscription]s over
 * JMAP (`PushSubscription/get` and `/set`). Subscriptions belong to the
 * signed-in user, not to an account, so these calls carry no `accountId`.
 * Calls are blocking; run them off the main thread.
 */
class Subscriptions(private val jmap: Jmap) {

    /** One registered subscription; the server never returns its URL or keys. */
    data class Entry(val id: String, val device: String, val expires: Instant?, val types: List<String>?)

    /**
     * Registers [subscription] and returns it with its id. The server then pushes a
     * [Push.Verification] to the URL or FCM token; pass it to [verify].
     */
    fun create(subscription: Subscription): Entry {
        subscription.validate()
        val reply = set(JSONObject().put("create", JSONObject().put("p", subscription.json())))
        refused(reply, "p")
        val made = reply.optJSONObject("created")?.optJSONObject("p")
            ?: throw AdukiException.Protocol("PushSubscription/set did not create the subscription")
        return Entry(made.getString("id"), subscription.device, instant(made, "expires"), subscription.types)
    }

    /** Confirms the subscription with the code of its [Push.Verification]. */
    fun verify(verification: Push.Verification) {
        val args = JSONObject().put("update", JSONObject().put(verification.subscription, JSONObject().put("verificationCode", verification.code)))
        refused(set(args), verification.subscription)
    }

    /** Moves the expiry of subscription [id] to [expires] (at most seven days ahead). */
    fun renew(id: String, expires: Instant) {
        val args = JSONObject().put("update", JSONObject().put(id, JSONObject().put("expires", Vacation.date(expires))))
        refused(set(args), id)
    }

    /** The user's subscriptions, every device's. */
    fun fetchAll(): List<Entry> {
        val reply = jmap.call(listOf(Jmap.Invocation("PushSubscription/get", JSONObject(), "0")), USING).first().args
        val list = reply.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it) }.map { obj ->
            Entry(
                id = obj.getString("id"),
                device = obj.optString("deviceClientId"),
                expires = instant(obj, "expires"),
                types = obj.optJSONArray("types")?.let { types -> (0 until types.length()).map(types::getString) }
            )
        }
    }

    /** Removes the subscriptions [ids]; ones already gone are ignored. */
    fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        set(JSONObject().put("destroy", JSONArray(ids)))
    }

    private fun set(args: JSONObject): JSONObject =
        jmap.call(listOf(Jmap.Invocation("PushSubscription/set", args, "0")), USING).first().args

    private fun instant(obj: JSONObject, key: String): Instant? =
        if (obj.isNull(key)) null else runCatching { Instant.parse(obj.optString(key)) }.getOrNull()

    private companion object {
        val USING = setOf(Jmap.CORE)
    }
}
