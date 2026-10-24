package pro.aduki.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pro.aduki.net.http.Filters
import pro.aduki.net.http.Push
import pro.aduki.net.http.Responder
import pro.aduki.net.http.Rule
import pro.aduki.net.http.Sender
import pro.aduki.net.http.Senders
import pro.aduki.net.http.Subscription
import pro.aduki.net.http.Subscriptions
import pro.aduki.net.http.Vacation

/**
 * Settings are the account settings kept on the mail server, so they follow
 * the account to every device: filter rules, the vacation reply, senders
 * and their signatures, and new-mail push. All go over JMAP (`client.jmap`)
 * and need no local store.
 *
 * Calls run on [Dispatchers.IO]. Server refusals are thrown as
 * `AdukiException.Api` with the JMAP error type as `kind`
 * (`invalidSieve`, `forbiddenFrom`, `overQuota`, ...).
 */
class Settings internal constructor(private val client: Aduki) {
    private val filters by lazy { Filters(client.jmap) }
    private val responder by lazy { Responder(client.jmap) }
    private val identities by lazy { Senders(client.jmap) }
    private val subscriptions by lazy { Subscriptions(client.jmap) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * The filter rules, in the order they run. They live in the Sieve script
     * named `aduki`, which saving makes the active script: another active
     * script (written by another client) is switched off but kept.
     */
    suspend fun rules(): List<Rule> = io { filters.fetchAll() }

    /** Adds [rule] after the others; returns the saved rules. */
    suspend fun create(rule: Rule): List<Rule> = io {
        filters.edit { rules ->
            require(rules.none { it.id == rule.id }) { "a rule with id ${rule.id} exists" }
            rules + rule
        }
    }

    /** Replaces the rule with [rule]'s id, keeping its place; returns the saved rules. */
    suspend fun update(rule: Rule): List<Rule> = io {
        filters.edit { rules ->
            require(rules.any { it.id == rule.id }) { "no rule ${rule.id}" }
            rules.map { if (it.id == rule.id) rule else it }
        }
    }

    /** Removes [rule] (by id); returns the saved rules. */
    suspend fun remove(rule: Rule): List<Rule> = io { filters.edit { rules -> rules.filter { it.id != rule.id } } }

    /** Puts the rules in the order of [ids], which must name each rule once; returns them. */
    suspend fun reorder(ids: List<String>): List<Rule> = io {
        filters.edit { rules ->
            require(ids.size == rules.size && ids.toSet() == rules.map { it.id }.toSet()) { "ids do not name each rule once" }
            ids.map { id -> rules.first { it.id == id } }
        }
    }

    /** Replaces every rule with [rules]; an empty list removes the `aduki` script. */
    suspend fun save(rules: List<Rule>) = io { filters.save(rules) }

    /** The vacation reply; disabled and empty when never set. */
    suspend fun vacation(): Vacation = io { responder.fetch() }

    /** Replaces the vacation reply; `Vacation(enabled = false)` turns it off. */
    suspend fun save(vacation: Vacation) = io { responder.save(vacation) }

    /** The addresses the account may send as, the default one first. */
    suspend fun senders(): List<Sender> = io { identities.fetchAll() }

    /** Adds a sender for an address the account may send as; returns it with its id. */
    suspend fun create(sender: Sender): Sender = io { identities.create(sender) }

    /** Saves the name, signatures, reply-to and bcc of [sender]; the address never changes. */
    suspend fun update(sender: Sender) = io { identities.update(sender) }

    /**
     * Makes [sender] the default one (`isDefault` on `Identity`; the others
     * lose it). Mail sent without a `from` goes out as the default sender.
     */
    suspend fun prefer(sender: Sender) = io { identities.prefer(sender.id) }

    /** Removes [sender]; the main one cannot be removed. */
    suspend fun remove(sender: Sender) = io { identities.remove(sender.id) }

    /**
     * Registers this device for new-mail push and returns the registration.
     * Earlier registrations of the same [Subscription.device] are removed
     * first, so calling it again (with a new endpoint, or before the
     * seven-day expiry) replaces it. The server then pushes a
     * verification to the endpoint: hand every push body to [receive].
     */
    suspend fun registerPush(subscription: Subscription): Subscriptions.Entry = io {
        subscription.validate()
        subscriptions.remove(subscriptions.fetchAll().filter { it.device == subscription.device }.map { it.id })
        subscriptions.create(subscription)
    }

    /** Removes every push registration of [device]; returns how many there were. */
    suspend fun unregisterPush(device: String): Int = io {
        val ids = subscriptions.fetchAll().filter { it.device == device }.map { it.id }
        subscriptions.remove(ids)
        ids.size
    }

    /**
     * Handles one push body (JSON, decrypted if the registration has keys;
     * for an FCM registration, the data message's `push` entry, or the data
     * message itself as JSON) and returns what it was, or null for anything
     * else:
     * - [Push.Verification]: sends the code back, completing [registerPush];
     * - [Push.Change] of `Email`, `Thread` or `Mailbox`: runs `client.sync.all()`
     *   on the mailboxes it names ([Push.Change.targets]), else on every
     *   mailbox;
     * - [Push.Rights]: renews the access token, as `client.rights(payload)`.
     */
    suspend fun receive(payload: String): Push? {
        val push = Push.parse(payload) ?: return null
        when (push) {
            is Push.Verification -> io { subscriptions.verify(push) }
            is Push.Change -> if (push.mail()) client.sync.all(push.targets())
            is Push.Rights -> client.rights(body(payload))
        }
        return push
    }

    /**
     * Handles an FCM data message ([data] is `RemoteMessage.data`): its
     * `push` entry is the push body. Null when it has none (a message not
     * from the mail server) or for anything [receive] does not handle.
     */
    suspend fun receive(data: Map<String, String>): Push? = data["push"]?.let { receive(it) }

    // The push body inside an FCM data message given as JSON.
    private fun body(payload: String): String {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return payload
        return if (obj.has("@type")) payload else obj.opt("push") as? String ?: payload
    }
}
