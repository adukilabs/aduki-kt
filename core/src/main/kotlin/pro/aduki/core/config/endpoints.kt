package pro.aduki.core.config

/**
 * Production endpoints for Aduki.
 */
object Endpoints {
    const val REST = "https://mail.aduki.pro/v1"

    /** Aduki ID: sign-in, token refresh and sign-out for every Aduki app. */
    const val ID = "https://id.aduki.pro/v1"

    /** Aduki Space REST API (ADK-SPACE-007). */
    const val SPACE = "https://space.aduki.pro/v1"

    /** The audience Space accepts on Aduki ID tokens (DPoP-bound only). */
    const val SPACE_AUDIENCE = "space"

    /** Aduki Store: drives and the pooled storage quota (`GET /quota`). */
    const val STORE = "https://store.aduki.pro/v1"

    /** The audience Store accepts on Aduki ID tokens. */
    const val STORE_AUDIENCE = "store"

    /** aduki Calls REST API (ADK-AUDIO-013); `audio.aduki.pro` once the service moves to its planned host. */
    const val CALLS = "https://voice.aduki.pro/v1"

    /** The audience aduki Calls accepts on Aduki ID tokens (DPoP-bound only). */
    const val CALLS_AUDIENCE = "audio"

    /** The audience mail accepts on Aduki ID tokens. */
    const val AUDIENCE = "mail"
}

