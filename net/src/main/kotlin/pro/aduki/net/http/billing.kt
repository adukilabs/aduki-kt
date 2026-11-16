package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.AddOn
import pro.aduki.core.models.AddOnPurchase
import pro.aduki.core.models.BillingPage
import pro.aduki.core.models.Invoice
import pro.aduki.core.models.Payment
import pro.aduki.core.models.Subscription
import pro.aduki.core.models.Usage

/**
 * Billing reads a personal account's package, add-ons, usage and its own
 * payments and invoices at Aduki ID (payments moved there from Space). It
 * reads only: buying and paying happen on the web.
 *
 * [bearer] gives tokens of audience `id`. Payments and invoices go through
 * a short portal session (`POST /v1/billing/session`) opened on first use
 * and again when it runs out. A member of a workspace has no package of
 * their own: those reads are `404` ([AdukiException.Api]).
 * Blocking: run off the main thread.
 */
class Billing(
    private val http: OkHttpClient,
    private val bearer: Bearer,
    private val base: String = Endpoints.ID
) {
    private val lock = Any()
    private var portal: String? = null
    private var until = 0L

    /** The account's package (`GET /v1/subscription`). */
    fun subscription(): Subscription {
        val o = Rest.obj(id("GET", "subscription"))
        val next = o.optJSONObject("next")
        return Subscription(
            hex = Rest.text(o, "hex"),
            plan = o.optString("plan").ifBlank { "free" },
            state = o.optString("state"),
            applies = Rest.text(o, "applies"),
            seats = Rest.long(o, "seats")?.toInt(),
            renews = Rest.instant(o, "renews"),
            limits = limits(o.optJSONObject("limits")),
            packageLimits = limits(o.optJSONObject("package")),
            next = Rest.text(next, "plan"),
            nextFrom = Rest.instant(next, "from")
        )
    }

    /** The account's add-on purchases (`GET /v1/addons`). */
    fun addons(): List<AddOnPurchase> = Rest.items(id("GET", "addons")).map { o ->
        AddOnPurchase(
            purchase = o.optString("purchase"),
            addon = o.optString("addon"),
            key = o.optString("key"),
            unit = o.optLong("unit"),
            quantity = o.optInt("quantity"),
            state = o.optString("state"),
            ends = Rest.instant(o, "ends"),
            created = Rest.instant(o, "created"),
            recurring = if (o.has("recurring")) o.optBoolean("recurring") else true,
            amount = Rest.long(o, "amount"),
            currency = Rest.text(o, "currency")
        )
    }

    /** The add-ons the account's package takes now, with prices (`GET /v1/addons/catalogue`). */
    fun addonCatalogue(): List<AddOn> = Rest.items(id("GET", "addons/catalogue")).map { o ->
        val prices = LinkedHashMap<String, Long>()
        o.optJSONObject("prices")?.let { p -> for (k in p.keys()) Rest.long(p, k)?.let { prices[k] = it } }
        AddOn(
            addon = o.optString("addon"),
            key = o.optString("key"),
            unit = o.optLong("unit"),
            recurring = o.optBoolean("recurring", true),
            days = Rest.long(o, "days")?.toInt(),
            label = o.optString("label"),
            description = o.optString("description"),
            prices = prices
        )
    }

    /** The account's month against its plan (`GET /v1/usage`); [period] `YYYY-MM`, this month by default. */
    fun usage(period: String? = null): Usage {
        period?.let { require(Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(it)) { "period is YYYY-MM" } }
        val o = Rest.obj(id("GET", if (period == null) "usage" else "usage?period=$period"))
        val used = LinkedHashMap<String, Long>()
        o.optJSONObject("used")?.let { u ->
            for (k in u.keys()) {
                val v = u.opt(k)
                (if (v is JSONObject) Rest.long(v, "used") else Rest.long(u, k))?.let { used[k] = it }
            }
        }
        val credits = LinkedHashMap<String, Long>()
        o.optJSONObject("credits")?.let { c ->
            for (k in c.keys()) {
                val v = c.opt(k)
                (if (v is JSONObject) Rest.long(v, "minutes") else Rest.long(c, k))?.let { credits[k] = it }
            }
        }
        return Usage(
            period = o.optString("period"),
            plan = Rest.text(o, "plan"),
            state = Rest.text(o, "state"),
            limits = limits(o.optJSONObject("limits")),
            used = used,
            credits = credits
        )
    }

    /** The account's own payments, newest first ([limit] 1 to 100). */
    fun payments(limit: Int? = null, after: String? = null): BillingPage<Payment> =
        page(portal("portal/payments", limit, after)) { o ->
            Payment(
                id = o.optString("id"),
                invoice = o.optString("invoice"),
                provider = o.optString("provider"),
                channel = o.optString("channel"),
                amount = o.optLong("amount"),
                currency = o.optString("currency"),
                status = o.optString("status"),
                initiated = Rest.instant(o, "initiated"),
                settled = Rest.instant(o, "settled")
            )
        }

    /** The account's own invoices, newest first ([limit] 1 to 100). */
    fun invoices(limit: Int? = null, after: String? = null): BillingPage<Invoice> =
        page(portal("portal/invoices", limit, after)) { o ->
            Invoice(
                id = o.optString("id"),
                number = o.optString("number"),
                status = o.optString("status"),
                currency = o.optString("currency"),
                total = o.optLong("total"),
                owing = o.optLong("owing"),
                due = Rest.instant(o, "due"),
                issued = Rest.instant(o, "issued"),
                paid = Rest.instant(o, "paid")
            )
        }

    // ---- internals --------------------------------------------------------------------------------------------------

    private fun id(method: String, path: String, body: JSONObject? = null): Any? {
        val url = "${base.trimEnd('/')}/$path"
        val target = url.toHttpUrl()
        fun build(token: String): Request = Rest.request(method, target, body, mapOf("Authorization" to "Bearer $token"))
        val sent = bearer.token(null)
        return try {
            Rest.execute(http, build(sent)) { it }
        } catch (e: AdukiException.Unauthorized) {
            Rest.execute(http, build(bearer.token(sent))) { it }
        }
    }

    private fun session(fresh: Boolean): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (!fresh) portal?.takeIf { now < until }?.let { return it }
        val data = Rest.obj(id("POST", "billing/session", JSONObject()))
        val s = data.optJSONObject("session") ?: throw AdukiException.Protocol("A billing session answer without a session")
        val token = s.optString("token").ifBlank { throw AdukiException.Protocol("A billing session without a token") }
        val expires = Rest.long(s, "expires") ?: 600L
        // `expires` is seconds from now or a Unix time; keep a minute in hand either way.
        val seconds = if (expires > 1_000_000_000L) expires - now / 1000 else expires
        portal = token
        until = now + (seconds - 60).coerceAtLeast(30) * 1000
        token
    }

    private fun portal(path: String, limit: Int?, after: String?): Any? {
        limit?.let { require(it in 1..100) { "limit is 1 to 100" } }
        val url = Rest.url(base, *path.split('/').toTypedArray()).apply {
            limit?.let { addQueryParameter("limit", it.toString()) }
            after?.let { addQueryParameter("after", it) }
        }.build()
        fun send(token: String) = Rest.execute(http, Rest.request("GET", url, null, mapOf("Authorization" to "Portal $token"))) { it }
        return try {
            send(session(false))
        } catch (e: AdukiException.Unauthorized) {
            send(session(true))
        }
    }

    private fun <T> page(data: Any?, item: (JSONObject) -> T): BillingPage<T> =
        BillingPage(Rest.items(data).map(item), (data as? JSONObject)?.let { Rest.text(it, "next") })

    private fun limits(o: JSONObject?): Map<String, Long?> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Long?>()
        for (k in o.keys()) out[k] = when (val v = o.opt(k)) {
            is Number -> v.toLong()
            is Boolean -> if (v) 1L else 0L
            else -> null
        }
        return out
    }
}
