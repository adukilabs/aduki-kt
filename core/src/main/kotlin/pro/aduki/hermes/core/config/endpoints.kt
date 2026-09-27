package pro.aduki.hermes.core.config

/**
 * Production endpoints for Hermes.
 */
object Endpoints {
    const val REST = "https://hermers.aduki.pro/v1"

    /** Aduki ID: sign-in, token refresh and sign-out for every Aduki app. */
    const val ID = "https://id.aduki.pro/v1"

    /** The audience mail accepts on Aduki ID tokens. */
    const val AUDIENCE = "mail"
    const val GRPC_HOST = "grpc.aduki.pro"
    const val GRPC_PORT = 443
}

