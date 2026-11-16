package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sign-up: the personal domain from Aduki ID and the sign-up page; no account is made through the SDK. */
class SignupTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun identity() = server.url("/id").toString()
    private fun endpoint() = server.url("/v1").toString()

    @Test
    fun requirementsDiscoversParameters() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"phone":false,"domain":"user.aduki.pro"}}"""
            )
        )

        val reqs = Aduki.requirements(identity = identity())

        assertFalse(reqs.phone)
        assertEquals("user.aduki.pro", reqs.domain)
        assertEquals("/id/accounts", server.takeRequest().path)
    }

    @Test
    fun signUpIsAPageInTheBrowserNotAnApiCall() {
        assertEquals("https://id.aduki.pro/signup?return=pro.aduki.mail%3A%2Fsignup", Aduki.signUpUrl(back = "pro.aduki.mail:/signup"))
        assertEquals(server.url("/signup?handle=ada").toString(), Aduki.signUpUrl(handle = "ada", identity = server.url("/v1").toString()))
        assertEquals("ada@aduki.me", Aduki.signedUp("pro.aduki.mail:/signup?handle=ada%40aduki.me", "pro.aduki.mail:/signup"))
        assertEquals(0, server.requestCount)
    }
}
