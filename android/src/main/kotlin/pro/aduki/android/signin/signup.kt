package pro.aduki.android.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import pro.aduki.core.config.Endpoints
import pro.aduki.net.http.Signup

/**
 * SignUpTab opens Aduki ID's sign-up page (`https://id.aduki.pro/signup`) in
 * a Custom Tab. Accounts are made there, not in the app: the SDK keeps only
 * sign-in (1.0.0).
 *
 * The app stays under the tab, so closing the tab returns to the app's
 * sign-in screen. With [back], the page sends the person back to the app
 * once the account is made: [back] is a URI only this app handles (its own
 * scheme, such as `pro.aduki.mail:/signup`, or an https App Link) declared
 * on the activity that shows the sign-in, which reads the new handle with
 * [handle] and fills the form with it.
 *
 * ```kotlin
 * val signUp = SignUpTab(back = "pro.aduki.mail:/signup")
 * signUp.open(activity)                                   // the "Create an account" button
 * // in onCreate / onNewIntent of the sign-in activity:
 * signUp.handle(intent)?.let { viewModel.prefill(it) }
 * ```
 */
class SignUpTab(
    private val back: String? = null,
    private val identity: String = Endpoints.ID
) {
    /** The page's address, with [handle] filled in when given. */
    fun url(handle: String? = null): String = Signup.url(identity, handle, back)

    /**
     * The intent that opens the page in a Custom Tab (in the browser when no
     * browser supports Custom Tabs). Start it with `startActivity`.
     */
    fun intent(handle: String? = null): Intent {
        val tab = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setShowTitle(true)
            .build()
        return tab.intent.setData(Uri.parse(url(handle)))
    }

    /** Opens the page from [context]; outside an activity the tab starts in a new task. */
    fun open(context: Context, handle: String? = null) {
        val intent = intent(handle)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * The new account's handle when [intent] is the page's return to [back],
     * else null (another link, or no [back] was set). It is a hint for the
     * sign-in form only.
     */
    fun handle(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val expected = back ?: return null
        if (intent.action != null && intent.action != Intent.ACTION_VIEW) return null
        return Signup.handle(data.toString(), expected)
    }
}
