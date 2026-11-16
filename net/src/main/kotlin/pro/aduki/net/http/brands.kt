package pro.aduki.net.http

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.models.Brand
import pro.aduki.core.models.Verification

/**
 * Sender logos and the evidence behind a verified sender, on the mail
 * server ([endpoint] is its REST base, `https://mail.aduki.pro/v1`).
 * Blocking: run off the main thread.
 */
object Brands {
    /** The most keys one lookup carries; [lookup] splits longer lists. */
    const val BATCH = 20

    /**
     * The logos of sender addresses and domains ([keys]; anything with an
     * `@` is an address), keyed by what was asked, lower case. Every key
     * asked is answered: one without a logo has a [Brand] with no `url`.
     */
    fun lookup(client: OkHttpClient, endpoint: String, keys: Collection<String>): Map<String, Brand> {
        val wanted = keys.map { it.trim().lowercase().removePrefix("mailto:").removeSuffix(".") }.filter { it.isNotEmpty() }.distinct()
        val found = LinkedHashMap<String, Brand>()
        val root = endpoint.toHttpUrl()
        for (batch in wanted.chunked(BATCH)) {
            val body = JSONObject()
            batch.filter { '@' !in it }.takeIf { it.isNotEmpty() }?.let { body.put("domains", JSONArray(it)) }
            batch.filter { '@' in it }.takeIf { it.isNotEmpty() }?.let { body.put("addresses", JSONArray(it)) }
            val url = Rest.url(endpoint, "user", "lookup", "brands").build()
            Rest.execute(client, Rest.request("POST", url, body)) { data ->
                for (o in Rest.items(data)) {
                    val domain = Rest.text(o, "domain")?.lowercase() ?: continue
                    val address = Rest.text(o, "address")?.lowercase()
                    val raw = Rest.text(o, "verified")
                    // The Aduki mark belongs to an official sender's address, never to a whole domain.
                    val verified = raw?.takeIf { it == "vmc" || it == "cmc" || (it == "aduki" && address != null) }
                    val source = Rest.text(o, "source")?.takeIf { it == "workspace" || it == "bimi" || (it == "aduki" && address != null) }
                    val path = Rest.text(o, "url")?.takeUnless { Rest.text(o, "source") == "aduki" && address == null }
                    found[address ?: domain] = Brand(domain, address, path?.let { root.resolve(it)?.toString() }, source, verified)
                }
            }
        }
        for (key in wanted) if (key !in found) {
            found[key] = if ('@' in key) Brand(key.substringAfterLast('@'), key) else Brand(key)
        }
        return found
    }

    /** Why message [hex]'s sender is verified (`GET /v1/user/mail/{hex}/verification`). */
    fun verification(client: OkHttpClient, endpoint: String, hex: String): Verification {
        require(hex.isNotBlank()) { "a message hex is required" }
        val url = Rest.url(endpoint, "user", "mail", hex.trim(), "verification").build()
        val root = endpoint.toHttpUrl()
        return Rest.execute(client, Rest.request("GET", url)) { data -> verification(Rest.obj(data), root) }
    }

    internal fun verification(o: JSONObject, root: okhttp3.HttpUrl): Verification {
        val a = o.optJSONObject("authentication")
        val b = o.optJSONObject("bimi")
        val c = b?.optJSONObject("certificate")
        val tm = c?.optJSONObject("trademark")
        val logo = o.optJSONObject("logo")
        fun check(x: JSONObject?) = x?.let {
            Verification.Check(
                result = it.optString("result"),
                domain = Rest.text(it, "domain"),
                aligned = it.optBoolean("aligned", false),
                selector = Rest.text(it, "selector"),
                policy = Rest.text(it, "policy")
            )
        }
        val dkim = a?.optJSONArray("dkim")
        return Verification(
            hex = Rest.text(o, "id") ?: Rest.text(o, "hex").orEmpty(),
            from = Rest.text(o, "from"),
            domain = Rest.text(o, "domain"),
            verified = Rest.text(o, "verified")?.takeIf { it in setOf("aduki", "vmc", "cmc") },
            passed = o.optBoolean("passed", false),
            official = Rest.text(o, "official"),
            authentication = a?.let {
                Verification.Authentication(
                    internal = it.optBoolean("internal", false),
                    tls = if (it.has("tls") && !it.isNull("tls")) it.optBoolean("tls") else null,
                    dmarc = check(it.optJSONObject("dmarc")),
                    dkim = if (dkim == null) emptyList() else (0 until dkim.length()).mapNotNull { i -> check(dkim.optJSONObject(i)) },
                    spf = check(it.optJSONObject("spf"))
                )
            },
            logo = logo?.let { l ->
                Rest.text(l, "url")?.let { Verification.Logo(root.resolve(it)?.toString() ?: it, Rest.text(l, "source") ?: "bimi") }
            },
            bimi = b?.let {
                Verification.Bimi(
                    record = Rest.text(it, "record"),
                    location = Rest.text(it, "location"),
                    evidence = Rest.text(it, "evidence"),
                    problem = Rest.text(it, "problem"),
                    certificate = c?.let { cert ->
                        Verification.MarkCertificate(
                            kind = Rest.text(cert, "kind"),
                            markType = Rest.text(cert, "markType"),
                            holder = Rest.text(cert, "holder"),
                            country = Rest.text(cert, "country"),
                            issuer = Rest.text(cert, "issuer"),
                            issuerName = Rest.text(cert, "issuerName"),
                            notBefore = Rest.text(cert, "notBefore"),
                            notAfter = Rest.text(cert, "notAfter"),
                            serial = Rest.text(cert, "serial"),
                            domains = Rest.strings(cert.optJSONArray("domains")),
                            trademark = tm?.let { t ->
                                Verification.Trademark(Rest.text(t, "office"), Rest.text(t, "country"), Rest.text(t, "registration"), Rest.text(t, "wordMark"))
                            },
                            ctLog = Rest.text(cert, "ctLog"),
                            ctLogged = Rest.text(cert, "ctLogged")
                        )
                    }
                )
            }
        )
    }
}
