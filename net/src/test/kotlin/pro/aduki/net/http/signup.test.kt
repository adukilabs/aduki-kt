package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException

class SignupTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun identity() = server.url("/v1").toString()

    @Test
    fun requirementsDiscoversPhoneAndDomain() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"phone":false,"domain":"user.aduki.pro"}}"""
            )
        )

        val reqs = Signup.requirements(client, identity())

        assertFalse(reqs.phone)
        assertEquals("user.aduki.pro", reqs.domain)

        val req = server.takeRequest()
        assertEquals("/v1/accounts", req.path)
        assertEquals("GET", req.method)
    }

    @Test
    fun requirementsDiscoversPhoneWhenEnabled() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"phone":true,"domain":"user.aduki.pro"}}"""
            )
        )

        val reqs = Signup.requirements(client, identity())

        assertTrue(reqs.phone)
        assertEquals("user.aduki.pro", reqs.domain)
    }

    @Test
    fun idClientReadsRequirementsAndBuildsTheSignUpPage() {
        val idClient = Id(client, identity())
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"phone":false,"domain":"aduki.me"}}"""
            )
        )
        assertEquals("aduki.me", idClient.requirements().domain)
        assertEquals(server.url("/signup").toString(), idClient.signUpUrl())
    }

    @Test
    fun signUpUrlIsTheIdSitePageWithAHandleAndAWayBack() {
        assertEquals("https://id.aduki.pro/signup", Signup.url())
        assertEquals("https://id.aduki.pro/signup", Signup.url("https://id.aduki.pro"))
        assertEquals(
            "https://id.aduki.pro/signup?handle=ada%20l&return=pro.aduki.mail%3A%2Fsignup",
            Signup.url(handle = " ada l ", back = "pro.aduki.mail:/signup")
        )
        assertThrows(IllegalArgumentException::class.java) { Signup.url(back = "no scheme") }
        assertThrows(IllegalArgumentException::class.java) { Signup.url(back = "pro.aduki.mail:/signup#x") }
    }

    @Test
    fun theHandleIsReadOnlyFromTheAppsOwnWayBack() {
        val back = "pro.aduki.mail:/signup"
        assertEquals("ada@aduki.me", Signup.handle("pro.aduki.mail:/signup?handle=ada%40aduki.me", back))
        assertEquals("ada@aduki.me", Signup.handle("PRO.ADUKI.MAIL:/signup?x=1&handle=ada@aduki.me", back))
        assertEquals(null, Signup.handle("pro.aduki.mail:/other?handle=ada", back))
        assertEquals(null, Signup.handle("pro.aduki.calendar:/signup?handle=ada", back))
        assertEquals(null, Signup.handle("pro.aduki.mail:/signup", back))
        assertEquals(null, Signup.handle("pro.aduki.mail:/signup?handle=", back))
        assertEquals("ada", Signup.handle("https://mail.aduki.pro/app/signup?handle=ada", "https://mail.aduki.pro/app/signup"))
        assertEquals(null, Signup.handle("https://evil.example/app/signup?handle=ada", "https://mail.aduki.pro/app/signup"))
    }
}
