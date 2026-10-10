package pro.aduki.core.config

/**
 * Client configuration options.
 */
data class Options(
    val endpoint: String = Endpoints.REST,
    val identity: String = Endpoints.ID,
    val grpcHost: String = Endpoints.GRPC_HOST,
    val grpcPort: Int = Endpoints.GRPC_PORT,
    val timeoutSeconds: Long = 15,
    val secure: Boolean = true,
    val maxRetries: Int = 3
)

