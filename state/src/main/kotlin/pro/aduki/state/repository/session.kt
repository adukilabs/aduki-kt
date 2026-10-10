package pro.aduki.state.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pro.aduki.core.models.Identity
import pro.aduki.core.models.Tokens

/**
 * Type alias to core Identity model.
 */
typealias Identity = pro.aduki.core.models.Identity

/**
 * Type alias for Tokens.
 */
typealias Tokens = pro.aduki.core.models.Tokens



/**
 * Session manages active identity and token StateFlows.
 */
class Session {
    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _tokens = MutableStateFlow<Tokens?>(null)
    val tokens: StateFlow<Tokens?> = _tokens.asStateFlow()

    fun update(identity: Identity) {
        _identity.value = identity
    }

    fun update(tokens: Tokens) {
        _tokens.value = tokens
    }

    fun updateIdentity(newIdentity: Identity) = update(newIdentity)

    fun token(): String? = _tokens.value?.token

    fun refresh(): String? = _tokens.value?.refresh

    fun clear() {
        _identity.value = null
        _tokens.value = null
    }
}

typealias SessionRepository = Session
