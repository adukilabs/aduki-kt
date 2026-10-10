package pro.aduki.core.models

/**
 * Identity models authenticated user and tenant state.
 */
data class Identity(
    val user: String = "",
    val tenant: String = "",
    val owner: Boolean = false,
    val scopes: List<String> = emptyList(),
    val tier: String = ""
)

