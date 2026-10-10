package pro.aduki.core.config

/**
 * Client configuration options.
 */
data class Options(
    val endpoint: String = Endpoints.REST,
    val identity: String = Endpoints.ID,
    val timeoutSeconds: Long = 15,
    val secure: Boolean = true,
    /** TLS pins for the default HTTP client; empty (the default) means no pinning. */
    val pins: List<Pin> = emptyList()
)

