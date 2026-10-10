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

    class Storage(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Sync(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class Protocol(message: String, cause: Throwable? = null) :
        AdukiException(message, cause)

    class CircuitOpen(message: String = "Circuit breaker is open") :
        AdukiException(message)
}

