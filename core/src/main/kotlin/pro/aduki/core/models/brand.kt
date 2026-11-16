package pro.aduki.core.models

/**
 * A sender's logo (`POST /v1/user/lookup/brands`): for a sender [address]
 * or a whole [domain].
 *
 * Show it only on a message the server marked verified for that domain
 * (`Body.verified` names it): a logo on an unverified message would let
 * anyone borrow a brand. [verified] is the badge: `aduki` (an official
 * Aduki sender, only ever for an address), `vmc` (a registered trademark)
 * or `cmc` (a common mark); null shows the logo without a badge.
 */
data class Brand(
    val domain: String,
    /** The sender address asked, or null when a domain was asked. */
    val address: String? = null,
    /** The logo's absolute URL (an image or a sanitised SVG), or null when there is none. */
    val url: String? = null,
    /** `aduki`, `workspace` (a verified workspace domain's own logo) or `bimi`; null without a logo. */
    val source: String? = null,
    val verified: String? = null
) {
    /** True when the logo carries a badge ("Verified · Aduki", a VMC or a CMC). */
    val badge: Boolean get() = verified != null

    /** True for an official Aduki sender ("Verified · Aduki"). */
    val aduki: Boolean get() = verified == "aduki"
}

/**
 * Why a message's sender is verified (`GET /v1/user/mail/{hex}/verification`):
 * the evidence behind the badge, for the owner of the message only.
 */
data class Verification(
    /** The message hex. */
    val hex: String,
    /** The `From` address, lower case. */
    val from: String? = null,
    val domain: String? = null,
    /** The badge: `aduki` (an official Aduki sender), `vmc`, `cmc`, or null (no badge). */
    val verified: String? = null,
    /** This message passed DMARC aligned with `From`, or an Aduki account sent it. */
    val passed: Boolean = false,
    /** Why an official sender is one: `service` or `listed`; null for others. */
    val official: String? = null,
    /** This message's DMARC, DKIM and SPF results; null when it has none of the server's (imported mail). */
    val authentication: Authentication? = null,
    /** The logo shown and where it comes from (`aduki`, `workspace`, `bimi`), or null. */
    val logo: Logo? = null,
    /** The BIMI record and its mark certificate, or null when the logo is not a BIMI one. */
    val bimi: Bimi? = null
) {
    /** True for "Verified · Aduki": an official Aduki sender. */
    val aduki: Boolean get() = verified == "aduki"

    data class Logo(val url: String, val source: String)

    /** Authentication results as Aduki Mail recorded them at delivery (relaxed alignment). */
    data class Authentication(
        /** Sent by a signed-in Aduki account. */
        val internal: Boolean,
        /** The hop into Aduki Mail used TLS; null when unknown. */
        val tls: Boolean?,
        val dmarc: Check?,
        val dkim: List<Check>,
        val spf: Check?
    )

    /**
     * One check: its [result] (`pass`, `fail`, ...), the [domain] it is
     * about, whether it is [aligned] with `From`; DKIM has a [selector],
     * DMARC the published [policy].
     */
    data class Check(
        val result: String,
        val domain: String? = null,
        val aligned: Boolean = false,
        val selector: String? = null,
        val policy: String? = null
    )

    /** A BIMI record and its mark certificate. */
    data class Bimi(
        /** The domain whose `default._bimi` record was used. */
        val record: String?,
        /** The record's `l=` logo URL. */
        val location: String?,
        /** The record's `a=` certificate URL. */
        val evidence: String?,
        /** Why the certificate gives no badge (`expired`, `revoked`, `san`, ...); null when valid. */
        val problem: String?,
        val certificate: MarkCertificate?
    )

    /** A VMC or CMC as read from its end entity. Dates are RFC 3339 text. */
    data class MarkCertificate(
        val kind: String?,
        val markType: String?,
        val holder: String?,
        val country: String?,
        val issuer: String?,
        val issuerName: String?,
        val notBefore: String?,
        val notAfter: String?,
        val serial: String?,
        val domains: List<String>,
        val trademark: Trademark?,
        /** The CT log whose timestamp it embeds (its identity only). */
        val ctLog: String?,
        val ctLogged: String?
    )

    data class Trademark(val office: String?, val country: String?, val registration: String?, val wordMark: String?)
}
