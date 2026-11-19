package pro.aduki.sdk

import pro.aduki.net.http.Dpop

/**
 * The account's sign-in at Aduki Chats (`Aduki.chats`), for the media SDK's end-to-end keys: `pro.aduki:calls` keys a
 * meeting from a Chats MLS group, and its `ChatsAuth` asks for exactly this. Chats takes only DPoP-bound tokens of
 * audience `chat`; its handshake signs the challenge with a proof whose `htm` is `ae09` and `htu` `identity.response`.
 * Blocking: call it off the main thread.
 */
class ChatsSignIn internal constructor(private val bearer: (String?) -> String, private val dpop: Dpop) {
    /** A current `chat` access token; with [stale] (one Chats refused), a renewed one. */
    fun token(stale: String? = null): String = bearer(stale)

    /** A DPoP proof for [method] on [url] bound to [token], with the server's [nonce] when it gave one. */
    fun proof(method: String, url: String, token: String, nonce: String? = null): String =
        dpop.proof(method, url, token, nonce)
}
