package pro.aduki.android.signin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.cipher.Vault
import pro.aduki.net.http.Oidc

/**
 * SignIn is "Sign in with Aduki" on Android (K7): Authorization Code with PKCE
 * through a Custom Tab, then the redirect back into the app. The protocol is
 * [Oidc]; this class is the launch and the redirect handling.
 *
 * The registered redirect must be a URI only this app handles: a custom scheme
 * (`pro.aduki.app:/callback`) or an https App Link. [accepts] matches scheme,
 * authority and path exactly, so another app's or page's link never reaches the
 * code exchange; [complete] additionally needs the `state` of a sign-in this
 * app started, spends it (a redirect works once) and refuses expired ones.
 * Nothing here ever logs a URL with a code.
 */
class SignIn(
    private val oidc: Oidc,
    private val redirect: String,
    private val pending: PendingStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Starts a sign-in: builds the authorization request (a network call for
     * discovery, so not on the main thread), remembers it and opens the Custom
     * Tab on [context]. Returns the request that was launched.
     */
    fun start(context: Context, scope: List<String> = listOf("openid", "profile")): Oidc.Begin {
        val begin = oidc.begin(scope)
        pending.save(begin, clock())
        launch(context, Uri.parse(begin.url))
        return begin
    }

    /** True when [uri] is this app's redirect (scheme, authority and path equal; the query is the response). */
    fun accepts(uri: Uri): Boolean = matches(redirect, uri)

    /**
     * Completes the sign-in from the [uri] the browser ended on.
     *
     * @throws AdukiException.Auth when [uri] is not our redirect, carries no `state`, or names no sign-in in flight (stray, replayed or expired).
     * @throws AdukiException.Protocol or [AdukiException.Unauthorized] as [Oidc.finish] does (denied, issuer mismatch, bad token).
     */
    fun complete(uri: Uri): Oidc.Grant {
        if (!accepts(uri)) throw AdukiException.Auth("Not the sign-in redirect")
        val state = uri.getQueryParameter("state") ?: throw AdukiException.Auth("Redirect carries no state")
        val begin = pending.take(state, clock()) ?: throw AdukiException.Auth("No sign-in in flight for this redirect")
        return oidc.finish(begin, uri.toString())
    }

    private fun launch(context: Context, uri: Uri) {
        val tab = CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
        tab.intent.data = uri
        if (context !is android.app.Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(tab.intent, tab.startAnimationBundle)
    }

    companion object {
        /** Scheme, authority and path of [callback] equal those of [registered]. Case-insensitive on scheme and host only. */
        fun matches(registered: String, callback: Uri): Boolean {
            val want = Uri.parse(registered)
            return want.scheme.equals(callback.scheme, ignoreCase = true) &&
                want.encodedAuthority.orEmpty().equals(callback.encodedAuthority.orEmpty(), ignoreCase = true) &&
                want.encodedPath.orEmpty() == callback.encodedPath.orEmpty() &&
                callback.fragment == null
        }
    }
}

/**
 * PendingStore keeps the sign-ins in flight (state, nonce, PKCE verifier) in
 * private preferences, so a redirect still works when Android killed the app
 * while the browser was open. Entries last [LIFETIME] ms and are spent by
 * [take]. When a [Vault] is given they are sealed (the verifier is a secret).
 */
class PendingStore(context: Context, private val vault: Vault?) {
    private val prefs = context.applicationContext.getSharedPreferences("pro.aduki.signin", Context.MODE_PRIVATE)

    @Synchronized
    fun save(begin: Oidc.Begin, now: Long) {
        val edit = prefs.edit()
        prefs.all.keys.filter { expired(prefs.getLong("t:$it", 0), now) }.forEach { edit.remove(it) }
        val body = listOf(begin.url, begin.nonce, begin.verifier).joinToString("\n")
        edit.putString(begin.state, vault?.sealText(body) ?: body).putLong("t:${begin.state}", now).apply()
    }

    /** Returns and removes the sign-in with [state], or null when unknown or older than [LIFETIME]. */
    @Synchronized
    fun take(state: String, now: Long): Oidc.Begin? {
        if (state.startsWith("t:")) return null
        val stored = prefs.getString(state, null) ?: return null
        val at = prefs.getLong("t:$state", 0)
        prefs.edit().remove(state).remove("t:$state").apply()
        if (expired(at, now)) return null
        val (url, nonce, verifier) = (vault?.openText(stored) ?: stored).split("\n")
        return Oidc.Begin(url, state, nonce, verifier)
    }

    /** How many sign-ins are waiting. */
    fun size(): Int = prefs.all.keys.count { !it.startsWith("t:") }

    private fun expired(at: Long, now: Long) = now - at > LIFETIME

    companion object {
        /** Ten minutes: the browser step is a person typing a password. */
        const val LIFETIME = 10 * 60 * 1000L
    }
}
