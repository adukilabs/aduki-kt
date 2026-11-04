package pro.aduki.core.errors

/**
 * Sealed exception hierarchy for Aduki SDK.
 */
sealed class AdukiException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * A transport failure, or an HTTP failure with its status [code]. For a
     * 429 or 503 [retryAfter] holds the server's `Retry-After` seconds when
     * it sent them.
     */
    class Network(message: String, cause: Throwable? = null, val code: Int? = null, val retryAfter: Long? = null) :
        AdukiException(message, cause)

    /**
     * An authentication response that could not be used. [refresh] carries a
     * rotated refresh token the response did include, so callers never lose a
     * token the server already swapped in.
     */
    class Auth(message: String, cause: Throwable? = null, val refresh: String = "") :
        AdukiException(message, cause)

    /**
     * A `401` from Aduki ID or a service. [kind] is the server's error kind
     * (`auth.invalid`, `auth.reused`, `auth.revoked`, `auth.expired`,
     * `auth.factor`, `auth.stale`, ...; blank when the answer had none).
     * A DPoP failure (`auth.dpop`, `auth.nonce`) is [Proof] instead, so a
     * `catch (e: Unauthorized)` that drops the session never sees one.
     */
    class Unauthorized(message: String, cause: Throwable?, val kind: String) :
        AdukiException(message, cause) {

        constructor(message: String, cause: Throwable? = null) : this(message, cause, "")

        /**
         * True when the session is over and its tokens must be dropped: a
         * spent, revoked, expired or unknown refresh token (`auth.reused`,
         * `auth.revoked`, `auth.expired`, `auth.invalid`). False for any other
         * kind (`auth.factor`, `auth.stale`) and for a `401` without a kind
         * (a proxy or gateway answer is no verdict on the session).
         */
        val ended: Boolean get() = kind in ENDED

        companion object {
            /** The kinds after which the tokens are useless (Aduki ID, `POST /v1/tokens`). */
            val ENDED = setOf("auth.invalid", "auth.reused", "auth.revoked", "auth.expired")
        }
    }

    /**
     * A DPoP proof was refused (`401 auth.dpop`: invalid, mismatched, replayed
     * or missing for a bound session) or still needs the server nonce after
     * the one retry (`auth.nonce`, OAuth `use_dpop_nonce`). Nothing changed at
     * the server: keep the tokens, fix the key or clock, and retry later.
     * [nonce] is the server's latest nonce when it sent one.
     */
    class Proof(message: String, val kind: String, val nonce: String? = null, cause: Throwable? = null) :
        AdukiException(message, cause)

    /**
     * A failure envelope from an Aduki service (ADK-NET-001 section 4): the HTTP
     * [status], the stable dotted [kind] (`entity.conflict`, `sod.refused`,
     * `rate.limited`, ...), the human [message], the raw `details` JSON object
     * text when present, and the `Retry-After` seconds when the server sent
     * them. A 401 is [Unauthorized] instead.
     */
    class Api(
        val status: Int,
        val kind: String,
        message: String,
        val details: String? = null,
        val retryAfter: Long? = null
    ) : AdukiException("$kind: $message") {
        /**
         * True for a 403 `locked`: the tenant locks profiles and the caller is
         * not an owner, so the name and photo cannot be changed.
         */
        val locked: Boolean get() = status == 403 && (kind == "locked" || kind.endsWith(".locked"))

        /**
         * True for a 403 whose `details.kind` is `calendar.readonly`: a write
         * to a read-only calendar (the "Birthdays & dates" calendar) or its
         * events, or deleting that calendar.
         */
        val readonly: Boolean
            get() = status == 403 && details != null && READONLY.containsMatchIn(details)

        private companion object {
            val READONLY = Regex("\"kind\"\\s*:\\s*\"calendar\\.readonly\"")
        }
    }

    /** The person refused an authorization (device grant `access_denied`). */
    class Denied(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    /** A device code ran out (`expired_token` or the local deadline): start again. */
    class Expired(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Storage(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Sync(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Protocol(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class CircuitOpen(message: String = "Circuit breaker is open") :
        AdukiException(message)
}

