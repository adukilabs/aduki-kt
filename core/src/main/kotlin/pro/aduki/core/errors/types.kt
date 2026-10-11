package pro.aduki.core.errors

/**
 * Sealed exception hierarchy for Aduki SDK.
 */
sealed class AdukiException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class Network(message: String, cause: Throwable? = null, val code: Int? = null) :
        AdukiException(message, cause)

    /**
     * An authentication response that could not be used. [refresh] carries a
     * rotated refresh token the response did include, so callers never lose a
     * token the server already swapped in.
     */
    class Auth(message: String, cause: Throwable? = null, val refresh: String = "") :
        AdukiException(message, cause)

    class Unauthorized(message: String, cause: Throwable? = null) :
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
    ) : AdukiException("$kind: $message")

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

