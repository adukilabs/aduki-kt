package pro.aduki.android.signin

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignUpTabTest {
    private val tab = SignUpTab(back = "pro.aduki.mail:/signup")

    @Test
    fun theIntentOpensTheSignUpPageWithTheWayBack() {
        val intent = tab.intent(handle = "ada")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://id.aduki.pro/signup?handle=ada&return=pro.aduki.mail%3A%2Fsignup", intent.dataString)
        assertEquals("https://id.aduki.pro/signup", SignUpTab().url())
    }

    @Test
    fun theHandleComesOnlyFromTheAppsOwnWayBack() {
        fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        assertEquals("ada@aduki.me", tab.handle(view("pro.aduki.mail:/signup?handle=ada%40aduki.me")))
        assertNull(tab.handle(view("pro.aduki.mail:/other?handle=ada")))
        assertNull(tab.handle(view("https://id.aduki.pro/signup?handle=ada")))
        assertNull(tab.handle(Intent(Intent.ACTION_SEND, Uri.parse("pro.aduki.mail:/signup?handle=ada"))))
        assertNull(tab.handle(null))
        assertNull(SignUpTab().handle(view("pro.aduki.mail:/signup?handle=ada")))
    }
}
