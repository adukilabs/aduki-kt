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

/** Aduki staged signup flow: discovery, initiation, confirmation, and registration. */
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
    fun signUpReturnsEnrolment() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"data":{"id":"01a14179","handle":"user@user.aduki.pro","totp":{"uri":"otpauth://totp/user?secret=ABC","secret":"ABC"}}}"""
            )
        )

        val enrolment = Aduki.signUp("user", "User Name", "password123456", identity = identity())

        assertEquals("01a14179", enrolment.id)
        assertEquals("user@user.aduki.pro", enrolment.handle)
        assertEquals("ABC", enrolment.secret)
    }

    @Test
    fun verifyReturnsBackupCodes() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"codes":["backup-code-1","backup-code-2"]}}"""
            )
        )

        val codes = Aduki.verify("01a14179", "password123456", "123456", identity = identity())

        assertEquals(listOf("backup-code-1", "backup-code-2"), codes)
    }

    @Test
    fun registerCompletesVerificationAndSignsIn() = runBlocking {
        // 1. Verify returns backup codes
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"codes":["b1","b2"]}}"""
            )
        )
        // 2. Login returns tokens
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"data":{"session":"00000000000000ab","access":"eyJ.mail","refresh":"rt_1","expires":600}}"""
            )
        )
        // 3. me() resolves identity
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"hex":"u1","tenant":"t1"}"""
            )
        )

        val (client, codes) = Aduki.register(
            handle = "user@user.aduki.pro",
            name = "User",
            password = "password123456",
            code = "123456",
            hex = "01a14179",
            endpoint = endpoint(),
            identity = identity()
        )

        assertEquals(listOf("b1", "b2"), codes)
        assertNotNull(client)
        assertEquals("eyJ.mail", client.session.token())
    }
}
