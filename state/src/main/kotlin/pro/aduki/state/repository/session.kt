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

    /**
     * Holds [tokens]. Tokens of another session than the one held (another
     * account, handed to a running client on a switch) drop the cached
     * identity, so the next `me()` reads the account behind them. Tokens
     * with no session id are another session when their access token differs.
     */
    fun update(tokens: Tokens) {
        val held = _tokens.value
        _tokens.value = tokens
        if (held != null && differs(held, tokens)) _identity.value = null
    }

    fun updateIdentity(newIdentity: Identity) = update(newIdentity)

    fun token(): String? = _tokens.value?.token

    fun refresh(): String? = _tokens.value?.refresh

    /** Drops the cached identity only; the next `me()` resolves it again. */
    fun forget() {
        _identity.value = null
    }

    fun clear() {
        _identity.value = null
        _tokens.value = null
    }

    companion object {
        /**
         * True when [next] is another Aduki ID session than [held]: another
         * session id, or, without one, another access token. A renewal keeps
         * the session id.
         */
        fun differs(held: Tokens, next: Tokens): Boolean =
            if (next.session.isBlank() || held.session.isBlank()) held.token != next.token else held.session != next.session
    }
}

typealias SessionRepository = Session
