package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
    fun createInitiatesPendingRegistration() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"data":{"id":"01a14179","handle":"ada@user.aduki.pro","totp":{"uri":"otpauth://totp/ada?secret=ABC","secret":"ABC"}}}"""
            )
        )

        val enrolment = Signup.create(client, identity(), "ada", "Ada Lovelace", "password123456")

        assertEquals("01a14179", enrolment.id)
        assertEquals("ada@user.aduki.pro", enrolment.handle)
        assertEquals("ABC", enrolment.secret)
        assertEquals("otpauth://totp/ada?secret=ABC", enrolment.uri)

        val req = server.takeRequest()
        assertEquals("/v1/accounts", req.path)
        assertEquals("POST", req.method)
        val json = JSONObject(req.body.readUtf8())
        assertEquals("ada", json.getString("handle"))
        assertEquals("Ada Lovelace", json.getString("name"))
        assertEquals("password123456", json.getString("password"))
    }

    @Test
    fun verifyConfirmsTotpAndReturnsBackupCodes() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"codes":["backup-1","backup-2"]}}"""
            )
        )

        val codes = Signup.verify(client, identity(), "01a14179", "password123456", "123456")

        assertEquals(listOf("backup-1", "backup-2"), codes)

        val req = server.takeRequest()
        assertEquals("/v1/accounts/01a14179/verifications", req.path)
        assertEquals("POST", req.method)
        val json = JSONObject(req.body.readUtf8())
        assertEquals("password123456", json.getString("password"))
        assertEquals("123456", json.getString("code"))
    }

    @Test
    fun idClientDelegatesSignupFlow() {
        val idClient = Id(client, identity())

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"phone":false,"domain":"user.aduki.pro"}}"""
            )
        )
        val reqs = idClient.requirements()
        assertFalse(reqs.phone)
        assertEquals("user.aduki.pro", reqs.domain)

        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"success":true,"data":{"id":"01a14179","handle":"ada@user.aduki.pro","totp":{"uri":"otpauth://totp/ada?secret=ABC","secret":"ABC"}}}"""
            )
        )
        val enrolment = idClient.create("ada", "Ada Lovelace", "password123456")
        assertEquals("01a14179", enrolment.id)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"codes":["b1","b2"]}}"""
            )
        )
        val codes = idClient.verify("01a14179", "password123456", "123456")
        assertEquals(listOf("b1", "b2"), codes)
    }

    @Test
    fun signupErrorsPropagateAdukiException() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"success":false,"error":{"kind":"input.invalid","message":"address must be 3-32 characters"}}"""
            )
        )

        assertThrows(AdukiException.Network::class.java) {
            Signup.create(client, identity(), "a", "A", "pass")
        }
    }
}
