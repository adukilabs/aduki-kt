package pro.aduki.android.signin

import android.app.Activity
import android.os.Bundle
import pro.aduki.core.errors.AdukiException

/** App wiring for the two activities below. */
object SignInConfig {
    /** The flow the app built (needs its client id and redirect). */
    @Volatile
    var flow: SignIn? = null

    /** Called on a background thread with the grant, or the failure. */
    @Volatile
    var done: ((Result<pro.aduki.net.http.Oidc.Grant>) -> Unit)? = null
}

/** Starts the Custom Tab (the authenticator's add-account screen) and finishes; the redirect arrives at [RedirectActivity]. */
class SignInActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flow = SignInConfig.flow
        if (flow == null) {
            finish()
            return
        }
        Thread {
            try {
                flow.start(this)
            } catch (e: Exception) {
                SignInConfig.done?.invoke(Result.failure(e))
            }
        }.start()
        finish()
    }
}

/**
 * Receives the redirect (`VIEW` with the app's scheme), exchanges the code on a
 * background thread and reports through [SignInConfig.done]. The activity has
 * no UI and closes at once.
 */
class RedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val flow = SignInConfig.flow
        if (uri != null && flow != null && flow.accepts(uri)) {
            Thread {
                val result = try { Result.success(flow.complete(uri)) } catch (e: AdukiException) { Result.failure(e) } catch (e: Exception) { Result.failure(e) }
                SignInConfig.done?.invoke(result)
            }.start()
        }
        finish()
    }
}
